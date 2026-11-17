use serde_json::{json, Value};

use crate::tools;

pub const KNOWN_MODELS: [&str; 2] = ["deepseek-flash", "deepseek-v4-pro"];
pub const EFFORT_LEVELS: [&str; 5] = ["none", "low", "medium", "high", "max"];
pub const STAGES: [&str; 6] = ["chat", "work", "plan", "research", "execute", "review"];

pub const DEFAULT_CHAT_SYSTEM_PROMPT: &str = "你是 Fluorine（DeepSeek 本地工作台），严谨、直接、重事实的中文技术与研究助手。优先给出可核验结论、关键依据、清晰推导与边界条件。数学公式请使用标准 LaTeX 语法（行内 $...$，独立公式块 $$...$$）。若证据不足或未联网核实，请明确说明不确定性，绝不编造来源、命令输出或实验数据。";

pub const DEFAULT_WORK_SYSTEM_PROMPT: &str = "你是 Fluorine（DeepSeek 本地工作台），具备自主规划、取证与本地执行能力的中文技术 Agent。没有固定死板的工作流步骤限制：请根据用户的具体目标，自行判断是否需要拆解计划、是否需要调用联网搜索、本地确定性计算工具（calc/stats/unit_convert/date_calc/text_metrics/json_query/csv_summary/text_diff/encode_hash）或 Android/Termux 工作区工具（device_probe/fs_list/fs_read/fs_search/git_inspect/fs_write/fs_edit/termux_device/android_ui/shell_exec）。简单问题直接回答或一步执行；复杂任务自主多轮调用工具验证后再汇总输出。数学公式请使用标准 LaTeX（$...$ 或 $$...$$）。生成或修改文件请使用 fs_write / fs_edit 写入工作区，方便用户直接导出产物。";

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
    pub effort: String,
    pub max_tokens: u64,
    pub system_prompt: Option<String>,
    pub messages: Vec<Value>,
}

#[derive(Clone, Debug)]
pub struct BuiltPayload {
    pub body: Value,
    pub use_local_tools: bool,
    pub allow_device_tools: bool,
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

    let effort = req
        .get("effort")
        .and_then(Value::as_str)
        .unwrap_or("medium")
        .to_owned();
    if !EFFORT_LEVELS.contains(&effort.as_str()) {
        return Err(format!("Unsupported reasoning.effort: {effort}"));
    }

    let max_tokens = sanitize_max_tokens(req.get("max_tokens").and_then(Value::as_u64));

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
        device_tools,
        effort,
        max_tokens,
        system_prompt,
        messages,
    })
}

pub fn build_payload(input: ChatInput) -> Result<BuiltPayload, String> {
    let sys = input
        .system_prompt
        .as_deref()
        .unwrap_or_else(|| default_system_prompt(&input.stage, input.local_tools));

    let mut messages = vec![json!({
        "role": "system",
        "content": sys
    })];

    for m in &input.messages {
        let role = m.get("role").and_then(Value::as_str).unwrap_or("");
        let content = m.get("content").and_then(Value::as_str).unwrap_or("");
        if role == "latest_reminder" {
            let rem = content.trim();
            if !rem.is_empty() {
                messages.push(json!({
                    "role": "latest_reminder",
                    "content": rem
                }));
            }
            continue;
        }
        if role == "user" {
            if content.trim().is_empty() {
                continue;
            }
            messages.push(json!({
                "role": "user",
                "content": content
            }));
        } else if role == "assistant" {
            if input.local_tools {
                if let Some(tc) = m.get("tool_calls").and_then(Value::as_array) {
                    if !tc.is_empty() {
                        // Preserve tool_calls history while stripping historical reasoning_content for Prefix Cache hit
                        messages.push(json!({
                            "role": "assistant",
                            "content": content,
                            "tool_calls": tc
                        }));
                        continue;
                    }
                }
            }
            if !content.trim().is_empty() {
                messages.push(json!({
                    "role": "assistant",
                    "content": content
                }));
            }
        } else if role == "tool" && input.local_tools {
            if let Some(id) = m
                .get("tool_call_id")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
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
        input.effort.as_str()
    };

    let use_stream = !input.local_tools;
    let mut payload = json!({
        "model": input.model,
        "messages": messages,
        "thinking": { "type": if effective_thinking { "enabled" } else { "disabled" } },
        "reasoning": { "effort": effective_effort },
        "max_tokens": input.max_tokens,
        "stream": use_stream
    });
    if use_stream {
        payload["stream_options"] = json!({ "include_usage": true });
    }

    let mut tool_list: Vec<Value> = Vec::new();
    let allow_web = input.unlock_web
        || input.model == "deepseek-flash"
        || !KNOWN_MODELS.contains(&input.model.as_str());
    if input.web && allow_web {
        // Official Chat Completions web tools: only `deepseek_web_search` /
        // `deepseek_read_url` are recognized; `web_search` is rejected with 422.
        tool_list.push(json!({ "type": "deepseek_web_search" }));
    }
    if input.local_tools {
        if let Value::Array(defs) = tools::tool_definitions(input.device_tools) {
            tool_list.extend(defs);
        }
    }
    if !tool_list.is_empty() {
        payload["tools"] = Value::Array(tool_list);
    }

    Ok(BuiltPayload {
        body: payload,
        use_local_tools: input.local_tools,
        allow_device_tools: input.device_tools,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(pro_built.body["reasoning"]["effort"], "high");
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
        assert_eq!(flash_built.body["tools"][0]["type"], "deepseek_web_search");
    }

    #[test]
    fn work_stage_enables_local_tools_and_preserves_tool_history_without_reasoning() {
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
                {"role": "assistant", "content": "", "reasoning_content": "should strip", "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "calc", "arguments": "{\"expression\":\"1+1\"}"}}]},
                {"role": "tool", "tool_call_id": "c1", "content": "{\"ok\":true,\"value\":2}"},
                {"role": "assistant", "content": "Result is 2"},
                {"role": "user", "content": "now multiply by 3"}
            ]
        }))
        .unwrap();
        let built = build_payload(parse_input(&work_raw).unwrap()).unwrap();
        assert!(built.use_local_tools);
        assert!(built.allow_device_tools);
        assert_eq!(built.body["stream"], false);
        let tools_arr = built.body["tools"].as_array().unwrap();
        assert!(tools_arr.len() >= 19);
        let msgs = built.body["messages"].as_array().unwrap();
        assert_eq!(msgs.len(), 6);
        assert!(msgs[2].get("tool_calls").is_some());
        assert!(msgs[2].get("reasoning_content").is_none());
        assert_eq!(msgs[3]["role"], "tool");
    }
}
