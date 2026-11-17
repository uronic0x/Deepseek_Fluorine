pub mod config;
pub mod harness;
pub mod http;
pub mod policy;
pub mod tools;

use config::AppConfig;
use http::{pipe_sse, read_request, start_sse, write_response, write_sse_data, HttpRequest, MAX_BODY};
use policy::{build_payload, parse_input};
use serde_json::{json, Value};
use std::collections::HashMap;
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
pub const MAX_TOOL_ROUNDS: usize = 20;

/// Unified event sink for both Desktop HTTP SSE streams and Android direct JNI callbacks.
pub trait EventSink {
    /// Emit a single SSE JSON payload (without `data: ` prefix) or `[DONE]`.
    /// Returns `false` if the downstream consumer has cancelled/aborted the stream.
    fn send_event(&mut self, data: &str) -> bool;
}

#[derive(Default)]
struct UsageAcc {
    prompt_tokens: u64,
    completion_tokens: u64,
    cache_hit: u64,
    cache_miss: u64,
    reasoning_tokens: u64,
}

impl UsageAcc {
    fn to_json(&self) -> Value {
        json!({
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "prompt_cache_hit_tokens": self.cache_hit,
            "prompt_cache_miss_tokens": self.cache_miss,
            "completion_tokens_details": {
                "reasoning_tokens": self.reasoning_tokens
            }
        })
    }
}

fn add_usage(total: &mut UsageAcc, u: &Value) {
    total.prompt_tokens += u.get("prompt_tokens").and_then(Value::as_u64).unwrap_or(0);
    total.completion_tokens += u
        .get("completion_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    total.cache_hit += u
        .get("prompt_cache_hit_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    total.cache_miss += u
        .get("prompt_cache_miss_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    total.reasoning_tokens += u
        .pointer("/completion_tokens_details/reasoning_tokens")
        .and_then(Value::as_u64)
        .unwrap_or(0);
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

pub fn build_http_agent() -> ureq::Agent {
    let config = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(300)))
        .timeout_connect(Some(Duration::from_secs(20)))
        .timeout_recv_response(Some(Duration::from_secs(60)))
        .build();
    config.into()
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

/// Direct in-process model discovery against `https://api.deepseek.com/models`.
pub fn fetch_models_json_str(base: &str, api_key: &str) -> String {
    let authorization = match validate_api_key(api_key) {
        Ok(v) => v,
        Err(msg) => return json!({ "error": { "code": "key_required", "message": msg } }).to_string(),
    };
    let agent = build_http_agent();
    let mut upstream = match agent
        .get(&format!("{base}/models"))
        .header("Authorization", &authorization)
        .call()
    {
        Ok(v) => v,
        Err(_) => {
            return json!({
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
        return json!({
            "error": {
                "code": "upstream_error",
                "message": upstream_error_msg(code)
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
            "error": {
                "code": "models_failed",
                "message": "读取模型列表失败"
            }
        })
        .to_string();
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

/// Execute a chat or 20-round local tool loop directly into an `EventSink` (no HTTP loopback required).
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
            let _ = sink.send_event(&json!({"error": {"code": "key_required", "message": msg}}).to_string());
            return;
        }
    };
    let input = match parse_input(request_json) {
        Ok(v) => v,
        Err(m) => {
            let _ = sink.send_event(&json!({"error": {"code": "invalid_json", "message": m}}).to_string());
            return;
        }
    };
    let built = match build_payload(input) {
        Ok(v) => v,
        Err(m) => {
            let _ = sink.send_event(&json!({"error": {"code": "invalid_request", "message": m}}).to_string());
            return;
        }
    };

    let agent = build_http_agent();
    if built.use_local_tools {
        run_local_tool_loop_sink(
            sink,
            cancelled,
            &agent,
            base,
            &authorization,
            cfg,
            built.allow_device_tools,
            built.body,
        );
        return;
    }

    if cancelled.load(Ordering::SeqCst) {
        return;
    }

    let upstream = match agent
        .post(&format!("{base}/chat/completions"))
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
        let _ = sink.send_event(
            &json!({"error": {"code": "upstream_error", "message": upstream_error_msg(code)}}).to_string(),
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

    let reader = BufReader::new(upstream.into_body().into_reader());
    let mut data_lines: Vec<String> = Vec::new();
    for line_res in reader.lines() {
        if cancelled.load(Ordering::SeqCst) {
            break;
        }
        let line = match line_res {
            Ok(l) => l,
            Err(_) => break,
        };
        let trimmed = line.trim_end_matches('\r');
        if trimmed.is_empty() {
            if !data_lines.is_empty() {
                let payload = data_lines.join("\n");
                data_lines.clear();
                let is_done = payload.trim() == "[DONE]";
                if !sink.send_event(&payload) || is_done {
                    return;
                }
            }
        } else if let Some(rest) = trimmed.strip_prefix("data:") {
            let clean = rest.strip_prefix(' ').unwrap_or(rest);
            data_lines.push(clean.to_owned());
        }
    }
    if !data_lines.is_empty() && !cancelled.load(Ordering::SeqCst) {
        let payload = data_lines.join("\n");
        let _ = sink.send_event(&payload);
    }
}

fn run_local_tool_loop_sink(
    sink: &mut dyn EventSink,
    cancelled: &AtomicBool,
    agent: &ureq::Agent,
    base: &str,
    authorization: &str,
    cfg: &AppConfig,
    allow_device_tools: bool,
    mut payload: Value,
) {
    let mut usage_acc = UsageAcc::default();
    let mut tool_log: Vec<Value> = Vec::new();

    for round in 0..MAX_TOOL_ROUNDS {
        if cancelled.load(Ordering::SeqCst) {
            return;
        }
        if round + 1 == MAX_TOOL_ROUNDS {
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
                let _ = sink.send_event(&json!({"error": {"message": msg}}).to_string());
                return;
            }
        };

        let code = upstream.status().as_u16();
        if !(200..300).contains(&code) {
            let _ = sink.send_event(&json!({"error": {"message": upstream_error_msg(code)}}).to_string());
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
            let _ = sink.send_event(&json!({"error": {"message": "读取上游响应失败"}}).to_string());
            return;
        }

        let body: Value = match serde_json::from_slice(&raw) {
            Ok(v) => v,
            Err(_) => {
                let _ = sink.send_event(&json!({"error": {"message": "上游返回格式错误"}}).to_string());
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
                let _ = sink.send_event(&json!({"error": {"message": "上游未返回有效 choice"}}).to_string());
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

        if finish_reason == "tool_calls" && !tool_calls.is_empty() {
            if !sink.send_event(
                &json!({
                    "choices": [{"delta": {}}],
                    "local_tool_event": {
                        "type": "round_started",
                        "round": round + 1,
                        "max_rounds": MAX_TOOL_ROUNDS,
                    }
                })
                .to_string(),
            ) {
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
                arr.push(assistant_msg);
            }

            for tc in &tool_calls {
                if cancelled.load(Ordering::SeqCst) {
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

                let result = tools::execute_tool(fn_name, fn_args, cfg, allow_device_tools);
                tool_log.push(json!({
                    "id": tc_id,
                    "name": fn_name,
                    "arguments": fn_args,
                    "result": result
                }));

                if !sink.send_event(
                    &json!({
                        "choices": [{"delta": {}}],
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

                if let Some(arr) = payload.get_mut("messages").and_then(Value::as_array_mut) {
                    arr.push(json!({
                        "role": "tool",
                        "tool_call_id": tc_id,
                        "content": result
                    }));
                }
            }
            continue;
        }

        let content = msg.get("content").and_then(Value::as_str).unwrap_or("");
        let reasoning = msg
            .get("reasoning_content")
            .and_then(Value::as_str)
            .unwrap_or("");
        let mut delta = json!({ "content": content });
        if !reasoning.is_empty() {
            delta["reasoning_content"] = json!(reasoning);
        }
        if !tool_log.is_empty() {
            delta["local_tool_calls"] = json!(tool_log);
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
        &json!({"error":{"message": format!("本地工具调用轮次超过上限（{MAX_TOOL_ROUNDS} 轮）")}})
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
    if req.path != "/api/chat" && req.path != "/api/models" {
        return reply_error(&mut stream, 404, "not_found", "没有这个接口");
    }
    if !req.header("Content-Type").starts_with("application/json") {
        return reply_error(&mut stream, 415, "content_type", "需要 JSON 请求");
    }
    let authorization = match auth_header(&req) {
        Ok(v) => v,
        Err(m) => return reply_error(&mut stream, 401, "key_required", m),
    };

    if req.path == "/api/models" {
        let mut upstream = match agent
            .get(&format!("{base}/models"))
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
            base,
            &authorization,
            cfg,
            built.allow_device_tools,
            built.body,
        );
        return;
    }

    let upstream = match agent
        .post(&format!("{base}/chat/completions"))
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
        return reply_error(&mut stream, code, "upstream_error", upstream_error_msg(code));
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

// Standard JNI 1.1+ JNINativeInterface_ table indices:
const JNI_DELETE_LOCAL_REF: usize = 23;
const JNI_GET_OBJECT_CLASS: usize = 31;
const JNI_GET_METHOD_ID: usize = 33;
const JNI_CALL_BOOLEAN_METHOD: usize = 37;
const JNI_NEW_STRING_UTF: usize = 167;
const JNI_GET_STRING_UTF_CHARS: usize = 169;
const JNI_RELEASE_STRING_UTF_CHARS: usize = 170;

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
        return String::new();
    }
    let out = CStr::from_ptr(ptr).to_string_lossy().into_owned();
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
    // Sanitize interior NULs if any
    let clean: String = text.chars().filter(|&c| c != '\0').collect();
    match CString::new(clean) {
        Ok(cstr) => new_str(env, cstr.as_ptr()),
        Err(_) => std::ptr::null_mut(),
    }
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
            let call_bool: unsafe extern "C" fn(JniEnv, JObject, JMethodId, JString) -> u8 =
                std::mem::transmute(*table.add(JNI_CALL_BOOLEAN_METHOD));
            let del_ref: unsafe extern "C" fn(JniEnv, JObject) =
                std::mem::transmute(*table.add(JNI_DELETE_LOCAL_REF));
            let jdata = jni_new_string(self.env, data);
            if jdata.is_null() {
                return false;
            }
            let keep_going = call_bool(self.env, self.callback_obj, self.method_id, jdata);
            del_ref(self.env, jdata);
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

unsafe fn jni_fetch_models_impl(env: JniEnv, api_key_jstr: JString) -> JString {
    let api_key = jni_get_string(env, api_key_jstr);
    let resp = fetch_models_json_str("https://api.deepseek.com", &api_key);
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
    let method_name = CString::new("onEvent").unwrap();
    let method_sig = CString::new("(Ljava/lang/String;)Z").unwrap();
    let method_id = get_mid(env, cls, method_name.as_ptr(), method_sig.as_ptr());
    del_ref(env, cls);

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

fn jni_cancel_impl(request_id: i64) {
    if let Ok(map) = cancel_registry().lock() {
        if let Some(flag) = map.get(&request_id) {
            flag.store(true, Ordering::SeqCst);
        }
    }
}

// Export JNI symbols for `com.uronic0x.fluorine.bridge.FluorineBridge`

#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeGetMeta(
    env: JniEnv,
    _thiz: JObject,
    workspace_jstr: JString,
    allow_storage: u8,
) -> JString {
    jni_get_meta_impl(env, workspace_jstr, allow_storage)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeFetchModels(
    env: JniEnv,
    _thiz: JObject,
    api_key_jstr: JString,
) -> JString {
    jni_fetch_models_impl(env, api_key_jstr)
}

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

#[no_mangle]
pub unsafe extern "C" fn Java_com_uronic0x_fluorine_bridge_FluorineBridge_nativeCancelStream(
    _env: JniEnv,
    _thiz: JObject,
    request_id: i64,
) {
    jni_cancel_impl(request_id);
}
