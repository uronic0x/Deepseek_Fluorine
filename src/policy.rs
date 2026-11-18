use serde_json::{json, Value};
use std::collections::HashSet;

use crate::tools;

pub const KNOWN_MODELS: [&str; 2] = ["deepseek-flash", "deepseek-v4-pro"];
pub const EFFORT_LEVELS: [&str; 5] = ["none", "low", "medium", "high", "max"];
pub const STAGES: [&str; 6] = ["chat", "work", "plan", "research", "execute", "review"];

pub const DEFAULT_CHAT_SYSTEM_PROMPT: &str = "你是 Fluorine（DeepSeek 本地工作台），严谨、直接、重事实的中文技术与研究助手。优先给出可核验结论、关键依据、清晰推导与边界条件。数学公式请使用标准 LaTeX 语法（行内 $...$，独立公式块 $$...$$）。若证据不足或未联网核实，请明确说明不确定性，绝不编造来源、命令输出或实验数据。";

pub const DEFAULT_WORK_SYSTEM_PROMPT: &str = "你是 Fluorine（DeepSeek 本地工作台），具备自主规划、取证与本地执行能力的中文技术 Agent。没有固定死板的工作流步骤限制：请根据用户的具体目标，自行判断是否需要拆解计划、是否需要调用联网搜索、本地确定性计算工具（calc/stats/unit_convert/date_calc/text_metrics/json_query/csv_summary/text_diff/encode_hash）或 Android/Termux 工作区工具（device_probe/fs_list/fs_read/fs_search/git_inspect/fs_write/fs_edit/termux_device/android_ui/shell_exec）。简单问题直接回答或一步执行；复杂任务自主多轮调用工具验证后再汇总输出。数学公式请使用标准 LaTeX（$...$ 或 $$...$$）。生成或修改文件请使用 fs_write / fs_edit 写入工作区，方便用户直接导出产物。";

const DETERMINISTIC_TOOLS: [&str; 9] = [
    "calc",
    "stats",
    "unit_convert",
    "date_calc",
    "text_metrics",
    "json_query",
    "csv_summary",
    "text_diff",
    "encode_hash",
];
const WORKSPACE_READ_TOOLS: [&str; 4] = ["device_probe", "fs_list", "fs_read", "fs_search"];
const WORKSPACE_WRITE_TOOLS: [&str; 2] = ["fs_write", "fs_edit"];
const GIT_TOOLS: [&str; 1] = ["git_inspect"];
const SHELL_TOOLS: [&str; 1] = ["shell_exec"];
const DEVICE_CONTROL_TOOLS: [&str; 2] = ["termux_device", "android_ui"];

#[derive(Clone, Debug)]
pub struct ChatInput {
    pub model: String,
    pub stage: String,
    pub web: bool,
    pub unlock_web: bool,
    pub use_thinking: bool,
    pub thinking_mode: String,
    pub local_tools: bool,
    pub device_tools: bool,
    pub enabled_tools: HashSet<String>,
    pub allow_web: bool,
    pub effort: String,
    pub max_tokens: u64,
    pub max_rounds: usize,
    pub temperature: Option<f64>,
    pub base_url: Option<String>,
    pub system_prompt: Option<String>,
    pub messages: Vec<Value>,
}

#[derive(Clone, Debug)]
pub struct BuiltPayload {
    pub body: Value,
    pub use_local_tools: bool,
    pub allow_device_tools: bool,
    pub enabled_tools: HashSet<String>,
    pub allow_web: bool,
    pub max_rounds: usize,
    pub base_url: Option<String>,
}

pub fn default_system_prompt(stage: &str, use_local_tools: bool) -> &'static str {
    if use_local_tools || matches!(stage, "work" | "execute") {
        DEFAULT_WORK_SYSTEM_PROMPT
    } else {
        DEFAULT_CHAT_SYSTEM_PROMPT
    }
}

pub fn sanitize_max_tokens(v: Option<u64>) -> u64 {
    v.unwrap_or(16384).clamp(256, 393_216)
}

pub fn sanitize_base_url(raw: Option<&str>) -> Option<String> {
    let url = raw?.trim().trim_end_matches('/');
    if url.is_empty() {
        return None;
    }
    if url.starts_with("https://")
        || url.starts_with("http://127.0.0.1:")
        || url.starts_with("http://localhost:")
    {
        Some(url.to_owned())
    } else {
        None
    }
}

fn resolve_enabled_tools(req: &Value, local_tools: bool, device_tools: bool) -> HashSet<String> {
    if !local_tools {
        return HashSet::new();
    }
    if let Some(arr) = req.get("enabled_tools").and_then(Value::as_array) {
        return arr
            .iter()
            .filter_map(Value::as_str)
            .map(|s| tools::canonical_tool_name(s.trim()).to_owned())
            .filter(|s| !s.is_empty())
            .collect();
    }
    if let Some(cfg) = req.get("tool_config").and_then(Value::as_object) {
        let mut out = HashSet::new();
        let det = cfg.get("deterministic").and_then(Value::as_bool).unwrap_or(true);
        let ws_r = cfg.get("workspace_read").and_then(Value::as_bool).unwrap_or(true);
        let ws_w = cfg.get("workspace_write").and_then(Value::as_bool).unwrap_or(true);
        let git = cfg.get("git").and_then(Value::as_bool).unwrap_or(true);
        let shell = cfg.get("shell_exec").and_then(Value::as_bool).unwrap_or(false);
        let dev = cfg.get("device_control").and_then(Value::as_bool).unwrap_or(false);

        if det {
            out.extend(DETERMINISTIC_TOOLS.iter().map(|s| (*s).to_owned()));
        }
        if ws_r {
            out.extend(WORKSPACE_READ_TOOLS.iter().map(|s| (*s).to_owned()));
        }
        if ws_w {
            out.extend(WORKSPACE_WRITE_TOOLS.iter().map(|s| (*s).to_owned()));
        }
        if git {
            out.extend(GIT_TOOLS.iter().map(|s| (*s).to_owned()));
        }
        if shell {
            out.extend(SHELL_TOOLS.iter().map(|s| (*s).to_owned()));
        }
        if dev {
            out.extend(DEVICE_CONTROL_TOOLS.iter().map(|s| (*s).to_owned()));
        }
        if let Some(disabled) = cfg.get("disabled_tools").and_then(Value::as_array) {
            for item in disabled {
                if let Some(name) = item.as_str() {
                    let canon = tools::canonical_tool_name(name.trim());
                    out.remove(canon);
                    out.remove(name.trim());
                }
            }
        }
        return out;
    }

    let mut out = HashSet::new();
    out.extend(DETERMINISTIC_TOOLS.iter().map(|s| (*s).to_owned()));
    out.extend(WORKSPACE_READ_TOOLS.iter().map(|s| (*s).to_owned()));
    out.extend(GIT_TOOLS.iter().map(|s| (*s).to_owned()));
    if device_tools {
        out.extend(WORKSPACE_WRITE_TOOLS.iter().map(|s| (*s).to_owned()));
        out.extend(SHELL_TOOLS.iter().map(|s| (*s).to_owned()));
        out.extend(DEVICE_CONTROL_TOOLS.iter().map(|s| (*s).to_owned()));
    }
    out
}

pub fn parse_input(raw: &[u8]) -> Result<ChatInput, String> {
    let req: Value =
        serde_json::from_slice(raw).map_err(|e| format!("invalid JSON request: {e}"))?;

    let model = req
        .get("model")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("deepseek-flash")
        .to_owned();

    let raw_stage = req.get("stage").and_then(Value::as_str).unwrap_or("chat");
    let stage = if STAGES.contains(&raw_stage) {
        raw_stage.to_owned()
    } else {
        "chat".to_owned()
    };

    let web = req
        .get("use_web")
        .or_else(|| req.get("web"))
        .and_then(Value::as_bool)
        .unwrap_or(false);

    let unlock_web = req
        .get("unlock_web")
        .and_then(Value::as_bool)
        .unwrap_or(false);

    let use_thinking = req
        .get("use_thinking")
        .and_then(Value::as_bool)
        .unwrap_or(true);

    let thinking_mode = req
        .get("thinking_mode")
        .and_then(Value::as_str)
        .unwrap_or("auto")
        .to_owned();

    let local_tools = req
        .get("use_local_tools")
        .or_else(|| req.get("local_tools"))
        .and_then(Value::as_bool)
        .unwrap_or(matches!(stage.as_str(), "work" | "execute"));

    let device_tools = req
        .get("allow_device_tools")
        .or_else(|| req.get("device_tools"))
        .and_then(Value::as_bool)
        .unwrap_or(false);

    let enabled_tools = resolve_enabled_tools(&req, local_tools, device_tools);
    let allow_device = device_tools
        || enabled_tools.iter().any(|t| {
            matches!(
                t.as_str(),
                "fs_write" | "fs_edit" | "shell_exec" | "termux_device" | "android_ui"
            )
        });

    let allow_web = web
        && (unlock_web || model == "deepseek-flash" || !KNOWN_MODELS.contains(&model.as_str()));

    let effort = req
        .get("effort")
        .and_then(Value::as_str)
        .unwrap_or("medium")
        .to_owned();
    if !EFFORT_LEVELS.contains(&effort.as_str()) {
        return Err(format!("Unsupported reasoning_effort: {effort}"));
    }

    let max_tokens = sanitize_max_tokens(req.get("max_tokens").and_then(Value::as_u64));
    let max_rounds = req
        .get("max_rounds")
        .and_then(Value::as_u64)
        .unwrap_or(crate::DEFAULT_MAX_TOOL_ROUNDS as u64)
        .clamp(1, crate::MAX_TOOL_ROUNDS as u64) as usize;

    let temperature = if req
        .get("enable_temperature")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        req.get("temperature")
            .and_then(Value::as_f64)
            .map(|t| t.clamp(0.0, 2.0))
    } else {
        None
    };

    let base_url = sanitize_base_url(req.get("base_url").and_then(Value::as_str));

    let system_prompt = req
        .get("system_prompt")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(ToOwned::to_owned);

    let messages = req
        .get("messages")
        .and_then(Value::as_array)
        .cloned()
        .ok_or_else(|| "messages must be a non-empty array".to_string())?;

    Ok(ChatInput {
        model,
        stage,
        web,
        unlock_web,
        use_thinking,
        thinking_mode,
        local_tools,
        device_tools: allow_device,
        enabled_tools,
        allow_web,
        effort,
        max_tokens,
        max_rounds,
        temperature,
        base_url,
        system_prompt,
        messages,
    })
}

fn client_web_search_decl() -> Value {
    json!({
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "Search the public web via DeepSeek Search API for up-to-date facts, docs, and URLs.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": { "type": "string", "description": "Search query" },
                    "max_results": { "type": "integer", "description": "1-10 results (default 5)" }
                },
                "required": ["query"]
            }
        }
    })
}

fn client_web_fetch_decl() -> Value {
    json!({
        "type": "function",
        "function": {
            "name": "web_fetch",
            "description": "Fetch readable content of an http/https webpage via DeepSeek Fetch API.",
            "parameters": {
                "type": "object",
                "properties": {
                    "url": { "type": "string", "description": "Webpage URL to read" }
                },
                "required": ["url"]
            }
        }
    })
}

pub fn build_payload(input: ChatInput) -> Result<BuiltPayload, String> {
    let sys = input
        .system_prompt
        .as_deref()
        .unwrap_or_else(|| default_system_prompt(&input.stage, input.local_tools));

    let mut local_fn_defs: Vec<Value> = Vec::new();
    if input.local_tools && !input.enabled_tools.is_empty() {
        if let Value::Array(defs) = tools::tool_definitions_for(&input.enabled_tools) {
            local_fn_defs.extend(defs);
        }
    }
    let has_local_functions = !local_fn_defs.is_empty();

    let mut tool_list: Vec<Value> = Vec::new();
    if input.allow_web {
        if has_local_functions {
            // Never mix server built-in `deepseek_web_search` with custom `function` tools.
            tool_list.push(client_web_search_decl());
            tool_list.push(client_web_fetch_decl());
        } else {
            tool_list.push(json!({ "type": "deepseek_web_search" }));
            tool_list.push(json!({ "type": "deepseek_read_url" }));
        }
    }
    tool_list.extend(local_fn_defs);
    let has_tools = !tool_list.is_empty();

    let mut messages = vec![json!({
        "role": "system",
        "content": sys
    })];

    for m in &input.messages {
        let role = m.get("role").and_then(Value::as_str).unwrap_or("");
        if role == "latest_reminder" {
            let rem = m.get("content").and_then(Value::as_str).unwrap_or("").trim();
            if !rem.is_empty() {
                messages.push(json!({
                    "role": "latest_reminder",
                    "content": rem
                }));
            }
            continue;
        }
        if role == "user" {
            if let Some(arr) = m.get("content").and_then(Value::as_array) {
                if !arr.is_empty() {
                    messages.push(json!({
                        "role": "user",
                        "content": arr
                    }));
                }
                continue;
            }
            let content = m.get("content").and_then(Value::as_str).unwrap_or("");
            if content.trim().is_empty() {
                continue;
            }
            messages.push(json!({
                "role": "user",
                "content": content
            }));
        } else if role == "assistant" {
            let content = m.get("content").and_then(Value::as_str).unwrap_or("");
            if input.local_tools {
                if let Some(tc) = m.get("tool_calls").and_then(Value::as_array) {
                    if !tc.is_empty() {
                        let mut msg_obj = json!({
                            "role": "assistant",
                            "content": content,
                            "tool_calls": tc
                        });
                        if has_tools {
                            if let Some(rc) = m.get("reasoning_content").and_then(Value::as_str) {
                                msg_obj["reasoning_content"] = json!(rc);
                            }
                        }
                        messages.push(msg_obj);
                        continue;
                    }
                }
            }
            let server_tc = if !has_local_functions {
                m.get("server_tool_calls").and_then(Value::as_array)
            } else {
                None
            };
            let has_stc = server_tc.is_some_and(|a| !a.is_empty());
            if !content.trim().is_empty() || has_stc {
                let mut msg_obj = json!({
                    "role": "assistant",
                    "content": content
                });
                if has_tools {
                    if let Some(rc) = m.get("reasoning_content").and_then(Value::as_str) {
                        msg_obj["reasoning_content"] = json!(rc);
                    }
                }
                if let Some(stc) = server_tc {
                    if !stc.is_empty() {
                        msg_obj["server_tool_calls"] = Value::Array(stc.clone());
                    }
                }
                messages.push(msg_obj);
            }
        } else if role == "tool" && input.local_tools {
            if let Some(id) = m
                .get("tool_call_id")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                let content = m.get("content").and_then(Value::as_str).unwrap_or("");
                messages.push(json!({
                    "role": "tool",
                    "tool_call_id": id,
                    "content": content
                }));
            }
        }
    }

    if messages.len() <= 1 {
        return Err("messages cannot be empty".to_string());
    }

    let effective_thinking = match input.thinking_mode.as_str() {
        "enabled" => true,
        "disabled" => false,
        _ => input.use_thinking,
    };
    let effective_effort = if !effective_thinking && input.thinking_mode == "disabled" {
        "none"
    } else {
        match input.effort.as_str() {
            "medium" => "high",
            other => other,
        }
    };

    let use_stream = !input.local_tools;
    let mut payload = json!({
        "model": input.model,
        "messages": messages,
        "thinking": { "type": if effective_thinking { "enabled" } else { "disabled" } },
        "reasoning_effort": effective_effort,
        "max_tokens": input.max_tokens,
        "stream": use_stream
    });
    if use_stream {
        payload["stream_options"] = json!({ "include_usage": true });
    }
    if let Some(temp) = input.temperature {
        if !effective_thinking {
            payload["temperature"] = json!(temp);
        }
    }
    if has_tools {
        payload["tools"] = Value::Array(tool_list);
    }

    Ok(BuiltPayload {
        body: payload,
        use_local_tools: input.local_tools,
        allow_device_tools: input.device_tools,
        enabled_tools: input.enabled_tools,
        allow_web: input.allow_web,
        max_rounds: input.max_rounds,
        base_url: input.base_url,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tool_round_budget_accepts_expanded_range() {
        let base = json!({"messages": [{"role": "user", "content": "hello"}]});
        assert_eq!(
            parse_input(&serde_json::to_vec(&base).unwrap())
                .unwrap()
                .max_rounds,
            50
        );
        for (requested, expected) in [(1, 1), (120, 120), (500, 500), (501, 500)] {
            let mut request = base.clone();
            request["max_rounds"] = json!(requested);
            assert_eq!(
                parse_input(&serde_json::to_vec(&request).unwrap())
                    .unwrap()
                    .max_rounds,
                expected
            );
        }
    }

    #[test]
    fn pro_omits_web_search_while_flash_includes_it_by_default() {
        let pro_raw = serde_json::to_vec(&json!({
            "model": "deepseek-v4-pro",
            "stage": "chat",
            "use_web": true,
            "use_thinking": true,
            "effort": "high",
            "max_tokens": 8192,
            "messages": [{"role": "user", "content": "hello", "reasoning_content": "strip me"}]
        }))
        .unwrap();
        let pro_built = build_payload(parse_input(&pro_raw).unwrap()).unwrap();
        assert!(pro_built.body.get("tools").is_none());
        assert_eq!(pro_built.body["thinking"]["type"], "enabled");
        assert_eq!(pro_built.body["reasoning_effort"], "high");
        assert_eq!(pro_built.body["stream"], true);
        assert!(pro_built.body["messages"][1].get("reasoning_content").is_none());

        let flash_raw = serde_json::to_vec(&json!({
            "model": "deepseek-flash",
            "stage": "chat",
            "web": true,
            "effort": "medium",
            "messages": [{"role": "user", "content": "latest news"}]
        }))
        .unwrap();
        let flash_built = build_payload(parse_input(&flash_raw).unwrap()).unwrap();
        assert_eq!(flash_built.body["reasoning_effort"], "high");
        assert_eq!(flash_built.body["tools"][0]["type"], "deepseek_web_search");
        assert_eq!(flash_built.body["tools"][1]["type"], "deepseek_read_url");
    }

    #[test]
    fn work_stage_enables_local_tools_avoids_web_mix_and_retains_reasoning() {
        let work_raw = serde_json::to_vec(&json!({
            "model": "deepseek-flash",
            "stage": "work",
            "web": true,
            "use_thinking": true,
            "local_tools": true,
            "device_tools": true,
            "effort": "medium",
            "messages": [
                {"role": "user", "content": "calc 1+1"},
                {"role": "assistant", "content": "", "reasoning_content": "should keep when tools present", "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "calc", "arguments": "{\"expression\":\"1+1\"}"}}]},
                {"role": "tool", "tool_call_id": "c1", "content": "{\"ok\":true,\"value\":2}"},
                {"role": "assistant", "content": "Result is 2", "reasoning_content": "turn reasoning"},
                {"role": "user", "content": "now multiply by 3"}
            ]
        }))
        .unwrap();
        let built = build_payload(parse_input(&work_raw).unwrap()).unwrap();
        assert!(built.use_local_tools);
        assert!(built.allow_device_tools);
        assert_eq!(built.body["stream"], false);
        let tools_arr = built.body["tools"].as_array().unwrap();
        assert!(tools_arr.len() >= 21);
        assert!(tools_arr.iter().all(|t| t["type"] == "function"));
        let msgs = built.body["messages"].as_array().unwrap();
        assert_eq!(msgs.len(), 6);
        assert!(msgs[2].get("tool_calls").is_some());
        assert_eq!(msgs[2]["reasoning_content"], "should keep when tools present");
        assert_eq!(msgs[4]["reasoning_content"], "turn reasoning");
        assert_eq!(msgs[3]["role"], "tool");
    }
}
