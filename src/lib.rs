pub mod config;
pub mod harness;
pub mod http;
pub mod policy;
pub mod tools;

use config::AppConfig;
use http::{pipe_sse, read_request, start_sse, write_response, write_sse_data, HttpRequest, MAX_BODY};
use policy::{build_payload, parse_input};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::ffi::{c_char, c_void, CStr, CString};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::{
    atomic::{AtomicBool, AtomicUsize, Ordering},
    Arc, Mutex, OnceLock,
};
use std::thread;
use std::time::Duration;

pub const MAX_ACTIVE: usize = 4;
pub const DEFAULT_MAX_TOOL_ROUNDS: usize = 50;
pub const MAX_TOOL_ROUNDS: usize = 500;

/// Unified event sink for both Desktop HTTP SSE streams and Android direct JNI callbacks.
pub trait EventSink {
    /// Emit a single SSE JSON payload (without `data: ` prefix) or `[DONE]`.
    /// Returns `false` if the downstream consumer has cancelled/aborted the stream.
    fn send_event(&mut self, data: &str) -> bool;
}

#[derive(Default)]
struct UsageAcc {
    last_prompt_tokens: u64,
    last_completion_tokens: u64,
    last_cache_hit: u64,
    last_cache_miss: u64,
    cumulative_prompt_tokens: u64,
    completion_tokens: u64,
    cache_hit: u64,
    cache_miss: u64,
    reasoning_tokens: u64,
    web_search_reqs: u64,
    read_url_reqs: u64,
}

impl UsageAcc {
    fn to_json(&self) -> Value {
        let effective_prompt = if self.last_prompt_tokens > 0 {
            self.last_prompt_tokens
        } else {
            self.cumulative_prompt_tokens
        };
        let context_tokens = if self.last_prompt_tokens > 0 {
            self.last_prompt_tokens + self.last_completion_tokens
        } else {
            effective_prompt + self.completion_tokens
        };
        let effective_hit = if self.last_prompt_tokens > 0 {
            self.last_cache_hit
        } else {
            self.cache_hit
        };
        let effective_miss = if self.last_prompt_tokens > 0 {
            self.last_cache_miss
        } else {
            self.cache_miss
        };
        let mut obj = json!({
            "prompt_tokens": effective_prompt,
            "completion_tokens": self.completion_tokens,
            "total_tokens": effective_prompt + self.completion_tokens,
            "context_tokens": context_tokens,
            "cumulative_prompt_tokens": self.cumulative_prompt_tokens,
            "cumulative_total_tokens": self.cumulative_prompt_tokens + self.completion_tokens,
            "prompt_cache_hit_tokens": effective_hit,
            "prompt_cache_miss_tokens": effective_miss,
            "cumulative_cache_hit_tokens": self.cache_hit,
            "cumulative_cache_miss_tokens": self.cache_miss,
            "completion_tokens_details": {
                "reasoning_tokens": self.reasoning_tokens
            }
        });
        if self.web_search_reqs > 0 || self.read_url_reqs > 0 {
            obj["server_tool_use"] = json!({
                "tool_search": self.web_search_reqs,
                "tool_fetch": self.read_url_reqs
            });
        }
        obj
    }
}

fn add_usage(total: &mut UsageAcc, u: &Value) {
    let p = u.get("prompt_tokens").and_then(Value::as_u64).unwrap_or(0);
    let c = u
        .get("completion_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let hit = u
        .get("prompt_cache_hit_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    let miss = u
        .get("prompt_cache_miss_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    if p > 0 {
        total.last_prompt_tokens = p;
        total.last_completion_tokens = c;
        total.last_cache_hit = hit;
        total.last_cache_miss = miss;
    } else if c > 0 && total.last_prompt_tokens == 0 {
        total.last_completion_tokens += c;
    }
    total.cumulative_prompt_tokens += p;
    total.completion_tokens += c;
    total.cache_hit += hit;
    total.cache_miss += miss;
    total.reasoning_tokens += u
        .pointer("/completion_tokens_details/reasoning_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    if let Some(stu) = u.get("server_tool_use") {
        total.web_search_reqs += stu
            .get("tool_search")
            .or_else(|| stu.get("web_search_requests"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
        total.read_url_reqs += stu
            .get("tool_fetch")
            .or_else(|| stu.get("read_url_requests"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
    }
}

pub fn upstream_error_msg(code: u16) -> &'static str {
    match code {
        401 => "API Key 无效",
        402 => "账户余额不足",
        429 => "API 限流，请稍后手动重试",
        400 => "上游拒绝参数，请核对模型、工具与历史格式",
        _ => "上游服务异常；本轮未自动重试",
    }
}

/// Read the real upstream error body so the exact rejection reason is visible.
fn read_upstream_error_body(
    upstream: &mut ureq::http::Response<ureq::Body>,
    code: u16,
) -> String {
    let mut raw = Vec::new();
    if upstream
        .body_mut()
        .as_reader()
        .take(MAX_BODY as u64 + 1)
        .read_to_end(&mut raw)
        .is_ok()
        && raw.len() <= MAX_BODY
    {
        if let Ok(v) = serde_json::from_slice::<Value>(&raw) {
            if let Some(msg) = v.pointer("/error/message").and_then(Value::as_str) {
                if !msg.is_empty() {
                    return msg.to_owned();
                }
            }
        }
        let s = String::from_utf8_lossy(&raw).into_owned();
        if !s.is_empty() && s.len() <= 512 {
            return s;
        }
    }
    upstream_error_msg(code).to_owned()
}

pub fn build_http_agent_with_timeouts(connect_sec: u64, read_sec: u64) -> ureq::Agent {
    let connect = connect_sec.clamp(5, 120);
    let read = read_sec.clamp(30, 600);
    let global = read.max(300);
    let config = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(global)))
        .timeout_connect(Some(Duration::from_secs(connect)))
        .timeout_recv_response(Some(Duration::from_secs(read)))
        .build();
    config.into()
}

pub fn build_http_agent() -> ureq::Agent {
    build_http_agent_with_timeouts(20, 60)
}

pub fn validate_api_key(raw_key: &str) -> Result<String, &'static str> {
    let key = raw_key.trim().strip_prefix("Bearer ").unwrap_or(raw_key.trim());
    if key.is_empty() {
        return Err("请在设置中填入 API Key");
    }
    if key.len() > 512 || !key.bytes().all(|b| b.is_ascii_graphic()) {
        return Err("API Key 格式不正确");
    }
    Ok(format!("Bearer {key}"))
}

/// Direct in-process metadata query (used by Android JNI and Desktop `/api/meta`).
pub fn get_meta_json_str(cfg: &AppConfig) -> String {
    let runtime = harness::probe_runtime_summary(cfg);
    config::meta_json(cfg, runtime).to_string()
}

/// Direct in-process model discovery against `{base}/models` with explicit timeouts.
pub fn fetch_models_json_str_with_timeouts(
    base: &str,
    api_key: &str,
    connect_sec: u64,
    read_sec: u64,
) -> String {
    let validated_base = match policy::validate_base_url(base) {
        Ok(v) => v,
        Err(msg) => {
            return json!({ "ok": false, "error": { "code": "invalid_base_url", "message": msg } })
                .to_string()
        }
    };
    let authorization = match validate_api_key(api_key) {
        Ok(v) => v,
        Err(msg) => {
            return json!({ "ok": false, "error": { "code": "key_required", "message": msg } })
                .to_string()
        }
    };
    let agent = build_http_agent_with_timeouts(connect_sec, read_sec);
    let mut upstream = match agent
        .get(&format!("{validated_base}/models"))
        .header("Authorization", &authorization)
        .call()
    {
        Ok(v) => v,
        Err(_) => {
            return json!({
                "ok": false,
                "error": {
                    "code": "transport",
                    "message": "连接上游失败或超时；本轮未自动重试"
                }
            })
            .to_string()
        }
    };
    let code = upstream.status().as_u16();
    if !(200..300).contains(&code) {
        let detail = read_upstream_error_body(&mut upstream, code);
        return json!({
            "ok": false,
            "error": {
                "code": "upstream_error",
                "message": detail
            }
        })
        .to_string();
    }
    let mut bytes = Vec::new();
    if upstream
        .body_mut()
        .as_reader()
        .take(MAX_BODY as u64 + 1)
        .read_to_end(&mut bytes)
        .is_err()
        || bytes.len() > MAX_BODY
    {
        return json!({
            "ok": false,
            "error": {
                "code": "models_failed",
                "message": "读取模型列表失败"
            }
        })
        .to_string();
    }
    let parsed: Value = match serde_json::from_slice(&bytes) {
        Ok(v) => v,
        Err(_) => {
            return json!({
                "ok": false,
                "error": {
                    "code": "models_failed",
                    "message": "上游模型列表 JSON 格式错误"
                }
            })
            .to_string()
        }
    };
    let data_arr = parsed
        .get("data")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut model_ids = Vec::new();
    let mut models_detail = Vec::new();
    for item in &data_arr {
        if let Some(id) = item
            .get("id")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            model_ids.push(Value::String(id.to_owned()));
            models_detail.push(item.clone());
        }
    }
    let local_tools: Vec<Value> = tools::tool_definitions(true)
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|t| t.pointer("/function/name").and_then(Value::as_str))
                .map(|n| Value::String(n.to_owned()))
                .collect()
        })
        .unwrap_or_default();
    json!({
        "ok": true,
        "base_url": validated_base,
        "models": model_ids,
        "models_detail": models_detail,
        "data": data_arr,
        "local_tools": local_tools
    })
    .to_string()
}

/// Direct in-process model discovery against `{base}/models`.
pub fn fetch_models_json_str(base: &str, api_key: &str) -> String {
    fetch_models_json_str_with_timeouts(base, api_key, 20, 60)
}

fn is_path_within_allowed_roots(file_path: &str, allowed_roots_raw: &str) -> Result<PathBuf, String> {
    let raw = file_path.trim();
    if raw.is_empty() {
        return Err("附件本地路径为空".to_owned());
    }
    let canonical_file =
        std::fs::canonicalize(raw).map_err(|_| "附件本地文件不存在或无法访问".to_owned())?;
    let meta = std::fs::metadata(&canonical_file)
        .map_err(|_| "无法读取附件文件属性".to_owned())?;
    if !meta.is_file() {
        return Err("附件路径不是有效文件".to_owned());
    }
    let roots: Vec<PathBuf> = allowed_roots_raw
        .split('\n')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .filter_map(|r| std::fs::canonicalize(r).ok())
        .collect();
    if roots.is_empty() {
        return Err("未配置受信任的附件目录".to_owned());
    }
    if roots.iter().any(|root| canonical_file.starts_with(root)) {
        Ok(canonical_file)
    } else {
        Err("拒绝读取应用受管附件目录之外的文件".to_owned())
    }
}

#[allow(clippy::too_many_arguments)]
pub fn upload_file_from_path_json_str(
    api_key: &str,
    base_url: &str,
    file_path: &str,
    filename: &str,
    mime_type: &str,
    purpose: &str,
    allowed_roots_newline: &str,
    connect_sec: u64,
    read_sec: u64,
) -> String {
    let effective_base = if base_url.trim().is_empty() {
        "https://api.deepseek.com".to_owned()
    } else {
        match policy::validate_base_url(base_url) {
            Ok(v) => v,
            Err(msg) => return json!({ "ok": false, "error": msg }).to_string(),
        }
    };
    let authorization = match validate_api_key(api_key) {
        Ok(v) => v,
        Err(msg) => return json!({ "ok": false, "error": msg }).to_string(),
    };
    let canonical_file = match is_path_within_allowed_roots(file_path, allowed_roots_newline) {
        Ok(p) => p,
        Err(msg) => return json!({ "ok": false, "error": msg }).to_string(),
    };
    let file_len = match std::fs::metadata(&canonical_file) {
        Ok(m) => m.len(),
        Err(_) => return json!({ "ok": false, "error": "无法获取附件大小" }).to_string(),
    };
    let max_bytes: u64 = if purpose.trim() == "text_recognition"
        || purpose.trim() == "rich_layout_recognition"
    {
        100 * 1024 * 1024
    } else if mime_type.trim().starts_with("image/") {
        64 * 1024 * 1024
    } else {
        32 * 1024 * 1024
    };
    if file_len == 0 {
        return json!({ "ok": false, "error": "附件文件内容为空" }).to_string();
    }
    if file_len > max_bytes {
        return json!({ "ok": false, "error": "附件体积超过官方上传上限" }).to_string();
    }
    let file_bytes = match std::fs::read(&canonical_file) {
        Ok(b) => b,
        Err(e) => {
            return json!({ "ok": false, "error": format!("读取本地附件失败: {e}") }).to_string()
        }
    };
    let boundary = "----FluorineMultipartBoundary7e2a9c4b";
    let multipart = build_file_multipart_body(boundary, purpose, filename, mime_type, &file_bytes);
    let agent = build_http_agent_with_timeouts(connect_sec, read_sec.max(150));
    let mut upstream = match agent
        .post(&format!("{effective_base}/files"))
        .header("Authorization", &authorization)
        .header(
            "Content-Type",
            &format!("multipart/form-data; boundary={boundary}"),
        )
        .send(multipart)
    {
        Ok(v) => v,
        Err(_) => {
            return json!({ "ok": false, "error": "上传文件到上游失败或超时" }).to_string()
        }
    };
    let code = upstream.status().as_u16();
    if !(200..300).contains(&code) {
        let detail = read_upstream_error_body(&mut upstream, code);
        return json!({ "ok": false, "error": detail }).to_string();
    }
    let mut resp_bytes = Vec::new();
    if upstream
        .body_mut()
        .as_reader()
        .take(MAX_BODY as u64 + 1)
        .read_to_end(&mut resp_bytes)
        .is_err()
        || resp_bytes.len() > MAX_BODY
    {
        return json!({ "ok": false, "error": "读取文件上传响应失败或响应过大" }).to_string();
    }
    let parsed: Value = match serde_json::from_slice(&resp_bytes) {
        Ok(v) => v,
        Err(_) => return json!({ "ok": false, "error": "上游文件响应 JSON 格式错误" }).to_string(),
    };
    let file_id = parsed
        .get("id")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim();
    if file_id.is_empty() {
        return json!({ "ok": false, "error": "上游未返回有效 file_id" }).to_string();
    }
    json!({
        "ok": true,
        "id": file_id,
        "filename": parsed.get("filename").and_then(Value::as_str).unwrap_or(filename),
        "bytes": parsed.get("bytes").and_then(Value::as_u64).unwrap_or(file_len),
        "purpose": parsed.get("purpose").and_then(Value::as_str).unwrap_or(purpose)
    })
    .to_string()
}

fn decode_base64(input: &str) -> Result<Vec<u8>, &'static str> {
    let mut vals = Vec::with_capacity(input.len());
    let mut pad = 0usize;
    for b in input.bytes() {
        if b.is_ascii_whitespace() {
            continue;
        }
        if b == b'=' {
            pad += 1;
            continue;
        }
        if pad > 0 {
            return Err("invalid base64 padding");
        }
        let v = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            _ => return Err("invalid base64 character"),
        };
        vals.push(v);
    }
    if pad > 2 || (vals.len() + pad) % 4 != 0 {
        return Err("invalid base64 length");
    }
    let mut out = Vec::with_capacity((vals.len() * 3) / 4);
    let mut i = 0;
    while i + 4 <= vals.len() {
        let n = ((vals[i] as u32) << 18)
            | ((vals[i + 1] as u32) << 12)
            | ((vals[i + 2] as u32) << 6)
            | (vals[i + 3] as u32);
        out.push((n >> 16) as u8);
        out.push((n >> 8) as u8);
        out.push(n as u8);
        i += 4;
    }
    let rem = vals.len() - i;
    if rem == 2 {
        let n = ((vals[i] as u32) << 18) | ((vals[i + 1] as u32) << 12);
        out.push((n >> 16) as u8);
    } else if rem == 3 {
        let n = ((vals[i] as u32) << 18)
            | ((vals[i + 1] as u32) << 12)
            | ((vals[i + 2] as u32) << 6);
        out.push((n >> 16) as u8);
        out.push((n >> 8) as u8);
    }
    Ok(out)
}

fn build_file_multipart_body(
    boundary: &str,
    purpose: &str,
    filename: &str,
    mime_type: &str,
    bytes: &[u8],
) -> Vec<u8> {
    let safe_purpose = match purpose.trim() {
        "text_recognition" | "rich_layout_recognition" | "user_data" => purpose.trim(),
        _ => "user_data",
    };
    let safe_name: String = filename
        .chars()
        .map(|c| if matches!(c, '"' | '\r' | '\n' | '\\') { '_' } else { c })
        .collect();
    let safe_name = if safe_name.trim().is_empty() {
        "upload.bin".to_owned()
    } else {
        safe_name
    };
    let safe_mime: String = mime_type
        .chars()
        .filter(|c| !c.is_control() && *c != ';')
        .collect();
    let safe_mime = if safe_mime.trim().is_empty() {
        "application/octet-stream".to_owned()
    } else {
        safe_mime
    };

    let mut body = Vec::with_capacity(bytes.len() + 512);
    // DeepSeek Files API spec: `purpose` MUST precede `file` in multipart body.
    body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
    body.extend_from_slice(b"Content-Disposition: form-data; name=\"purpose\"\r\n\r\n");
    body.extend_from_slice(safe_purpose.as_bytes());
    body.extend_from_slice(b"\r\n");
    body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
    body.extend_from_slice(
        format!(
            "Content-Disposition: form-data; name=\"file\"; filename=\"{safe_name}\"\r\nContent-Type: {safe_mime}\r\n\r\n"
        )
        .as_bytes(),
    );
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    body
}

#[allow(clippy::too_many_arguments)]
fn execute_client_web_tool(
    name: &str,
    args_json: &str,
    agent: &ureq::Agent,
    base: &str,
    authorization: &str,
    allow_web: bool,
    usage_acc: &mut UsageAcc,
    collected_sources: &mut Vec<Value>,
) -> String {
    if !allow_web {
        return json!({
            "ok": false,
            "error": "Web search/fetch is disabled for this request."
        })
        .to_string();
    }
    let args: Value = match serde_json::from_str(args_json) {
        Ok(v) => v,
        Err(e) => {
            return json!({ "ok": false, "error": format!("invalid JSON arguments: {e}") })
                .to_string()
        }
    };

    if name == "web_search" {
        let query = args.get("query").and_then(Value::as_str).unwrap_or("").trim();
        if query.is_empty() {
            return json!({ "ok": false, "error": "query is required" }).to_string();
        }
        let max_results = args
            .get("max_results")
            .or_else(|| args.get("count"))
            .and_then(Value::as_u64)
            .unwrap_or(5)
            .clamp(1, 20);
        let need_content = args
            .get("need_content")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let req_body = json!({
            "query": query.chars().take(128).collect::<String>(),
            "count": max_results,
            "need_content": need_content
        });
        let mut resp = match agent
            .post(&format!("{base}/v1/tools/search"))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .send(req_body.to_string())
        {
            Ok(v) => v,
            Err(e) => {
                return json!({ "ok": false, "error": format!("search transport failed: {e}") })
                    .to_string()
            }
        };
        let code = resp.status().as_u16();
        if !(200..300).contains(&code) {
            let detail = read_upstream_error_body(&mut resp, code);
            return json!({ "ok": false, "error": detail }).to_string();
        }
        let mut raw = Vec::new();
        if resp
            .body_mut()
            .as_reader()
            .take(MAX_BODY as u64 + 1)
            .read_to_end(&mut raw)
            .is_err()
            || raw.len() > MAX_BODY
        {
            return json!({ "ok": false, "error": "failed reading search response" }).to_string();
        }
        let parsed: Value = match serde_json::from_slice(&raw) {
            Ok(v) => v,
            Err(_) => {
                return json!({ "ok": false, "error": "invalid JSON from /v1/tools/search" })
                    .to_string()
            }
        };
        usage_acc.web_search_reqs += 1;
        let results = parsed.get("results").cloned().unwrap_or_else(|| json!([]));
        if let Some(arr) = results.as_array() {
            for item in arr {
                let url = item.get("url").and_then(Value::as_str).unwrap_or("").trim();
                let title = item.get("title").and_then(Value::as_str).unwrap_or("").trim();
                if !url.is_empty() || !title.is_empty() {
                    collected_sources.push(json!({
                        "title": if title.is_empty() { url } else { title },
                        "url": url,
                        "snippet": item.get("snippet").or_else(|| item.get("summary")).and_then(Value::as_str).unwrap_or(""),
                        "site_name": item.get("site_name").and_then(Value::as_str).unwrap_or(""),
                        "published_at": item.get("date").and_then(Value::as_str).unwrap_or(""),
                        "cite_index": collected_sources.len() + 1
                    }));
                }
            }
        }
        return json!({ "ok": true, "results": results }).to_string();
    }

    if name == "web_fetch" {
        let target_url = args.get("url").and_then(Value::as_str).unwrap_or("").trim();
        if target_url.is_empty() {
            return json!({ "ok": false, "error": "url is required" }).to_string();
        }
        let mut resp = match agent
            .post(&format!("{base}/v1/tools/fetch"))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .send(json!({ "url": target_url }).to_string())
        {
            Ok(v) => v,
            Err(e) => {
                return json!({ "ok": false, "error": format!("fetch transport failed: {e}") })
                    .to_string()
            }
        };
        let code = resp.status().as_u16();
        if !(200..300).contains(&code) {
            let detail = read_upstream_error_body(&mut resp, code);
            return json!({ "ok": false, "error": detail }).to_string();
        }
        let mut raw = Vec::new();
        if resp
            .body_mut()
            .as_reader()
            .take(MAX_BODY as u64 + 1)
            .read_to_end(&mut raw)
            .is_err()
            || raw.len() > MAX_BODY
        {
            return json!({ "ok": false, "error": "failed reading fetch response" }).to_string();
        }
        let parsed: Value = match serde_json::from_slice(&raw) {
            Ok(v) => v,
            Err(_) => {
                return json!({ "ok": false, "error": "invalid JSON from /v1/tools/fetch" })
                    .to_string()
            }
        };
        usage_acc.read_url_reqs += 1;
        let title = parsed
            .get("title")
            .and_then(Value::as_str)
            .unwrap_or(target_url);
        let content = parsed.get("content").and_then(Value::as_str).unwrap_or("");
        let clipped: String = content.chars().take(24_000).collect();
        let truncated = content.chars().count() > 24_000;
        let out_content = if truncated {
            format!("{clipped}\n...[truncated]")
        } else {
            clipped
        };
        let snippet: String = content.chars().take(180).collect();
        collected_sources.push(json!({
            "title": title,
            "url": target_url,
            "snippet": snippet,
            "published_at": parsed.get("date").and_then(Value::as_str).unwrap_or(""),
            "cite_index": collected_sources.len() + 1
        }));
        return json!({
            "ok": true,
            "url": target_url,
            "title": title,
            "content": out_content
        })
        .to_string();
    }

    json!({ "ok": false, "error": format!("unknown web tool: {name}") }).to_string()
}

fn read_bounded_sse_line<R: BufRead>(
    reader: &mut R,
    buf: &mut Vec<u8>,
    max_bytes: usize,
) -> Result<Option<String>, &'static str> {
    buf.clear();
    loop {
        let available = match reader.fill_buf() {
            Ok(b) => b,
            Err(_) => return Err("stream_read_error"),
        };
        if available.is_empty() {
            if buf.is_empty() {
                return Ok(None);
            }
            return Ok(Some(String::from_utf8_lossy(buf).into_owned()));
        }
        if let Some(pos) = available.iter().position(|&b| b == b'\n') {
            if buf.len() + pos > max_bytes {
                return Err("sse_line_too_large");
            }
            buf.extend_from_slice(&available[..pos]);
            reader.consume(pos + 1);
            if buf.last() == Some(&b'\r') {
                buf.pop();
            }
            return Ok(Some(String::from_utf8_lossy(buf).into_owned()));
        }
        if buf.len() + available.len() > max_bytes {
            return Err("sse_line_too_large");
        }
        buf.extend_from_slice(available);
        let consumed = available.len();
        reader.consume(consumed);
    }
}

/// Execute a chat or multi-round local tool loop directly into an `EventSink` (no HTTP loopback required).
pub fn execute_chat_stream(
    sink: &mut dyn EventSink,
    cancelled: &AtomicBool,
    base: &str,
    api_key: &str,
    request_json: &[u8],
    cfg: &AppConfig,
) {
    let authorization = match validate_api_key(api_key) {
        Ok(v) => v,
        Err(msg) => {
            let _ = sink.send_event(
                &json!({"error": {"code": "key_required", "message": msg}}).to_string(),
            );
            return;
        }
    };
    let input = match parse_input(request_json) {
        Ok(v) => v,
        Err(m) => {
            let _ = sink.send_event(
                &json!({"error": {"code": "invalid_json", "message": m}}).to_string(),
            );
            return;
        }
    };
    let built = match build_payload(input) {
        Ok(v) => v,
        Err(m) => {
            let _ = sink.send_event(
                &json!({"error": {"code": "invalid_request", "message": m}}).to_string(),
            );
            return;
        }
    };

    let effective_base = built.base_url.as_deref().unwrap_or(base);
    let agent = build_http_agent_with_timeouts(
        built.connect_timeout_sec.unwrap_or(20),
        built.read_timeout_sec.unwrap_or(180),
    );

    if built.use_local_tools {
        run_local_tool_loop_sink(
            sink,
            cancelled,
            &agent,
            effective_base,
            &authorization,
            cfg,
            &built.enabled_tools,
            built.allow_web,
            built.max_rounds,
            built.body,
        );
        return;
    }

    if cancelled.load(Ordering::SeqCst) {
        let _ = sink.send_event(
            &json!({"error": {"code": "cancelled", "message": "用户已手动停止生成"}}).to_string(),
        );
        return;
    }

    let mut upstream = match agent
        .post(&format!("{effective_base}/chat/completions"))
        .header("Authorization", &authorization)
        .header("Content-Type", "application/json")
        .send(built.body.to_string())
    {
        Ok(v) => v,
        Err(_) => {
            let _ = sink.send_event(
                &json!({"error": {"code": "transport", "message": "连接上游失败或超时；本轮未自动重试"}})
                    .to_string(),
            );
            return;
        }
    };

    let code = upstream.status().as_u16();
    if !(200..300).contains(&code) {
        let detail = read_upstream_error_body(&mut upstream, code);
        let _ = sink.send_event(
            &json!({"error": {"code": "upstream_error", "message": detail}}).to_string(),
        );
        return;
    }

    let mime = upstream
        .headers()
        .get("content-type")
        .and_then(|h| h.to_str().ok())
        .unwrap_or("");
    if !mime.starts_with("text/event-stream") {
        let _ = sink.send_event(
            &json!({"error": {"code": "expected_sse", "message": "上游没有返回事件流"}}).to_string(),
        );
        return;
    }

    let mut reader = BufReader::new(upstream.into_body().into_reader());
    let mut line_buf = Vec::new();
    let mut data_lines: Vec<String> = Vec::new();
    let mut frame_bytes = 0usize;
    loop {
        if cancelled.load(Ordering::SeqCst) {
            let _ = sink.send_event(
                &json!({"error": {"code": "cancelled", "message": "用户已手动停止生成"}}).to_string(),
            );
            return;
        }
        let line = match read_bounded_sse_line(&mut reader, &mut line_buf, MAX_BODY) {
            Ok(Some(l)) => l,
            Ok(None) => break,
            Err("sse_line_too_large") => {
                let _ = sink.send_event(
                    &json!({"error": {"code": "size_limit", "message": "单个 SSE 事件超过 2 MiB 上限，已中止接收"}})
                        .to_string(),
                );
                return;
            }
            Err(_) => break,
        };
        if line.is_empty() {
            if !data_lines.is_empty() {
                let payload = data_lines.join("\n");
                data_lines.clear();
                frame_bytes = 0;
                let is_done = payload.trim() == "[DONE]";
                if !sink.send_event(&payload) || is_done {
                    return;
                }
            }
        } else if let Some(rest) = line.strip_prefix("data:") {
            let clean = rest.strip_prefix(' ').unwrap_or(rest);
            frame_bytes = frame_bytes.saturating_add(clean.len() + 1);
            if frame_bytes > MAX_BODY {
                let _ = sink.send_event(
                    &json!({"error": {"code": "size_limit", "message": "单个 SSE 事件超过 2 MiB 上限，已中止接收"}})
                        .to_string(),
                );
                return;
            }
            data_lines.push(clean.to_owned());
        }
    }
    if !data_lines.is_empty() && !cancelled.load(Ordering::SeqCst) {
        let payload = data_lines.join("\n");
        let _ = sink.send_event(&payload);
    }
}

#[allow(clippy::too_many_arguments)]
fn run_local_tool_loop_sink(
    sink: &mut dyn EventSink,
    cancelled: &AtomicBool,
    agent: &ureq::Agent,
    base: &str,
    authorization: &str,
    cfg: &AppConfig,
    enabled_tools: &HashSet<String>,
    allow_web: bool,
    max_rounds: usize,
    mut payload: Value,
) {
    let max_rounds = max_rounds.clamp(1, MAX_TOOL_ROUNDS);
    let mut usage_acc = UsageAcc::default();
    let mut tool_log: Vec<Value> = Vec::new();
    let mut work_steps: Vec<Value> = Vec::new();
    let mut collected_sources: Vec<Value> = Vec::new();
    let mut subturn_messages: Vec<Value> = Vec::new();

    // Count tool batches, then reserve a response with tools disabled for the summary.
    for round in 0..=max_rounds {
        if cancelled.load(Ordering::SeqCst) {
            let _ = sink.send_event(
                &json!({
                    "error": {"code": "cancelled", "message": "用户已手动停止生成"},
                    "work_steps": work_steps,
                    "subturn_messages": subturn_messages,
                    "usage": usage_acc.to_json()
                })
                .to_string(),
            );
            return;
        }
        if !sink.send_event(
            &json!({
                "choices": [{"delta": {}}],
                "local_tool_event": {"type": "round_started", "round": round + 1, "max_rounds": max_rounds}
            }).to_string(),
        ) {
            return;
        }
        if round == max_rounds {
            payload["tool_choice"] = json!("none");
        }
        let resp = agent
            .post(&format!("{base}/chat/completions"))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .send(payload.to_string());
        let mut upstream = match resp {
            Ok(v) => v,
            Err(_) => {
                let msg = if round == 0 {
                    "连接上游失败或超时；本轮未自动重试"
                } else {
                    "工具执行中途连接上游失败"
                };
                let _ = sink.send_event(
                    &json!({"error": {"message": msg}, "usage": usage_acc.to_json()}).to_string(),
                );
                return;
            }
        };

        let code = upstream.status().as_u16();
        if !(200..300).contains(&code) {
            let detail = read_upstream_error_body(&mut upstream, code);
            let _ = sink.send_event(
                &json!({"error": {"message": detail}, "usage": usage_acc.to_json()}).to_string(),
            );
            return;
        }

        let mut raw = Vec::new();
        if upstream
            .body_mut()
            .as_reader()
            .take(MAX_BODY as u64 + 1)
            .read_to_end(&mut raw)
            .is_err()
            || raw.len() > MAX_BODY
        {
            let _ = sink.send_event(
                &json!({"error": {"message": "读取上游响应失败"}, "usage": usage_acc.to_json()})
                    .to_string(),
            );
            return;
        }

        let body: Value = match serde_json::from_slice(&raw) {
            Ok(v) => v,
            Err(_) => {
                let _ = sink.send_event(
                    &json!({"error": {"message": "上游返回格式错误"}, "usage": usage_acc.to_json()})
                        .to_string(),
                );
                return;
            }
        };

        if let Some(u) = body.get("usage") {
            add_usage(&mut usage_acc, u);
        }

        if let Some(err_obj) = body.get("error") {
            let err_msg = err_obj
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("上游生成失败；已发生的用量仍可能计费");
            let _ = sink.send_event(
                &json!({
                    "error": {"message": err_msg},
                    "usage": usage_acc.to_json()
                })
                .to_string(),
            );
            return;
        }

        let choice = match body.get("choices").and_then(|c| c.get(0)) {
            Some(c) => c,
            None => {
                let _ = sink.send_event(
                    &json!({"error": {"message": "上游未返回有效 choice"}, "usage": usage_acc.to_json()})
                        .to_string(),
                );
                return;
            }
        };

        let msg = choice.get("message").cloned().unwrap_or_else(|| json!({}));
        let finish_reason = choice
            .get("finish_reason")
            .and_then(Value::as_str)
            .unwrap_or("stop");
        let tool_calls = msg
            .get("tool_calls")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();

        let content_raw = msg.get("content").and_then(Value::as_str).unwrap_or("");
        let refusal = msg.get("refusal").and_then(Value::as_str).unwrap_or("");
        let content = if content_raw.is_empty() && !refusal.is_empty() {
            refusal.to_owned()
        } else if !refusal.is_empty() {
            format!("{content_raw}\n{refusal}")
        } else {
            content_raw.to_owned()
        };
        let reasoning = msg
            .get("reasoning_content")
            .and_then(Value::as_str)
            .unwrap_or("");
        work_steps.push(json!({
            "round": round + 1,
            "content": content,
            "thinking": reasoning,
            "annotations": msg.get("annotations").cloned().unwrap_or_else(|| json!([])),
            "local_tool_calls": []
        }));
        if !sink
            .send_event(&json!({"choices": [{"delta": {"work_steps": work_steps}}]}).to_string())
        {
            return;
        }

        if finish_reason == "tool_calls" && !tool_calls.is_empty() {
            if round == max_rounds {
                let _ = sink.send_event(
                    &json!({
                        "error": {"code": "max_rounds", "message": format!("已达到工具轮次上限（{max_rounds} 轮），已保留当前输出")},
                        "usage": usage_acc.to_json()
                    }).to_string(),
                );
                return;
            }

            let mut assistant_msg = json!({
                "role": "assistant",
                "content": msg.get("content").cloned().unwrap_or(json!("")),
                "tool_calls": tool_calls
            });
            if let Some(rc) = msg.get("reasoning_content") {
                if !rc.is_null() {
                    assistant_msg["reasoning_content"] = rc.clone();
                }
            }
            if let Some(arr) = payload.get_mut("messages").and_then(Value::as_array_mut) {
                arr.push(assistant_msg.clone());
            }
            subturn_messages.push(assistant_msg);

            for tc in &tool_calls {
                if cancelled.load(Ordering::SeqCst) {
                    let _ = sink.send_event(
                        &json!({
                            "error": {"code": "cancelled", "message": "用户已手动停止生成"},
                            "work_steps": work_steps,
                            "subturn_messages": subturn_messages,
                            "usage": usage_acc.to_json()
                        })
                        .to_string(),
                    );
                    return;
                }
                let tc_id = tc.get("id").and_then(Value::as_str).unwrap_or("");
                let fn_name = tc
                    .pointer("/function/name")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let fn_args = tc
                    .pointer("/function/arguments")
                    .and_then(Value::as_str)
                    .unwrap_or("{}");

                if !sink.send_event(
                    &json!({
                        "choices": [{"delta": {}}],
                        "local_tool_event": {
                            "type": "call_started",
                            "id": tc_id,
                            "name": fn_name,
                            "arguments": fn_args
                        }
                    })
                    .to_string(),
                ) {
                    return;
                }

                let result = if fn_name == "web_search" || fn_name == "web_fetch" {
                    execute_client_web_tool(
                        fn_name,
                        fn_args,
                        agent,
                        base,
                        authorization,
                        allow_web,
                        &mut usage_acc,
                        &mut collected_sources,
                    )
                } else {
                    tools::execute_tool_with_allowlist(fn_name, fn_args, cfg, Some(enabled_tools))
                };
                let record = json!({
                    "round": round + 1,
                    "id": tc_id,
                    "name": fn_name,
                    "arguments": fn_args,
                    "result": result,
                    "output": result
                });
                tool_log.push(record.clone());
                if let Some(calls) = work_steps
                    .last_mut()
                    .and_then(|step| step["local_tool_calls"].as_array_mut())
                {
                    calls.push(record);
                }

                if !sink.send_event(
                    &json!({
                        "choices": [{"delta": {
                            "work_steps": work_steps,
                            "local_tool_calls": tool_log,
                            "sources": collected_sources
                        }}],
                        "local_tool_event": {
                            "type": "call_completed",
                            "id": tc_id,
                            "name": fn_name,
                            "result": result
                        }
                    })
                    .to_string(),
                ) {
                    return;
                }

                let tool_msg = json!({
                    "role": "tool",
                    "tool_call_id": tc_id,
                    "content": result
                });
                if let Some(arr) = payload.get_mut("messages").and_then(Value::as_array_mut) {
                    arr.push(tool_msg.clone());
                }
                subturn_messages.push(tool_msg);
            }
            continue;
        }

        let mut delta = json!({ "content": content });
        if !reasoning.is_empty() {
            delta["reasoning_content"] = json!(reasoning);
        }
        if !work_steps.is_empty() {
            delta["work_steps"] = json!(work_steps);
        }
        if !tool_log.is_empty() {
            delta["local_tool_calls"] = json!(tool_log);
        }
        if !subturn_messages.is_empty() {
            delta["subturn_messages"] = json!(subturn_messages);
        }
        if !collected_sources.is_empty() {
            delta["sources"] = json!(collected_sources);
        }
        if let Some(annotations) = msg.get("annotations").and_then(Value::as_array) {
            if !annotations.is_empty() {
                delta["annotations"] = Value::Array(annotations.clone());
            }
        }
        if !sink.send_event(
            &json!({
                "choices": [{
                    "delta": delta,
                    "finish_reason": finish_reason
                }]
            })
            .to_string(),
        ) {
            return;
        }
        if !sink.send_event(
            &json!({
                "choices": [],
                "usage": usage_acc.to_json()
            })
            .to_string(),
        ) {
            return;
        }
        let _ = sink.send_event("[DONE]");
        return;
    }

    let _ = sink.send_event(
        &json!({
            "error": {"code": "max_rounds", "message": format!("本地工具调用轮次超过上限（{max_rounds} 轮）")},
            "usage": usage_acc.to_json()
        })
        .to_string(),
    );
}

// ============================================================================
// Desktop HTTP Server Mode (preserved for desktop binary & cargo tests)
// ============================================================================

fn reply_json(stream: &mut TcpStream, status: u16, body: &str) {
    write_response(
        stream,
        status,
        "application/json; charset=utf-8",
        body.as_bytes(),
    );
}

fn reply_error(stream: &mut TcpStream, status: u16, code: &str, msg: &str) {
    reply_json(
        stream,
        status,
        &json!({ "error": { "code": code, "message": msg } }).to_string(),
    );
}

fn auth_header(req: &HttpRequest) -> Result<String, &'static str> {
    validate_api_key(req.header("Authorization"))
}

struct TcpSseSink<'a> {
    stream: &'a mut TcpStream,
    started: bool,
}

impl<'a> EventSink for TcpSseSink<'a> {
    fn send_event(&mut self, data: &str) -> bool {
        if !self.started {
            if start_sse(self.stream).is_err() {
                return false;
            }
            self.started = true;
        }
        if data == "[DONE]" {
            self.stream.write_all(b"data: [DONE]\n\n").is_ok() && self.stream.flush().is_ok()
        } else {
            write_sse_data(self.stream, data).is_ok()
        }
    }
}

pub fn serve(mut stream: TcpStream, port: u16, agent: ureq::Agent, base: &str, cfg: &AppConfig) {
    let req = match read_request(&mut stream) {
        Ok(r) => r,
        Err("body_limit") => {
            return reply_error(&mut stream, 413, "body_limit", "上下文超过 2 MiB，请新建会话")
        }
        Err(_) => return reply_error(&mut stream, 400, "bad_request", "无效的 HTTP 请求"),
    };
    let host = req.header("Host");
    if host != format!("127.0.0.1:{port}") && host != format!("localhost:{port}") {
        return reply_error(&mut stream, 403, "host_denied", "仅允许本地地址");
    }

    if req.method == "GET" {
        match req.path.as_str() {
            "/api/tools" => {
                let body = json!({ "tools": tools::tool_definitions(true) }).to_string();
                return reply_json(&mut stream, 200, &body);
            }
            "/api/meta" => {
                let body = get_meta_json_str(cfg);
                return reply_json(&mut stream, 200, &body);
            }
            "/health" => {
                let body = json!({
                    "ok": true,
                    "app": cfg.app_name,
                    "display_name": cfg.display_name,
                    "version": cfg.version,
                })
                .to_string();
                return reply_json(&mut stream, 200, &body);
            }
            "/" | "/index.html" => {
                let rendered = config::render_index_html(include_str!("../web/index.html"), cfg);
                return write_response(
                    &mut stream,
                    200,
                    "text/html; charset=utf-8",
                    rendered.as_bytes(),
                );
            }
            "/app.js" => {
                return write_response(
                    &mut stream,
                    200,
                    "text/javascript; charset=utf-8",
                    include_str!("../web/app.js").as_bytes(),
                );
            }
            "/core.js" => {
                return write_response(
                    &mut stream,
                    200,
                    "text/javascript; charset=utf-8",
                    include_str!("../web/core.js").as_bytes(),
                );
            }
            "/style.css" => {
                return write_response(
                    &mut stream,
                    200,
                    "text/css; charset=utf-8",
                    include_str!("../web/style.css").as_bytes(),
                );
            }
            _ => return reply_error(&mut stream, 404, "not_found", "没有这个接口"),
        }
    }

    if req.method != "POST" {
        return reply_error(&mut stream, 405, "method_not_allowed", "仅支持 GET / POST");
    }
    if req.header("Origin") != format!("http://{host}") {
        return reply_error(&mut stream, 403, "origin_denied", "仅接受同源页面请求");
    }
    if req.path != "/api/chat" && req.path != "/api/models" && req.path != "/api/files" {
        return reply_error(&mut stream, 404, "not_found", "没有这个接口");
    }
    if !req.header("Content-Type").starts_with("application/json") {
        return reply_error(&mut stream, 415, "content_type", "需要 JSON 请求");
    }
    let authorization = match auth_header(&req) {
        Ok(v) => v,
        Err(m) => return reply_error(&mut stream, 401, "key_required", m),
    };

    if req.path == "/api/files" {
        let body_json: Value = match serde_json::from_slice(&req.body) {
            Ok(v) => v,
            Err(e) => {
                return reply_error(
                    &mut stream,
                    400,
                    "invalid_json",
                    &format!("invalid JSON: {e}"),
                )
            }
        };
        let filename = body_json.get("filename").and_then(Value::as_str).unwrap_or("upload.bin");
        let mime_type = body_json
            .get("mime_type")
            .and_then(Value::as_str)
            .unwrap_or("application/octet-stream");
        let purpose = body_json
            .get("purpose")
            .and_then(Value::as_str)
            .unwrap_or("user_data");
        let data_b64 = body_json
            .get("data_base64")
            .and_then(Value::as_str)
            .unwrap_or("");
        if data_b64.is_empty() {
            return reply_error(&mut stream, 400, "invalid_file", "文件内容不能为空");
        }
        let file_bytes = match decode_base64(data_b64) {
            Ok(b) => b,
            Err(e) => return reply_error(&mut stream, 400, "invalid_base64", e),
        };
        let effective_base = match body_json.get("base_url").and_then(Value::as_str).map(str::trim) {
            Some(s) if !s.is_empty() => match policy::validate_base_url(s) {
                Ok(v) => v,
                Err(msg) => return reply_error(&mut stream, 400, "invalid_base_url", &msg),
            },
            _ => base.to_owned(),
        };
        let boundary = "----FluorineMultipartBoundary7e2a9c4b";
        let multipart = build_file_multipart_body(boundary, purpose, filename, mime_type, &file_bytes);
        let mut upstream = match agent
            .post(&format!("{effective_base}/files"))
            .header("Authorization", &authorization)
            .header("Content-Type", &format!("multipart/form-data; boundary={boundary}"))
            .send(multipart)
        {
            Ok(v) => v,
            Err(_) => {
                return reply_error(
                    &mut stream,
                    502,
                    "transport",
                    "上传文件到上游失败或超时",
                )
            }
        };
        let code = upstream.status().as_u16();
        if !(200..300).contains(&code) {
            let detail = read_upstream_error_body(&mut upstream, code);
            return reply_error(&mut stream, code, "upstream_error", &detail);
        }
        let mut resp_bytes = Vec::new();
        if upstream
            .body_mut()
            .as_reader()
            .take(MAX_BODY as u64 + 1)
            .read_to_end(&mut resp_bytes)
            .is_err()
            || resp_bytes.len() > MAX_BODY
        {
            return reply_error(&mut stream, 502, "upload_failed", "读取文件上传响应失败");
        }
        return write_response(&mut stream, 200, "application/json; charset=utf-8", &resp_bytes);
    }

    if req.path == "/api/models" {
        let models_base = if req.body.is_empty() {
            base.to_owned()
        } else {
            let body_json: Value = match serde_json::from_slice(&req.body) {
                Ok(v) => v,
                Err(e) => {
                    return reply_error(
                        &mut stream,
                        400,
                        "invalid_json",
                        &format!("invalid JSON: {e}"),
                    )
                }
            };
            match body_json.get("base_url").and_then(Value::as_str).map(str::trim) {
                Some(s) if !s.is_empty() => match policy::validate_base_url(s) {
                    Ok(v) => v,
                    Err(msg) => return reply_error(&mut stream, 400, "invalid_base_url", &msg),
                },
                _ => base.to_owned(),
            }
        };
        let mut upstream = match agent
            .get(&format!("{models_base}/models"))
            .header("Authorization", &authorization)
            .call()
        {
            Ok(v) => v,
            Err(_) => {
                return reply_error(
                    &mut stream,
                    502,
                    "transport",
                    "连接上游失败或超时；本轮未自动重试",
                )
            }
        };
        let code = upstream.status().as_u16();
        if !(200..300).contains(&code) {
            return reply_error(&mut stream, code, "upstream_error", upstream_error_msg(code));
        }
        let mut bytes = Vec::new();
        if upstream
            .body_mut()
            .as_reader()
            .take(MAX_BODY as u64 + 1)
            .read_to_end(&mut bytes)
            .is_err()
            || bytes.len() > MAX_BODY
        {
            return reply_error(&mut stream, 502, "models_failed", "读取模型列表失败");
        }
        return write_response(&mut stream, 200, "application/json; charset=utf-8", &bytes);
    }

    let input = match parse_input(&req.body) {
        Ok(v) => v,
        Err(m) => return reply_error(&mut stream, 400, "invalid_json", &m),
    };
    let built = match build_payload(input) {
        Ok(v) => v,
        Err(m) => return reply_error(&mut stream, 400, "invalid_request", &m),
    };
    let effective_base = built.base_url.as_deref().unwrap_or(base);

    if built.use_local_tools {
        let cancelled = AtomicBool::new(false);
        let mut sink = TcpSseSink {
            stream: &mut stream,
            started: false,
        };
        run_local_tool_loop_sink(
            &mut sink,
            &cancelled,
            &agent,
            effective_base,
            &authorization,
            cfg,
            &built.enabled_tools,
            built.allow_web,
            built.max_rounds,
            built.body,
        );
        return;
    }

    let mut upstream = match agent
        .post(&format!("{effective_base}/chat/completions"))
        .header("Authorization", &authorization)
        .header("Content-Type", "application/json")
        .send(built.body.to_string())
    {
        Ok(v) => v,
        Err(_) => {
            return reply_error(
                &mut stream,
                502,
                "transport",
                "连接上游失败或超时；本轮未自动重试",
            )
        }
    };

    let code = upstream.status().as_u16();
    if !(200..300).contains(&code) {
        let detail = read_upstream_error_body(&mut upstream, code);
        return reply_error(&mut stream, code, "upstream_error", &detail);
    }
    let mime = upstream
        .headers()
        .get("content-type")
        .and_then(|h| h.to_str().ok())
        .unwrap_or("");
    if !mime.starts_with("text/event-stream") {
        return reply_error(&mut stream, 502, "expected_sse", "上游没有返回事件流");
    }
    pipe_sse(&mut stream, upstream.into_body().into_reader());
}

pub fn run_server(port: u16, cfg: AppConfig) -> std::io::Result<()> {
    let cfg = Arc::new(cfg);
    let base = if cfg!(debug_assertions) {
        std::env::var("DEEPSEEK_TEST_BASE")
            .ok()
            .filter(|v| {
                v.strip_prefix("http://127.0.0.1:")
                    .is_some_and(|p| p.parse::<u16>().is_ok())
            })
            .unwrap_or_else(|| "https://api.deepseek.com".into())
    } else {
        "https://api.deepseek.com".into()
    };
    let agent = build_http_agent();
    let listener = TcpListener::bind(("127.0.0.1", port))?;
    println!(
        "{} ({}): http://127.0.0.1:{port} [workspace: {}]",
        cfg.display_name,
        cfg.app_name,
        cfg.workspace_root.display()
    );
    let active = Arc::new(AtomicUsize::new(0));
    for conn in listener.incoming() {
        let mut stream = match conn {
            Ok(s) => s,
            Err(_) => continue,
        };
        if active.fetch_add(1, Ordering::SeqCst) >= MAX_ACTIVE {
            active.fetch_sub(1, Ordering::SeqCst);
            reply_error(&mut stream, 503, "busy", "正在处理其他请求，请稍后再试");
            continue;
        }
        let a = agent.clone();
        let b = base.clone();
        let c = Arc::clone(&cfg);
        let count = active.clone();
        thread::spawn(move || {
            struct Guard(Arc<AtomicUsize>);
            impl Drop for Guard {
                fn drop(&mut self) {
                    self.0.fetch_sub(1, Ordering::SeqCst);
                }
            }
            let _guard = Guard(count);
            serve(stream, port, a, &b, &c);
        });
    }
    Ok(())
}

// ============================================================================
// Android Direct JNI Streaming Interface (Zero External Crate Dependencies)
// ============================================================================

type JniEnv = *mut *const *const c_void;
type JObject = *mut c_void;
type JClass = *mut c_void;
type JString = *mut c_void;
type JMethodId = *mut c_void;

#[repr(C)]
#[allow(dead_code)]
union JValue {
    l: JObject,
    j: i64,
}

// Standard JNI 1.1+ JNINativeInterface_ table indices:
const JNI_EXCEPTION_OCCURRED: usize = 15;
const JNI_EXCEPTION_CLEAR: usize = 17;
const JNI_DELETE_LOCAL_REF: usize = 23;
const JNI_GET_OBJECT_CLASS: usize = 31;
const JNI_GET_METHOD_ID: usize = 33;
const JNI_CALL_BOOLEAN_METHOD_A: usize = 39;
const JNI_NEW_STRING_UTF: usize = 167;
const JNI_GET_STRING_UTF_CHARS: usize = 169;
const JNI_RELEASE_STRING_UTF_CHARS: usize = 170;

/// Encodes standard UTF-8 into NUL-terminated Modified UTF-8 (CESU-8) expected by JNI `NewStringUTF`.
/// Supplementary Unicode characters (U+10000..=U+10FFFF, e.g. emoji) are encoded as surrogate pairs
/// so Android ART CheckJNI never aborts on 4-byte UTF-8 sequences.
fn utf8_to_modified_utf8(text: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(text.len() + 8);
    for ch in text.chars() {
        let cp = ch as u32;
        if cp == 0 {
            continue;
        } else if cp <= 0x7F {
            out.push(cp as u8);
        } else if cp <= 0x7FF {
            out.push((0xC0 | (cp >> 6)) as u8);
            out.push((0x80 | (cp & 0x3F)) as u8);
        } else if cp <= 0xFFFF {
            out.push((0xE0 | (cp >> 12)) as u8);
            out.push((0x80 | ((cp >> 6) & 0x3F)) as u8);
            out.push((0x80 | (cp & 0x3F)) as u8);
        } else {
            let v = cp - 0x10000;
            let hi = 0xD800 + (v >> 10);
            let lo = 0xDC00 + (v & 0x3FF);
            for unit in [hi, lo] {
                out.push((0xE0 | (unit >> 12)) as u8);
                out.push((0x80 | ((unit >> 6) & 0x3F)) as u8);
                out.push((0x80 | (unit & 0x3F)) as u8);
            }
        }
    }
    out.push(0);
    out
}

/// Decodes JNI Modified UTF-8 (CESU-8) bytes (without trailing NUL) back into standard UTF-8.
fn modified_utf8_to_utf8(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len());
    let mut i = 0usize;
    while i < bytes.len() {
        let b0 = bytes[i];
        if b0 < 0x80 {
            out.push(b0 as char);
            i += 1;
        } else if (b0 & 0xE0) == 0xC0 && i + 1 < bytes.len() {
            let b1 = bytes[i + 1];
            if b0 == 0xC0 && b1 == 0x80 {
                out.push('\0');
            } else {
                let cp = (((b0 & 0x1F) as u32) << 6) | ((b1 & 0x3F) as u32);
                out.push(char::from_u32(cp).unwrap_or('\u{FFFD}'));
            }
            i += 2;
        } else if (b0 & 0xF0) == 0xE0 && i + 2 < bytes.len() {
            let b1 = bytes[i + 1];
            let b2 = bytes[i + 2];
            let unit1 =
                (((b0 & 0x0F) as u32) << 12) | (((b1 & 0x3F) as u32) << 6) | ((b2 & 0x3F) as u32);
            if (0xD800..=0xDBFF).contains(&unit1)
                && i + 5 < bytes.len()
                && (bytes[i + 3] & 0xF0) == 0xE0
            {
                let b3 = bytes[i + 3];
                let b4 = bytes[i + 4];
                let b5 = bytes[i + 5];
                let unit2 = (((b3 & 0x0F) as u32) << 12)
                    | (((b4 & 0x3F) as u32) << 6)
                    | ((b5 & 0x3F) as u32);
                if (0xDC00..=0xDFFF).contains(&unit2) {
                    let cp = 0x10000 + (((unit1 - 0xD800) << 10) | (unit2 - 0xDC00));
                    out.push(char::from_u32(cp).unwrap_or('\u{FFFD}'));
                    i += 6;
                    continue;
                }
            }
            out.push(char::from_u32(unit1).unwrap_or('\u{FFFD}'));
            i += 3;
        } else {
            out.push('\u{FFFD}');
            i += 1;
        }
    }
    out
}

unsafe fn jni_clear_if_exception(env: JniEnv) -> bool {
    if env.is_null() {
        return false;
    }
    let table = *env;
    let exc_occurred: unsafe extern "C" fn(JniEnv) -> JObject =
        std::mem::transmute(*table.add(JNI_EXCEPTION_OCCURRED));
    let exc = exc_occurred(env);
    if !exc.is_null() {
        let exc_clear: unsafe extern "C" fn(JniEnv) =
            std::mem::transmute(*table.add(JNI_EXCEPTION_CLEAR));
        let del_ref: unsafe extern "C" fn(JniEnv, JObject) =
            std::mem::transmute(*table.add(JNI_DELETE_LOCAL_REF));
        exc_clear(env);
        del_ref(env, exc);
        return true;
    }
    false
}

unsafe fn jni_get_string(env: JniEnv, jstr: JString) -> String {
    if env.is_null() || jstr.is_null() {
        return String::new();
    }
    let table = *env;
    let get_chars: unsafe extern "C" fn(JniEnv, JString, *mut u8) -> *const c_char =
        std::mem::transmute(*table.add(JNI_GET_STRING_UTF_CHARS));
    let rel_chars: unsafe extern "C" fn(JniEnv, JString, *const c_char) =
        std::mem::transmute(*table.add(JNI_RELEASE_STRING_UTF_CHARS));
    let ptr = get_chars(env, jstr, std::ptr::null_mut());
    if ptr.is_null() {
        let _ = jni_clear_if_exception(env);
        return String::new();
    }
    let out = modified_utf8_to_utf8(CStr::from_ptr(ptr).to_bytes());
    rel_chars(env, jstr, ptr);
    out
}

unsafe fn jni_new_string(env: JniEnv, text: &str) -> JString {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let table = *env;
    let new_str: unsafe extern "C" fn(JniEnv, *const c_char) -> JString =
        std::mem::transmute(*table.add(JNI_NEW_STRING_UTF));
    let encoded = utf8_to_modified_utf8(text);
    let jstr = new_str(env, encoded.as_ptr() as *const c_char);
    if jni_clear_if_exception(env) {
        return std::ptr::null_mut();
    }
    jstr
}

struct JniCallbackSink {
    env: JniEnv,
    callback_obj: JObject,
    method_id: JMethodId,
}

impl EventSink for JniCallbackSink {
    fn send_event(&mut self, data: &str) -> bool {
        unsafe {
            if self.env.is_null() || self.callback_obj.is_null() || self.method_id.is_null() {
                return false;
            }
            let table = *self.env;
            let call_bool_a: unsafe extern "C" fn(JniEnv, JObject, JMethodId, *const JValue) -> u8 =
                std::mem::transmute(*table.add(JNI_CALL_BOOLEAN_METHOD_A));
            let del_ref: unsafe extern "C" fn(JniEnv, JObject) =
                std::mem::transmute(*table.add(JNI_DELETE_LOCAL_REF));
            let jdata = jni_new_string(self.env, data);
            if jdata.is_null() {
                return false;
            }
            let args = [JValue { l: jdata }];
            let keep_going =
                call_bool_a(self.env, self.callback_obj, self.method_id, args.as_ptr());
            del_ref(self.env, jdata);
            if jni_clear_if_exception(self.env) {
                return false;
            }
            keep_going != 0
        }
    }
}

fn cancel_registry() -> &'static Mutex<HashMap<i64, Arc<AtomicBool>>> {
    static REGISTRY: OnceLock<Mutex<HashMap<i64, Arc<AtomicBool>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

unsafe fn jni_get_meta_impl(
    env: JniEnv,
    workspace_jstr: JString,
    allow_storage: u8,
) -> JString {
    let workspace = jni_get_string(env, workspace_jstr);
    let cfg = AppConfig::from_android(PathBuf::from(workspace), allow_storage != 0);
    let meta = get_meta_json_str(&cfg);
    jni_new_string(env, &meta)
}

unsafe fn jni_fetch_models_impl(
    env: JniEnv,
    api_key_jstr: JString,
    base_url_jstr: JString,
    connect_timeout_sec: i32,
    read_timeout_sec: i32,
) -> JString {
    let api_key = jni_get_string(env, api_key_jstr);
    let raw_base = jni_get_string(env, base_url_jstr);
    let base = if raw_base.trim().is_empty() {
        "https://api.deepseek.com"
    } else {
        raw_base.trim()
    };
    let resp = fetch_models_json_str_with_timeouts(
        base,
        &api_key,
        connect_timeout_sec.max(5) as u64,
        read_timeout_sec.max(30) as u64,
    );
    jni_new_string(env, &resp)
}

#[allow(clippy::too_many_arguments)]
unsafe fn jni_upload_file_impl(
    env: JniEnv,
    api_key_jstr: JString,
    base_url_jstr: JString,
    file_path_jstr: JString,
    filename_jstr: JString,
    mime_type_jstr: JString,
    purpose_jstr: JString,
    allowed_roots_jstr: JString,
    connect_timeout_sec: i32,
    read_timeout_sec: i32,
) -> JString {
    let api_key = jni_get_string(env, api_key_jstr);
    let base_url = jni_get_string(env, base_url_jstr);
    let file_path = jni_get_string(env, file_path_jstr);
    let filename = jni_get_string(env, filename_jstr);
    let mime_type = jni_get_string(env, mime_type_jstr);
    let purpose = jni_get_string(env, purpose_jstr);
    let allowed_roots = jni_get_string(env, allowed_roots_jstr);
    let resp = upload_file_from_path_json_str(
        &api_key,
        &base_url,
        &file_path,
        &filename,
        &mime_type,
        &purpose,
        &allowed_roots,
        connect_timeout_sec.max(5) as u64,
        read_timeout_sec.max(30) as u64,
    );
    jni_new_string(env, &resp)
}

unsafe fn jni_stream_chat_impl(
    env: JniEnv,
    request_id: i64,
    api_key_jstr: JString,
    request_json_jstr: JString,
    workspace_jstr: JString,
    allow_storage: u8,
    callback_obj: JObject,
) {
    if env.is_null() || callback_obj.is_null() {
        return;
    }
    let api_key = jni_get_string(env, api_key_jstr);
    let request_json = jni_get_string(env, request_json_jstr);
    let workspace = jni_get_string(env, workspace_jstr);
    let cfg = AppConfig::from_android(PathBuf::from(workspace), allow_storage != 0);

    let table = *env;
    let get_cls: unsafe extern "C" fn(JniEnv, JObject) -> JClass =
        std::mem::transmute(*table.add(JNI_GET_OBJECT_CLASS));
    let get_mid: unsafe extern "C" fn(JniEnv, JClass, *const c_char, *const c_char) -> JMethodId =
        std::mem::transmute(*table.add(JNI_GET_METHOD_ID));
    let del_ref: unsafe extern "C" fn(JniEnv, JObject) =
        std::mem::transmute(*table.add(JNI_DELETE_LOCAL_REF));

    let cls = get_cls(env, callback_obj);
    if cls.is_null() || jni_clear_if_exception(env) {
        return;
    }
    let method_name = CString::new("onEvent").unwrap();
    let method_sig = CString::new("(Ljava/lang/String;)Z").unwrap();
    let method_id = get_mid(env, cls, method_name.as_ptr(), method_sig.as_ptr());
    del_ref(env, cls);
    if method_id.is_null() || jni_clear_if_exception(env) {
        return;
    }

    let cancel_flag = Arc::new(AtomicBool::new(false));
    if let Ok(mut map) = cancel_registry().lock() {
        map.insert(request_id, Arc::clone(&cancel_flag));
    }

    let mut sink = JniCallbackSink {
        env,
        callback_obj,
        method_id,
    };

    execute_chat_stream(
        &mut sink,
        &cancel_flag,
        "https://api.deepseek.com",
        &api_key,
        request_json.as_bytes(),
        &cfg,
    );

    if let Ok(mut map) = cancel_registry().lock() {
        map.remove(&request_id);
    }
}

fn jni_cancel_impl(request_id: i64) -> u8 {
    if let Ok(map) = cancel_registry().lock() {
        if let Some(flag) = map.get(&request_id) {
            flag.store(true, Ordering::SeqCst);
            return 1;
        }
    }
    0
}

unsafe fn jni_execute_local_tool_impl(
    env: JniEnv,
    name_jstr: JString,
    args_jstr: JString,
    workspace_jstr: JString,
    allow_storage: u8,
) -> JString {
    let name = jni_get_string(env, name_jstr);
    let args = jni_get_string(env, args_jstr);
    let workspace = jni_get_string(env, workspace_jstr);
    let cfg = AppConfig::from_android(PathBuf::from(workspace), allow_storage != 0);
    let out = tools::execute_tool(&name, &args, &cfg, true);
    jni_new_string(env, &out)
}

unsafe fn jni_build_upstream_payload_impl(env: JniEnv, request_json_jstr: JString) -> JString {
    let request_json = jni_get_string(env, request_json_jstr);
    let out = match parse_input(request_json.as_bytes()).and_then(build_payload) {
        Ok(built) => built.body.to_string(),
        Err(msg) => json!({ "error": msg }).to_string(),
    };
    jni_new_string(env, &out)
}

// Export JNI symbols for `com.uronic0x.fluorine.bridge.FluorineBridge`

/// # Safety
/// Must be called by the JVM/ART JNI bridge with a valid `JNIEnv*` and Java `String` reference.
#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeGetMeta(
    env: JniEnv,
    _thiz: JObject,
    workspace_jstr: JString,
    allow_storage: u8,
) -> JString {
    jni_get_meta_impl(env, workspace_jstr, allow_storage)
}

/// # Safety
/// Must be called by the JVM/ART JNI bridge with a valid `JNIEnv*` and Java `String` references.
#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeFetchModels(
    env: JniEnv,
    _thiz: JObject,
    api_key_jstr: JString,
    base_url_jstr: JString,
    connect_timeout_sec: i32,
    read_timeout_sec: i32,
) -> JString {
    jni_fetch_models_impl(
        env,
        api_key_jstr,
        base_url_jstr,
        connect_timeout_sec,
        read_timeout_sec,
    )
}

/// # Safety
/// Must be called by the JVM/ART JNI bridge with a valid `JNIEnv*` and Java `String` references.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeUploadFile(
    env: JniEnv,
    _thiz: JObject,
    api_key_jstr: JString,
    base_url_jstr: JString,
    file_path_jstr: JString,
    filename_jstr: JString,
    mime_type_jstr: JString,
    purpose_jstr: JString,
    allowed_roots_jstr: JString,
    connect_timeout_sec: i32,
    read_timeout_sec: i32,
) -> JString {
    jni_upload_file_impl(
        env,
        api_key_jstr,
        base_url_jstr,
        file_path_jstr,
        filename_jstr,
        mime_type_jstr,
        purpose_jstr,
        allowed_roots_jstr,
        connect_timeout_sec,
        read_timeout_sec,
    )
}

/// # Safety
/// Must be called by the JVM/ART JNI bridge with a valid `JNIEnv*`, Java `String` references,
/// and a callback implementing `FluorineBridge$JniStreamCallback`.
#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeStreamChat(
    env: JniEnv,
    _thiz: JObject,
    request_id: i64,
    api_key_jstr: JString,
    request_json_jstr: JString,
    workspace_jstr: JString,
    allow_storage: u8,
    callback_obj: JObject,
) {
    jni_stream_chat_impl(
        env,
        request_id,
        api_key_jstr,
        request_json_jstr,
        workspace_jstr,
        allow_storage,
        callback_obj,
    );
}

/// # Safety
/// Must be called by the JVM/ART JNI bridge with a valid `JNIEnv*`.
#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeCancelStream(
    _env: JniEnv,
    _thiz: JObject,
    request_id: i64,
) -> u8 {
    jni_cancel_impl(request_id)
}

/// # Safety
/// Must be called by the JVM/ART JNI bridge with a valid `JNIEnv*` and Java `String` references.
#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeExecuteLocalTool(
    env: JniEnv,
    _thiz: JObject,
    name_jstr: JString,
    args_jstr: JString,
    workspace_jstr: JString,
    allow_storage: u8,
) -> JString {
    jni_execute_local_tool_impl(env, name_jstr, args_jstr, workspace_jstr, allow_storage)
}

/// # Safety
/// Must be called by the JVM/ART JNI bridge with a valid `JNIEnv*` and Java `String` reference.
#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeBuildUpstreamPayload(
    env: JniEnv,
    _thiz: JObject,
    request_json_jstr: JString,
) -> JString {
    jni_build_upstream_payload_impl(env, request_json_jstr)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct VecSink(Vec<String>);
    impl EventSink for VecSink {
        fn send_event(&mut self, data: &str) -> bool {
            self.0.push(data.to_owned());
            true
        }
    }

    #[test]
    fn cancelled_stream_emits_explicit_cancelled_event() {
        let mut sink = VecSink(Vec::new());
        let cancelled = AtomicBool::new(true);
        let cfg = AppConfig::from_env();
        let req = serde_json::to_vec(&json!({
            "model": "deepseek-flash",
            "stage": "chat",
            "messages": [{"role": "user", "content": "hi"}]
        }))
        .unwrap();
        execute_chat_stream(
            &mut sink,
            &cancelled,
            "http://127.0.0.1:9",
            "sk-test",
            &req,
            &cfg,
        );
        assert_eq!(sink.0.len(), 1);
        let v: Value = serde_json::from_str(&sink.0[0]).unwrap();
        assert_eq!(v["error"]["code"], "cancelled");
    }

    #[test]
    fn multipart_file_body_places_purpose_before_file_and_decodes_base64() {
        let decoded = decode_base64("aGVsbG8=").unwrap();
        assert_eq!(decoded, b"hello");
        let body = build_file_multipart_body(
            "b1",
            "rich_layout_recognition",
            "report.pdf",
            "application/pdf",
            &decoded,
        );
        let s = String::from_utf8_lossy(&body);
        let p_idx = s.find("name=\"purpose\"").unwrap();
        let f_idx = s.find("name=\"file\"").unwrap();
        assert!(p_idx < f_idx);
        assert!(s.contains("rich_layout_recognition"));
        assert!(s.contains("filename=\"report.pdf\""));
    }

    #[test]
    fn modified_utf8_round_trips_supplementary_emoji_and_cjk() {
        let sample = "你好 Fluorine 😀🚀 数学 ∑∫";
        let encoded = utf8_to_modified_utf8(sample);
        assert_eq!(*encoded.last().unwrap(), 0);
        // 4-byte UTF-8 emoji must be split into 6-byte CESU-8 surrogate pairs (no 0xF0..0xF4 leading byte)
        assert!(!encoded.iter().any(|&b| (0xF0..=0xF4).contains(&b)));
        let decoded = modified_utf8_to_utf8(&encoded[..encoded.len() - 1]);
        assert_eq!(decoded, sample);
    }

    #[test]
    fn bounded_sse_line_reader_rejects_oversized_lines() {
        let huge = format!("data: {}\n", "a".repeat(256));
        let mut reader = BufReader::new(huge.as_bytes());
        let mut buf = Vec::new();
        assert_eq!(
            read_bounded_sse_line(&mut reader, &mut buf, 128),
            Err("sse_line_too_large")
        );
    }

    #[test]
    fn upload_path_check_blocks_files_outside_allowed_roots() {
        let tmp = std::env::temp_dir().join("fluorine_upload_root_test");
        let allowed = tmp.join("attachments");
        let outside = tmp.join("shared_prefs");
        let _ = std::fs::create_dir_all(&allowed);
        let _ = std::fs::create_dir_all(&outside);
        let ok_file = allowed.join("doc.txt");
        let bad_file = outside.join("secret.xml");
        std::fs::write(&ok_file, "ok").unwrap();
        std::fs::write(&bad_file, "secret").unwrap();

        let allowed_str = allowed.display().to_string();
        assert!(is_path_within_allowed_roots(&ok_file.display().to_string(), &allowed_str).is_ok());
        assert!(is_path_within_allowed_roots(&bad_file.display().to_string(), &allowed_str).is_err());

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
