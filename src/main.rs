mod config;
mod harness;
mod http;
mod policy;
mod tools;

use config::AppConfig;
use http::{pipe_sse, read_request, start_sse, write_response, write_sse_data, HttpRequest, MAX_BODY};
use policy::{build_payload, parse_input};
use serde_json::{json, Value};
use std::env;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
use std::thread;
use std::time::Duration;

const MAX_ACTIVE: usize = 4;
const MAX_TOOL_ROUNDS: usize = 20;

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
    let value = req.header("Authorization");
    let key = value
        .strip_prefix("Bearer ")
        .ok_or("请在设置中填入 API Key")?;
    if key.is_empty() || key.len() > 512 || !key.bytes().all(|b| b.is_ascii_graphic()) {
        return Err("API Key 格式不正确");
    }
    Ok(value.to_owned())
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

fn upstream_error_msg(code: u16) -> &'static str {
    match code {
        401 => "API Key 无效",
        402 => "账户余额不足",
        429 => "API 限流，请稍后手动重试",
        400 => "上游拒绝参数，请核对模型、工具与历史格式",
        _ => "上游服务异常；本轮未自动重试",
    }
}

fn run_local_tool_loop(
    stream: &mut TcpStream,
    agent: &ureq::Agent,
    base: &str,
    authorization: &str,
    cfg: &AppConfig,
    allow_device_tools: bool,
    mut payload: Value,
) {
    let mut usage_acc = UsageAcc::default();
    let mut tool_log: Vec<Value> = Vec::new();
    let mut sse_started = false;

    for round in 0..MAX_TOOL_ROUNDS {
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
                if !sse_started {
                    reply_error(stream, 502, "transport", "连接上游失败或超时；本轮未自动重试");
                } else {
                    let _ = write_sse_data(
                        stream,
                        &json!({"error":{"message":"工具执行中途连接上游失败"}}).to_string(),
                    );
                }
                return;
            }
        };

        let code = upstream.status().as_u16();
        if !(200..300).contains(&code) {
            if !sse_started {
                reply_error(stream, code, "upstream_error", upstream_error_msg(code));
            } else {
                let _ = write_sse_data(
                    stream,
                    &json!({"error":{"message": upstream_error_msg(code)}}).to_string(),
                );
            }
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
            if !sse_started {
                reply_error(stream, 502, "upstream_read", "读取上游响应失败");
            } else {
                let _ = write_sse_data(
                    stream,
                    &json!({"error":{"message":"读取上游响应失败"}}).to_string(),
                );
            }
            return;
        }

        let body: Value = match serde_json::from_slice(&raw) {
            Ok(v) => v,
            Err(_) => {
                if !sse_started {
                    reply_error(stream, 502, "invalid_upstream_json", "上游返回格式错误");
                } else {
                    let _ = write_sse_data(
                        stream,
                        &json!({"error":{"message":"上游返回格式错误"}}).to_string(),
                    );
                }
                return;
            }
        };

        if let Some(u) = body.get("usage") {
            add_usage(&mut usage_acc, u);
        }

        // Per DeepSeek API docs, non-streaming HTTP 200 can carry {"error": {...}, "usage": {...}}
        if let Some(err_obj) = body.get("error") {
            let err_msg = err_obj
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("上游生成失败；已发生的用量仍可能计费");
            if !sse_started {
                reply_error(stream, 502, "upstream_generation_error", err_msg);
            } else {
                let _ = write_sse_data(
                    stream,
                    &json!({
                        "error": {"message": err_msg},
                        "usage": usage_acc.to_json()
                    })
                    .to_string(),
                );
            }
            return;
        }

        if !sse_started {
            if start_sse(stream).is_err() {
                return;
            }
            sse_started = true;
        }

        let choice = match body.get("choices").and_then(|c| c.get(0)) {
            Some(c) => c,
            None => {
                let _ = write_sse_data(
                    stream,
                    &json!({"error":{"message":"上游未返回有效 choice"}}).to_string(),
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

        if finish_reason == "tool_calls" && !tool_calls.is_empty() {
            let _ = write_sse_data(
                stream,
                &json!({
                    "choices": [{"delta": {}}],
                    "local_tool_event": {
                        "type": "round_started",
                        "round": round + 1,
                        "max_rounds": MAX_TOOL_ROUNDS,
                    }
                })
                .to_string(),
            );

            // Preserve reasoning_content + tool_calls on the assistant message per DeepSeek docs.
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
                let tc_id = tc.get("id").and_then(Value::as_str).unwrap_or("");
                let fn_name = tc
                    .pointer("/function/name")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let fn_args = tc
                    .pointer("/function/arguments")
                    .and_then(Value::as_str)
                    .unwrap_or("{}");

                let _ = write_sse_data(
                    stream,
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
                );

                let result = tools::execute_tool(fn_name, fn_args, cfg, allow_device_tools);
                tool_log.push(json!({
                    "id": tc_id,
                    "name": fn_name,
                    "arguments": fn_args,
                    "result": result
                }));

                let _ = write_sse_data(
                    stream,
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
                );

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

        // Final answer reached
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
        let _ = write_sse_data(
            stream,
            &json!({
                "choices": [{
                    "delta": delta,
                    "finish_reason": finish_reason
                }]
            })
            .to_string(),
        );
        let _ = write_sse_data(
            stream,
            &json!({
                "choices": [],
                "usage": usage_acc.to_json()
            })
            .to_string(),
        );
        let _ = stream.write_all(b"data: [DONE]\n\n");
        let _ = stream.flush();
        return;
    }

    if sse_started {
        let _ = write_sse_data(
            stream,
            &json!({"error":{"message": format!("本地工具调用轮次超过上限（{MAX_TOOL_ROUNDS} 轮）")}})
                .to_string(),
        );
    }
}

fn serve(mut stream: TcpStream, port: u16, agent: ureq::Agent, base: &str, cfg: &AppConfig) {
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
                let runtime = harness::probe_runtime_summary(cfg);
                let body = config::meta_json(cfg, runtime).to_string();
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
        run_local_tool_loop(
            &mut stream,
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

fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let cfg = Arc::new(AppConfig::from_env());
    let port: u16 = env::var("PORT").unwrap_or_else(|_| "8787".into()).parse()?;
    if port == 0 {
        return Err("PORT must be nonzero".into());
    }
    let base = if cfg!(debug_assertions) {
        env::var("DEEPSEEK_TEST_BASE")
            .ok()
            .filter(|v| {
                v.strip_prefix("http://127.0.0.1:")
                    .is_some_and(|p| p.parse::<u16>().is_ok())
            })
            .unwrap_or_else(|| "https://api.deepseek.com".into())
    } else {
        "https://api.deepseek.com".into()
    };
    let config = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(300)))
        .timeout_connect(Some(Duration::from_secs(20)))
        .timeout_recv_response(Some(Duration::from_secs(60)))
        .build();
    let agent: ureq::Agent = config.into();
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
