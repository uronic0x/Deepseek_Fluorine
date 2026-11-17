use crate::config::AppConfig;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const MAX_READ_BYTES: usize = 32 * 1024;
const MAX_WRITE_BYTES: usize = 512 * 1024;
const MAX_CMD_OUTPUT_BYTES: usize = 24 * 1024;
const DEFAULT_CMD_TIMEOUT_SECS: u64 = 15;
const MAX_CMD_TIMEOUT_SECS: u64 = 60;

pub fn is_device_control_tool(name: &str) -> bool {
    matches!(
        name,
        "fs_write" | "fs_edit" | "termux_device" | "android_ui" | "shell_exec"
    )
}

pub fn harness_tool_definitions(include_device_tools: bool) -> Vec<Value> {
    let mut list = vec![
        json!({
            "type": "function",
            "function": {
                "name": "device_probe",
                "description": "Inspect the local device and Android/Termux/Linux runtime environment: OS/arch, Android properties, Termux-API binaries, ADB/Shizuku availability, battery, memory, storage, and workspace path.",
                "parameters": {
                    "type": "object",
                    "properties": {}
                }
            }
        }),
        json!({
            "type": "function",
            "function": {
                "name": "fs_list",
                "description": "List files and directories inside the configured workspace (skips .git, target, node_modules).",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "Relative path inside workspace (default '.')" },
                        "recursive": { "type": "boolean", "description": "Whether to recurse into subdirectories (default false)" },
                        "max_depth": { "type": "integer", "description": "Max directory depth when recursive=true (1-6, default 3)" },
                        "ext": { "type": "string", "description": "Optional file extension filter without leading dot, e.g. 'rs' or 'json'" }
                    }
                }
            }
        }),
        json!({
            "type": "function",
            "function": {
                "name": "fs_read",
                "description": "Read a UTF-8 text file inside the workspace with optional 1-indexed line range.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "File path relative to workspace" },
                        "start_line": { "type": "integer", "description": "1-indexed start line (default 1)" },
                        "end_line": { "type": "integer", "description": "1-indexed inclusive end line (optional)" }
                    },
                    "required": ["path"]
                }
            }
        }),
        json!({
            "type": "function",
            "function": {
                "name": "fs_search",
                "description": "Search text across files inside the workspace (grep-like), returning file, line number, and line snippet.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": { "type": "string", "description": "Substring to search for" },
                        "path": { "type": "string", "description": "Subdirectory or file relative to workspace (default '.')" },
                        "case_sensitive": { "type": "boolean", "description": "Default false" },
                        "max_results": { "type": "integer", "description": "Max matches (1-200, default 50)" }
                    },
                    "required": ["query"]
                }
            }
        }),
        json!({
            "type": "function",
            "function": {
                "name": "git_inspect",
                "description": "Inspect local git repository state in the workspace: status, diff, log, or branches.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "op": { "type": "string", "enum": ["status", "diff", "log", "branches"] },
                        "limit": { "type": "integer", "description": "Commit count for 'log' (1-30, default 10)" },
                        "path": { "type": "string", "description": "Optional relative path filter for diff/log" }
                    },
                    "required": ["op"]
                }
            }
        }),
    ];

    if include_device_tools {
        list.extend([
            json!({
                "type": "function",
                "function": {
                    "name": "fs_write",
                    "description": "Create, overwrite, or append a UTF-8 text file inside the workspace.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "path": { "type": "string", "description": "Target file path relative to workspace" },
                            "content": { "type": "string", "description": "UTF-8 file content" },
                            "append": { "type": "boolean", "description": "Append instead of overwrite (default false)" }
                        },
                        "required": ["path", "content"]
                    }
                }
            }),
            json!({
                "type": "function",
                "function": {
                    "name": "fs_edit",
                    "description": "Surgically replace text in an existing workspace file. Requires unique match unless replace_all=true.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "path": { "type": "string", "description": "Target file path relative to workspace" },
                            "old_text": { "type": "string", "description": "Exact text to find" },
                            "new_text": { "type": "string", "description": "Replacement text" },
                            "replace_all": { "type": "boolean", "description": "Replace all occurrences (default false)" }
                        },
                        "required": ["path", "old_text", "new_text"]
                    }
                }
            }),
            json!({
                "type": "function",
                "function": {
                    "name": "termux_device",
                    "description": "Access Android phone hardware, sensors, clipboard, notifications, TTS, and properties via Termux-API or native Android/Linux sysfs & getprop.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "op": {
                                "type": "string",
                                "enum": [
                                    "battery",
                                    "device_props",
                                    "clipboard_get",
                                    "clipboard_set",
                                    "location",
                                    "wifi_info",
                                    "sensors",
                                    "notification",
                                    "toast",
                                    "vibrate",
                                    "tts_speak",
                                    "open_url"
                                ]
                            },
                            "text": { "type": "string", "description": "Text for clipboard_set, toast, tts_speak, or notification content" },
                            "title": { "type": "string", "description": "Notification title" },
                            "id": { "type": "string", "description": "Notification id" },
                            "url": { "type": "string", "description": "http/https URL for open_url" },
                            "provider": { "type": "string", "enum": ["gps", "network", "passive"], "description": "Location provider" },
                            "sensor": { "type": "string", "description": "Optional sensor name for single reading" },
                            "duration_ms": { "type": "integer", "description": "Vibration duration in ms (50-5000)" }
                        },
                        "required": ["op"]
                    }
                }
            }),
            json!({
                "type": "function",
                "function": {
                    "name": "android_ui",
                    "description": "Self-ADB / Shizuku Android UI perception and control loop: inspect active app, dump semantic UI node tree with tap coordinates, capture screenshot, tap, swipe, input text, keyevent, launch app, read notifications or logcat.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "op": {
                                "type": "string",
                                "enum": [
                                    "status",
                                    "current_app",
                                    "dump_ui",
                                    "screenshot",
                                    "tap",
                                    "swipe",
                                    "input_text",
                                    "keyevent",
                                    "launch_app",
                                    "notifications",
                                    "logcat"
                                ]
                            },
                            "backend": { "type": "string", "enum": ["auto", "adb", "shizuku"] },
                            "x": { "type": "integer" },
                            "y": { "type": "integer" },
                            "x1": { "type": "integer" },
                            "y1": { "type": "integer" },
                            "x2": { "type": "integer" },
                            "y2": { "type": "integer" },
                            "duration_ms": { "type": "integer", "description": "Swipe duration in ms (default 300)" },
                            "text": { "type": "string", "description": "ASCII/text for input_text" },
                            "keycode": { "type": "string", "description": "Android keycode name or number, e.g. HOME, BACK, ENTER" },
                            "package": { "type": "string", "description": "Android package name, e.g. com.android.settings" },
                            "lines": { "type": "integer", "description": "Number of logcat lines (10-200, default 60)" }
                        },
                        "required": ["op"]
                    }
                }
            }),
            json!({
                "type": "function",
                "function": {
                    "name": "shell_exec",
                    "description": "Execute a shell command inside the workspace with bounded timeout and output cap (supports Termux, Python, Node, Git, Cargo, Gradle).",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "command": { "type": "string", "description": "Shell command executed via sh -c inside workspace" },
                            "subdir": { "type": "string", "description": "Optional relative subdirectory inside workspace" },
                            "timeout_secs": { "type": "integer", "description": "Timeout in seconds (1-60, default 15)" }
                        },
                        "required": ["command"]
                    }
                }
            }),
        ]);
    }
    list
}

fn find_in_path(bin: &str) -> Option<PathBuf> {
    let paths = env::var_os("PATH")?;
    for dir in env::split_paths(&paths) {
        let candidate = dir.join(bin);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

fn detect_tools_map() -> BTreeMap<&'static str, bool> {
    let bins = [
        "adb",
        "rish",
        "getprop",
        "am",
        "git",
        "python3",
        "node",
        "cargo",
        "termux-battery-status",
        "termux-clipboard-get",
        "termux-clipboard-set",
        "termux-location",
        "termux-wifi-connectioninfo",
        "termux-sensor",
        "termux-notification",
        "termux-toast",
        "termux-vibrate",
        "termux-tts-speak",
        "termux-open-url",
    ];
    let mut map = BTreeMap::new();
    for bin in bins {
        map.insert(bin, find_in_path(bin).is_some());
    }
    map
}

fn read_sysfs_battery() -> Option<Value> {
    let base = Path::new("/sys/class/power_supply");
    let entries = fs::read_dir(base).ok()?;
    for entry in entries.flatten() {
        let p = entry.path();
        let kind = fs::read_to_string(p.join("type")).unwrap_or_default();
        if kind.trim().eq_ignore_ascii_case("battery")
            || entry.file_name().to_string_lossy().to_ascii_lowercase().contains("bat")
        {
            let cap = fs::read_to_string(p.join("capacity"))
                .ok()
                .and_then(|s| s.trim().parse::<u64>().ok());
            let status = fs::read_to_string(p.join("status"))
                .ok()
                .map(|s| s.trim().to_owned());
            let temp = fs::read_to_string(p.join("temp"))
                .ok()
                .and_then(|s| s.trim().parse::<f64>().ok())
                .map(|t| t / 10.0);
            if cap.is_some() || status.is_some() {
                return Some(json!({
                    "source": "sysfs",
                    "percentage": cap,
                    "status": status,
                    "temperature_c": temp,
                }));
            }
        }
    }
    None
}

fn read_meminfo_summary() -> Option<Value> {
    let text = fs::read_to_string("/proc/meminfo").ok()?;
    let mut total_kb = None;
    let mut avail_kb = None;
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            total_kb = rest
                .split_whitespace()
                .next()
                .and_then(|v| v.parse::<u64>().ok());
        } else if let Some(rest) = line.strip_prefix("MemAvailable:") {
            avail_kb = rest
                .split_whitespace()
                .next()
                .and_then(|v| v.parse::<u64>().ok());
        }
    }
    Some(json!({
        "mem_total_mb": total_kb.map(|k| k / 1024),
        "mem_available_mb": avail_kb.map(|k| k / 1024),
    }))
}

pub fn probe_runtime_summary(cfg: &AppConfig) -> Value {
    let tools = detect_tools_map();
    let termux_version = env::var("TERMUX_VERSION").ok().filter(|s| !s.is_empty());
    let is_android = env::consts::OS == "android"
        || termux_version.is_some()
        || Path::new("/system/build.prop").exists()
        || tools.get("getprop").copied().unwrap_or(false);
    let termux_api_ready = tools
        .get("termux-battery-status")
        .copied()
        .unwrap_or(false);
    let adb_ready = tools.get("adb").copied().unwrap_or(false);
    let shizuku_ready = tools.get("rish").copied().unwrap_or(false);

    json!({
        "os": env::consts::OS,
        "arch": env::consts::ARCH,
        "is_android": is_android,
        "termux_version": termux_version,
        "termux_api_ready": termux_api_ready,
        "adb_ready": adb_ready,
        "shizuku_ready": shizuku_ready,
        "workspace": cfg.workspace_root.display().to_string(),
        "memory": read_meminfo_summary(),
        "battery": read_sysfs_battery(),
        "available_bins": tools,
    })
}

fn truncate_utf8(bytes: &[u8], max_bytes: usize) -> (String, bool) {
    if bytes.len() <= max_bytes {
        return (String::from_utf8_lossy(bytes).into_owned(), false);
    }
    let mut end = max_bytes;
    let mut steps = 0;
    while end > 0 && (bytes[end] & 0xC0) == 0x80 && steps < 4 {
        end -= 1;
        steps += 1;
    }
    let mut s = String::from_utf8_lossy(&bytes[..end]).into_owned();
    s.push_str("\n...[truncated]");
    (s, true)
}

fn run_command_timeout(
    program: &str,
    args: &[&str],
    cwd: &Path,
    timeout_secs: u64,
) -> Result<(i32, bool, String, String, bool), String> {
    let mut child = Command::new(program)
        .args(args)
        .current_dir(cwd)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("failed to spawn {program}: {e}"))?;

    let stdout_pipe = child.stdout.take();
    let stderr_pipe = child.stderr.take();

    let out_handle = thread::spawn(move || {
        let mut buf = Vec::new();
        if let Some(mut r) = stdout_pipe {
            let _ = r
                .by_ref()
                .take((MAX_CMD_OUTPUT_BYTES + 1024) as u64)
                .read_to_end(&mut buf);
        }
        buf
    });
    let err_handle = thread::spawn(move || {
        let mut buf = Vec::new();
        if let Some(mut r) = stderr_pipe {
            let _ = r
                .by_ref()
                .take((MAX_CMD_OUTPUT_BYTES + 1024) as u64)
                .read_to_end(&mut buf);
        }
        buf
    });

    let deadline = Instant::now() + Duration::from_secs(timeout_secs.clamp(1, MAX_CMD_TIMEOUT_SECS));
    let mut timed_out = false;
    let status = loop {
        match child.try_wait() {
            Ok(Some(st)) => break Some(st),
            Ok(None) => {
                if Instant::now() >= deadline {
                    timed_out = true;
                    let _ = child.kill();
                    let _ = child.wait();
                    break None;
                }
                thread::sleep(Duration::from_millis(20));
            }
            Err(_) => break None,
        }
    };

    let out_bytes = out_handle.join().unwrap_or_default();
    let err_bytes = err_handle.join().unwrap_or_default();
    let (stdout, out_trunc) = truncate_utf8(&out_bytes, MAX_CMD_OUTPUT_BYTES);
    let (stderr, err_trunc) = truncate_utf8(&err_bytes, MAX_CMD_OUTPUT_BYTES / 2);
    let exit_code = status.and_then(|s| s.code()).unwrap_or(if timed_out { 124 } else { -1 });

    Ok((exit_code, timed_out, stdout, stderr, out_trunc || err_trunc))
}

fn is_git_internal(rel: &Path) -> bool {
    rel.components().any(|c| c.as_os_str() == ".git")
}

fn resolve_workspace_path(
    cfg: &AppConfig,
    raw_path: &str,
    for_write: bool,
) -> Result<PathBuf, String> {
    let requested = Path::new(raw_path);
    if requested
        .components()
        .any(|c| matches!(c, Component::ParentDir))
    {
        return Err("path traversal ('..') is not allowed".into());
    }

    let candidate = if requested.is_absolute() {
        requested.to_path_buf()
    } else {
        cfg.workspace_root.join(requested)
    };

    let mut allowed_roots = vec![cfg.workspace_root.clone()];
    if !for_write && cfg.allow_android_storage {
        for extra in ["/sdcard", "/storage/emulated/0"] {
            if let Ok(canon) = fs::canonicalize(extra) {
                allowed_roots.push(canon);
            }
        }
    }

    let resolved = if candidate.exists() {
        fs::canonicalize(&candidate).map_err(|e| format!("cannot resolve path: {e}"))?
    } else if for_write {
        let mut suffix_parts: Vec<std::ffi::OsString> = Vec::new();
        let mut cur = candidate.as_path();
        while !cur.exists() {
            let name = cur
                .file_name()
                .ok_or_else(|| "invalid target path".to_owned())?;
            suffix_parts.push(name.to_os_string());
            cur = cur
                .parent()
                .ok_or_else(|| "invalid parent path".to_owned())?;
        }
        let mut base =
            fs::canonicalize(cur).map_err(|e| format!("cannot resolve ancestor path: {e}"))?;
        for part in suffix_parts.into_iter().rev() {
            base.push(part);
        }
        base
    } else {
        return Err(format!("path does not exist: {raw_path}"));
    };

    let inside_allowed = allowed_roots
        .iter()
        .any(|root| resolved.starts_with(root));
    if !inside_allowed {
        return Err(format!(
            "path '{}' is outside workspace root '{}'",
            resolved.display(),
            cfg.workspace_root.display()
        ));
    }

    if let Ok(rel) = resolved.strip_prefix(&cfg.workspace_root) {
        if for_write && is_git_internal(rel) {
            return Err("refusing to modify .git internals directly".into());
        }
    }

    Ok(resolved)
}

fn should_skip_dir(name: &str) -> bool {
    matches!(name, ".git" | "target" | "node_modules" | ".gradle" | "build")
}

fn run_device_probe(cfg: &AppConfig) -> Result<Value, String> {
    let mut summary = probe_runtime_summary(cfg);
    if find_in_path("getprop").is_some() {
        let mut props = BTreeMap::new();
        for key in [
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.product.cpu.abi",
        ] {
            if let Ok((0, false, out, _, _)) =
                run_command_timeout("getprop", &[key], &cfg.workspace_root, 3)
            {
                let v = out.trim();
                if !v.is_empty() {
                    props.insert(key, v.to_owned());
                }
            }
        }
        if !props.is_empty() {
            summary["android_props"] = json!(props);
        }
    }
    if find_in_path("adb").is_some() {
        if let Ok((0, false, out, _, _)) =
            run_command_timeout("adb", &["devices"], &cfg.workspace_root, 4)
        {
            summary["adb_devices"] = json!(out.trim());
        }
    }
    Ok(summary)
}

fn collect_entries(
    root: &Path,
    dir: &Path,
    depth: usize,
    max_depth: usize,
    ext_filter: Option<&str>,
    out: &mut Vec<Value>,
) -> Result<(), String> {
    if out.len() >= 200 {
        return Ok(());
    }
    let mut entries: Vec<_> = fs::read_dir(dir)
        .map_err(|e| format!("failed to read directory: {e}"))?
        .flatten()
        .collect();
    entries.sort_by_key(|e| e.file_name());

    for entry in entries {
        if out.len() >= 200 {
            break;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        let path = entry.path();
        let is_dir = path.is_dir();
        if is_dir && should_skip_dir(&name) {
            continue;
        }
        if !is_dir {
            if let Some(ext) = ext_filter {
                let matches_ext = path
                    .extension()
                    .and_then(|e| e.to_str())
                    .is_some_and(|e| e.eq_ignore_ascii_case(ext));
                if !matches_ext {
                    continue;
                }
            }
        }
        let rel = path
            .strip_prefix(root)
            .unwrap_or(&path)
            .display()
            .to_string();
        let size = if is_dir {
            0
        } else {
            entry.metadata().map(|m| m.len()).unwrap_or(0)
        };
        out.push(json!({
            "path": rel,
            "type": if is_dir { "dir" } else { "file" },
            "size": size,
        }));
        if is_dir && depth < max_depth {
            collect_entries(root, &path, depth + 1, max_depth, ext_filter, out)?;
        }
    }
    Ok(())
}

fn run_fs_list(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    let raw_path = args.get("path").and_then(Value::as_str).unwrap_or(".");
    let recursive = args
        .get("recursive")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let max_depth = if recursive {
        args.get("max_depth")
            .and_then(Value::as_u64)
            .unwrap_or(3)
            .clamp(1, 6) as usize
    } else {
        1
    };
    let ext = args
        .get("ext")
        .and_then(Value::as_str)
        .map(|s| s.trim_start_matches('.'))
        .filter(|s| !s.is_empty());

    let dir = resolve_workspace_path(cfg, raw_path, false)?;
    if !dir.is_dir() {
        return Err(format!("not a directory: {raw_path}"));
    }
    let mut entries = Vec::new();
    collect_entries(&cfg.workspace_root, &dir, 1, max_depth, ext, &mut entries)?;
    Ok(json!({
        "root": cfg.workspace_root.display().to_string(),
        "count": entries.len(),
        "truncated": entries.len() >= 200,
        "entries": entries,
    }))
}

fn run_fs_read(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    let raw_path = args
        .get("path")
        .and_then(Value::as_str)
        .ok_or("missing 'path'")?;
    let path = resolve_workspace_path(cfg, raw_path, false)?;
    if !path.is_file() {
        return Err(format!("not a file: {raw_path}"));
    }
    let bytes = fs::read(&path).map_err(|e| format!("failed to read file: {e}"))?;
    let text = String::from_utf8(bytes)
        .map_err(|_| "file is not valid UTF-8 (binary files cannot be read as text)".to_owned())?;
    let lines: Vec<&str> = text.lines().collect();
    let total_lines = lines.len();
    let start = args
        .get("start_line")
        .and_then(Value::as_u64)
        .unwrap_or(1)
        .max(1) as usize;
    let end = args
        .get("end_line")
        .and_then(Value::as_u64)
        .map(|v| v as usize)
        .unwrap_or(total_lines)
        .min(total_lines);

    if start > total_lines && total_lines > 0 {
        return Err(format!(
            "start_line {start} exceeds total lines ({total_lines})"
        ));
    }

    let mut out = String::new();
    let mut truncated = false;
    if total_lines > 0 && start <= end {
        for idx in (start - 1)..end {
            let line_str = format!("{}: {}\n", idx + 1, lines[idx]);
            if out.len() + line_str.len() > MAX_READ_BYTES {
                truncated = true;
                break;
            }
            out.push_str(&line_str);
        }
    }
    Ok(json!({
        "path": raw_path,
        "total_lines": total_lines,
        "start_line": start,
        "end_line": end,
        "truncated": truncated,
        "content": out,
    }))
}

fn run_fs_write(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    let raw_path = args
        .get("path")
        .and_then(Value::as_str)
        .ok_or("missing 'path'")?;
    let content = args
        .get("content")
        .and_then(Value::as_str)
        .ok_or("missing 'content'")?;
    if content.len() > MAX_WRITE_BYTES {
        return Err("content exceeds 512 KiB limit".into());
    }
    let append = args
        .get("append")
        .and_then(Value::as_bool)
        .unwrap_or(false);

    let path = resolve_workspace_path(cfg, raw_path, true)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("failed to create parent dir: {e}"))?;
    }
    if append && path.exists() {
        let mut existing = fs::read_to_string(&path)
            .map_err(|e| format!("failed to read existing file for append: {e}"))?;
        if existing.len() + content.len() > MAX_WRITE_BYTES {
            return Err("resulting file exceeds 512 KiB limit".into());
        }
        existing.push_str(content);
        fs::write(&path, existing.as_bytes()).map_err(|e| format!("write failed: {e}"))?;
    } else {
        fs::write(&path, content.as_bytes()).map_err(|e| format!("write failed: {e}"))?;
    }
    Ok(json!({
        "path": raw_path,
        "bytes_written": content.len(),
        "append": append,
    }))
}

fn run_fs_edit(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    let raw_path = args
        .get("path")
        .and_then(Value::as_str)
        .ok_or("missing 'path'")?;
    let old_text = args
        .get("old_text")
        .and_then(Value::as_str)
        .ok_or("missing 'old_text'")?;
    let new_text = args
        .get("new_text")
        .and_then(Value::as_str)
        .ok_or("missing 'new_text'")?;
    if old_text.is_empty() {
        return Err("old_text must not be empty".into());
    }
    let replace_all = args
        .get("replace_all")
        .and_then(Value::as_bool)
        .unwrap_or(false);

    let path = resolve_workspace_path(cfg, raw_path, true)?;
    if !path.is_file() {
        return Err(format!("file does not exist: {raw_path}"));
    }
    let content =
        fs::read_to_string(&path).map_err(|e| format!("failed to read file: {e}"))?;
    let matches = content.matches(old_text).count();
    if matches == 0 {
        return Err("old_text not found in file".into());
    }
    if !replace_all && matches > 1 {
        return Err(format!(
            "old_text matched {matches} times; provide more context or set replace_all=true"
        ));
    }
    let updated = if replace_all {
        content.replace(old_text, new_text)
    } else {
        content.replacen(old_text, new_text, 1)
    };
    if updated.len() > MAX_WRITE_BYTES {
        return Err("updated file exceeds 512 KiB limit".into());
    }
    fs::write(&path, updated.as_bytes()).map_err(|e| format!("write failed: {e}"))?;
    Ok(json!({
        "path": raw_path,
        "replacements": if replace_all { matches } else { 1 },
    }))
}

fn search_recursive(
    root: &Path,
    target: &Path,
    query: &str,
    case_sensitive: bool,
    max_results: usize,
    matches: &mut Vec<Value>,
) {
    if matches.len() >= max_results {
        return;
    }
    if target.is_file() {
        if let Ok(bytes) = fs::read(target) {
            if bytes.len() <= 512 * 1024 && !bytes.contains(&0) {
                if let Ok(text) = std::str::from_utf8(&bytes) {
                    let needle = if case_sensitive {
                        query.to_owned()
                    } else {
                        query.to_ascii_lowercase()
                    };
                    let rel = target
                        .strip_prefix(root)
                        .unwrap_or(target)
                        .display()
                        .to_string();
                    for (idx, line) in text.lines().enumerate() {
                        let hit = if case_sensitive {
                            line.contains(&needle)
                        } else {
                            line.to_ascii_lowercase().contains(&needle)
                        };
                        if hit {
                            let (snippet, _) = truncate_utf8(line.as_bytes(), 240);
                            matches.push(json!({
                                "file": rel,
                                "line": idx + 1,
                                "text": snippet,
                            }));
                            if matches.len() >= max_results {
                                break;
                            }
                        }
                    }
                }
            }
        }
        return;
    }
    if let Ok(entries) = fs::read_dir(target) {
        let mut sorted: Vec<_> = entries.flatten().collect();
        sorted.sort_by_key(|e| e.file_name());
        for entry in sorted {
            if matches.len() >= max_results {
                break;
            }
            let p = entry.path();
            if p.is_dir() && should_skip_dir(&entry.file_name().to_string_lossy()) {
                continue;
            }
            search_recursive(root, &p, query, case_sensitive, max_results, matches);
        }
    }
}

fn run_fs_search(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    let query = args
        .get("query")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or("missing non-empty 'query'")?;
    let raw_path = args.get("path").and_then(Value::as_str).unwrap_or(".");
    let case_sensitive = args
        .get("case_sensitive")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let max_results = args
        .get("max_results")
        .and_then(Value::as_u64)
        .unwrap_or(50)
        .clamp(1, 200) as usize;

    let target = resolve_workspace_path(cfg, raw_path, false)?;
    let mut matches = Vec::new();
    search_recursive(
        &cfg.workspace_root,
        &target,
        query,
        case_sensitive,
        max_results,
        &mut matches,
    );
    Ok(json!({
        "query": query,
        "count": matches.len(),
        "matches": matches,
    }))
}

fn run_git_inspect(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    if find_in_path("git").is_none() {
        return Err("git binary is not installed in PATH".into());
    }
    let op = args
        .get("op")
        .and_then(Value::as_str)
        .ok_or("missing 'op'")?;
    let limit = args
        .get("limit")
        .and_then(Value::as_u64)
        .unwrap_or(10)
        .clamp(1, 30);
    let rel_path = args.get("path").and_then(Value::as_str);
    if let Some(p) = rel_path {
        let _ = resolve_workspace_path(cfg, p, false)?;
    }

    let limit_str = format!("-n{limit}");
    let mut cmd_args: Vec<&str> = match op {
        "status" => vec!["status", "--short", "--branch"],
        "diff" => vec!["diff", "--no-ext-diff"],
        "log" => vec!["log", &limit_str, "--oneline", "--decorate"],
        "branches" => vec!["branch", "-a", "--no-color"],
        _ => return Err(format!("unsupported git_inspect op: {op}")),
    };
    if let Some(p) = rel_path {
        if matches!(op, "diff" | "log") {
            cmd_args.push("--");
            cmd_args.push(p);
        }
    }
    let (code, timed_out, stdout, stderr, truncated) =
        run_command_timeout("git", &cmd_args, &cfg.workspace_root, 10)?;
    Ok(json!({
        "op": op,
        "exit_code": code,
        "timed_out": timed_out,
        "stdout": stdout,
        "stderr": stderr,
        "truncated": truncated,
    }))
}

fn validate_http_url(raw: &str) -> Result<&str, String> {
    let trimmed = raw.trim();
    if !(trimmed.starts_with("http://") || trimmed.starts_with("https://")) {
        return Err("only http:// or https:// URLs are allowed".into());
    }
    if trimmed.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err("URL must not contain whitespace or control characters".into());
    }
    Ok(trimmed)
}

fn run_termux_cmd(bin: &str, args: &[&str], cfg: &AppConfig) -> Result<Value, String> {
    if find_in_path(bin).is_none() {
        return Err(format!(
            "'{bin}' is not installed (on Android Termux, run 'pkg install termux-api' and install the Termux:API app)"
        ));
    }
    let (code, timed_out, stdout, stderr, truncated) =
        run_command_timeout(bin, args, &cfg.workspace_root, 15)?;
    let parsed_json = serde_json::from_str::<Value>(stdout.trim()).ok();
    Ok(json!({
        "command": bin,
        "exit_code": code,
        "timed_out": timed_out,
        "data": parsed_json,
        "stdout": if parsed_json.is_some() { Value::Null } else { json!(stdout.trim()) },
        "stderr": stderr.trim(),
        "truncated": truncated,
    }))
}

fn run_termux_device(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    let op = args
        .get("op")
        .and_then(Value::as_str)
        .ok_or("missing 'op'")?;

    match op {
        "battery" => {
            if find_in_path("termux-battery-status").is_some() {
                run_termux_cmd("termux-battery-status", &[], cfg)
            } else if let Some(sys) = read_sysfs_battery() {
                Ok(sys)
            } else {
                Err("battery status unavailable (no termux-battery-status or sysfs battery)".into())
            }
        }
        "device_props" => {
            if find_in_path("getprop").is_some() {
                run_device_probe(cfg)
            } else {
                Ok(probe_runtime_summary(cfg))
            }
        }
        "clipboard_get" => {
            if find_in_path("termux-clipboard-get").is_some() {
                run_termux_cmd("termux-clipboard-get", &[], cfg)
            } else if find_in_path("wl-paste").is_some() {
                run_termux_cmd("wl-paste", &["-n"], cfg)
            } else if find_in_path("xclip").is_some() {
                run_termux_cmd("xclip", &["-o", "-selection", "clipboard"], cfg)
            } else {
                Err("clipboard_get requires termux-clipboard-get (Termux:API) or wl-paste/xclip".into())
            }
        }
        "clipboard_set" => {
            let text = args
                .get("text")
                .and_then(Value::as_str)
                .ok_or("missing 'text'")?;
            if find_in_path("termux-clipboard-set").is_some() {
                run_termux_cmd("termux-clipboard-set", &[text], cfg)
            } else {
                Err("clipboard_set requires termux-clipboard-set (Termux:API)".into())
            }
        }
        "location" => {
            let provider = args
                .get("provider")
                .and_then(Value::as_str)
                .unwrap_or("network");
            if !matches!(provider, "gps" | "network" | "passive") {
                return Err("provider must be gps, network, or passive".into());
            }
            run_termux_cmd("termux-location", &["-p", provider], cfg)
        }
        "wifi_info" => run_termux_cmd("termux-wifi-connectioninfo", &[], cfg),
        "sensors" => {
            if let Some(s) = args.get("sensor").and_then(Value::as_str) {
                run_termux_cmd("termux-sensor", &["-s", s, "-n", "1"], cfg)
            } else {
                run_termux_cmd("termux-sensor", &["-l"], cfg)
            }
        }
        "notification" => {
            let title = args
                .get("title")
                .and_then(Value::as_str)
                .unwrap_or("Agent Notification");
            let content = args
                .get("text")
                .and_then(Value::as_str)
                .ok_or("missing 'text' for notification content")?;
            let mut cmd_args = vec!["-t", title, "-c", content];
            if let Some(id) = args.get("id").and_then(Value::as_str) {
                cmd_args.push("--id");
                cmd_args.push(id);
            }
            run_termux_cmd("termux-notification", &cmd_args, cfg)
        }
        "toast" => {
            let text = args
                .get("text")
                .and_then(Value::as_str)
                .ok_or("missing 'text'")?;
            run_termux_cmd("termux-toast", &[text], cfg)
        }
        "vibrate" => {
            let ms = args
                .get("duration_ms")
                .and_then(Value::as_u64)
                .unwrap_or(200)
                .clamp(50, 5000);
            let ms_str = ms.to_string();
            run_termux_cmd("termux-vibrate", &["-d", &ms_str], cfg)
        }
        "tts_speak" => {
            let text = args
                .get("text")
                .and_then(Value::as_str)
                .ok_or("missing 'text'")?;
            run_termux_cmd("termux-tts-speak", &[text], cfg)
        }
        "open_url" => {
            let raw_url = args
                .get("url")
                .and_then(Value::as_str)
                .ok_or("missing 'url'")?;
            let url = validate_http_url(raw_url)?;
            if find_in_path("termux-open-url").is_some() {
                run_termux_cmd("termux-open-url", &[url], cfg)
            } else if find_in_path("am").is_some() {
                run_termux_cmd(
                    "am",
                    &["start", "-a", "android.intent.action.VIEW", "-d", url],
                    cfg,
                )
            } else {
                Err("open_url requires termux-open-url or Android 'am'".into())
            }
        }
        _ => Err(format!("unsupported termux_device op: {op}")),
    }
}

#[derive(Clone, Copy)]
enum AndroidBackend {
    Adb,
    Shizuku,
}

fn pick_android_backend(pref: &str) -> Result<AndroidBackend, String> {
    match pref {
        "adb" => {
            if find_in_path("adb").is_some() {
                Ok(AndroidBackend::Adb)
            } else {
                Err("'adb' not found in PATH (on Termux run 'pkg install android-tools' and connect Wireless Debugging)".into())
            }
        }
        "shizuku" => {
            if find_in_path("rish").is_some() {
                Ok(AndroidBackend::Shizuku)
            } else {
                Err("'rish' (Shizuku) not found in PATH".into())
            }
        }
        _ => {
            if find_in_path("adb").is_some() {
                Ok(AndroidBackend::Adb)
            } else if find_in_path("rish").is_some() {
                Ok(AndroidBackend::Shizuku)
            } else {
                Err("neither 'adb' (Self-ADB) nor 'rish' (Shizuku) is available in PATH".into())
            }
        }
    }
}

fn run_android_shell(
    backend: AndroidBackend,
    shell_cmd: &str,
    cfg: &AppConfig,
    timeout_secs: u64,
) -> Result<(i32, bool, String, String, bool), String> {
    match backend {
        AndroidBackend::Adb => {
            run_command_timeout("adb", &["shell", shell_cmd], &cfg.workspace_root, timeout_secs)
        }
        AndroidBackend::Shizuku => {
            run_command_timeout("rish", &["-c", shell_cmd], &cfg.workspace_root, timeout_secs)
        }
    }
}

fn extract_xml_attr(tag: &str, attr: &str) -> String {
    let needle = format!("{attr}=\"");
    let Some(start) = tag.find(&needle) else {
        return String::new();
    };
    let rest = &tag[start + needle.len()..];
    let Some(end) = rest.find('"') else {
        return String::new();
    };
    rest[..end]
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}

fn parse_bounds_center(bounds: &str) -> Option<[i64; 2]> {
    let (p1, p2) = bounds.strip_prefix('[')?.split_once("][")?;
    let p2 = p2.strip_suffix(']')?;
    let (x1, y1) = p1.split_once(',')?;
    let (x2, y2) = p2.split_once(',')?;
    let cx = (x1.parse::<i64>().ok()? + x2.parse::<i64>().ok()?) / 2;
    let cy = (y1.parse::<i64>().ok()? + y2.parse::<i64>().ok()?) / 2;
    Some([cx, cy])
}

pub fn parse_android_ui_xml(xml: &str) -> Vec<Value> {
    let mut nodes = Vec::new();
    let mut rest = xml;
    while let Some(pos) = rest.find("<node ") {
        if nodes.len() >= 120 {
            break;
        }
        rest = &rest[pos..];
        let Some(end) = rest.find('>') else {
            break;
        };
        let tag = &rest[..=end];
        rest = &rest[end + 1..];

        let text = extract_xml_attr(tag, "text");
        let desc = extract_xml_attr(tag, "content-desc");
        let res_id = extract_xml_attr(tag, "resource-id");
        let clickable = extract_xml_attr(tag, "clickable") == "true";
        let scrollable = extract_xml_attr(tag, "scrollable") == "true";
        let enabled = extract_xml_attr(tag, "enabled") != "false";
        let focused = extract_xml_attr(tag, "focused") == "true";
        let bounds = extract_xml_attr(tag, "bounds");
        let class_name = extract_xml_attr(tag, "class");

        if text.is_empty() && desc.is_empty() && res_id.is_empty() && !clickable && !scrollable {
            continue;
        }
        let short_class = class_name
            .rsplit('.')
            .next()
            .unwrap_or(&class_name)
            .to_owned();
        let center = parse_bounds_center(&bounds);
        let mut node = json!({
            "class": short_class,
            "bounds": bounds,
            "clickable": clickable,
            "scrollable": scrollable,
            "enabled": enabled,
        });
        if !text.is_empty() {
            node["text"] = json!(text);
        }
        if !desc.is_empty() {
            node["desc"] = json!(desc);
        }
        if !res_id.is_empty() {
            node["id"] = json!(res_id);
        }
        if let Some(c) = center {
            node["center"] = json!(c);
        }
        if focused {
            node["focused"] = json!(true);
        }
        nodes.push(node);
    }
    nodes
}

fn escape_adb_input_text(raw: &str) -> Result<String, String> {
    if raw.len() > 1024 {
        return Err("input_text exceeds 1024 bytes".into());
    }
    let mut out = String::with_capacity(raw.len() * 2);
    for ch in raw.chars() {
        if ch.is_control() {
            return Err("input_text must not contain control characters".into());
        }
        if ch == ' ' {
            out.push_str("%s");
        } else if ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_' | '.' | '/' | ':' | '@' | ',') {
            out.push(ch);
        } else {
            out.push('\\');
            out.push(ch);
        }
    }
    Ok(out)
}

fn run_android_ui(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    let op = args
        .get("op")
        .and_then(Value::as_str)
        .ok_or("missing 'op'")?;
    let backend_pref = args
        .get("backend")
        .and_then(Value::as_str)
        .unwrap_or("auto");
    let backend = pick_android_backend(backend_pref)?;

    match op {
        "status" => match backend {
            AndroidBackend::Adb => {
                let (code, timed_out, stdout, stderr, _) =
                    run_command_timeout("adb", &["devices", "-l"], &cfg.workspace_root, 5)?;
                Ok(json!({ "backend": "adb", "exit_code": code, "timed_out": timed_out, "devices": stdout.trim(), "stderr": stderr.trim() }))
            }
            AndroidBackend::Shizuku => {
                let (code, timed_out, stdout, stderr, _) =
                    run_android_shell(backend, "id", cfg, 5)?;
                Ok(json!({ "backend": "shizuku", "exit_code": code, "timed_out": timed_out, "id": stdout.trim(), "stderr": stderr.trim() }))
            }
        },
        "current_app" => {
            let (code, _, stdout, stderr, _) = run_android_shell(
                backend,
                "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|mCurrentFocus' | head -n 5",
                cfg,
                8,
            )?;
            Ok(json!({ "exit_code": code, "focus": stdout.trim(), "stderr": stderr.trim() }))
        }
        "dump_ui" => {
            let (code, _, stdout, stderr, _) = run_android_shell(
                backend,
                "uiautomator dump /data/local/tmp/harness_ui.xml >/dev/null && cat /data/local/tmp/harness_ui.xml",
                cfg,
                15,
            )?;
            if code != 0 && !stdout.contains("<hierarchy") {
                return Err(format!("uiautomator dump failed: {}", stderr.trim()));
            }
            let nodes = parse_android_ui_xml(&stdout);
            Ok(json!({
                "node_count": nodes.len(),
                "nodes": nodes,
            }))
        }
        "screenshot" => {
            if !matches!(backend, AndroidBackend::Adb) {
                return Err("screenshot capture requires adb exec-out".into());
            }
            let dir = cfg.workspace_root.join(".harness");
            fs::create_dir_all(&dir).map_err(|e| format!("failed to create .harness dir: {e}"))?;
            let ts = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            let out_path = dir.join(format!("screen_{ts}.png"));
            let output = Command::new("adb")
                .args(["exec-out", "screencap", "-p"])
                .current_dir(&cfg.workspace_root)
                .output()
                .map_err(|e| format!("adb screencap failed: {e}"))?;
            if !output.status.success() || output.stdout.is_empty() {
                return Err(format!(
                    "screencap failed: {}",
                    String::from_utf8_lossy(&output.stderr).trim()
                ));
            }
            fs::write(&out_path, &output.stdout)
                .map_err(|e| format!("failed to save screenshot: {e}"))?;
            let rel = out_path
                .strip_prefix(&cfg.workspace_root)
                .unwrap_or(&out_path)
                .display()
                .to_string();
            Ok(json!({ "path": rel, "bytes": output.stdout.len() }))
        }
        "tap" => {
            let x = args.get("x").and_then(Value::as_i64).ok_or("missing 'x'")?;
            let y = args.get("y").and_then(Value::as_i64).ok_or("missing 'y'")?;
            if x < 0 || y < 0 {
                return Err("coordinates must be non-negative".into());
            }
            let cmd = format!("input tap {x} {y}");
            let (code, _, _, stderr, _) = run_android_shell(backend, &cmd, cfg, 8)?;
            Ok(json!({ "op": "tap", "x": x, "y": y, "exit_code": code, "stderr": stderr.trim() }))
        }
        "swipe" => {
            let x1 = args.get("x1").and_then(Value::as_i64).ok_or("missing 'x1'")?;
            let y1 = args.get("y1").and_then(Value::as_i64).ok_or("missing 'y1'")?;
            let x2 = args.get("x2").and_then(Value::as_i64).ok_or("missing 'x2'")?;
            let y2 = args.get("y2").and_then(Value::as_i64).ok_or("missing 'y2'")?;
            let dur = args
                .get("duration_ms")
                .and_then(Value::as_u64)
                .unwrap_or(300)
                .clamp(50, 5000);
            let cmd = format!("input swipe {x1} {y1} {x2} {y2} {dur}");
            let (code, _, _, stderr, _) = run_android_shell(backend, &cmd, cfg, 10)?;
            Ok(json!({ "op": "swipe", "exit_code": code, "stderr": stderr.trim() }))
        }
        "input_text" => {
            let raw = args
                .get("text")
                .and_then(Value::as_str)
                .ok_or("missing 'text'")?;
            let escaped = escape_adb_input_text(raw)?;
            let cmd = format!("input text {escaped}");
            let (code, _, _, stderr, _) = run_android_shell(backend, &cmd, cfg, 8)?;
            Ok(json!({ "op": "input_text", "exit_code": code, "stderr": stderr.trim() }))
        }
        "keyevent" => {
            let keycode = args
                .get("keycode")
                .and_then(Value::as_str)
                .ok_or("missing 'keycode'")?;
            if keycode.is_empty()
                || keycode.len() > 40
                || !keycode
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'_')
            {
                return Err("invalid keycode format".into());
            }
            let cmd = format!("input keyevent {keycode}");
            let (code, _, _, stderr, _) = run_android_shell(backend, &cmd, cfg, 8)?;
            Ok(json!({ "op": "keyevent", "keycode": keycode, "exit_code": code, "stderr": stderr.trim() }))
        }
        "launch_app" => {
            let pkg = args
                .get("package")
                .and_then(Value::as_str)
                .ok_or("missing 'package'")?;
            if pkg.is_empty()
                || pkg.len() > 160
                || !pkg
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_'))
            {
                return Err("invalid Android package name".into());
            }
            let cmd = format!(
                "monkey -p {pkg} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {pkg}"
            );
            let (code, _, stdout, stderr, _) = run_android_shell(backend, &cmd, cfg, 10)?;
            Ok(json!({ "op": "launch_app", "package": pkg, "exit_code": code, "stdout": stdout.trim(), "stderr": stderr.trim() }))
        }
        "notifications" => {
            let (code, _, stdout, stderr, truncated) = run_android_shell(
                backend,
                "dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=' | head -n 60",
                cfg,
                10,
            )?;
            Ok(json!({ "exit_code": code, "notifications": stdout.trim(), "stderr": stderr.trim(), "truncated": truncated }))
        }
        "logcat" => {
            let lines = args
                .get("lines")
                .and_then(Value::as_u64)
                .unwrap_or(60)
                .clamp(10, 200);
            let cmd = format!("logcat -d -t {lines}");
            let (code, _, stdout, stderr, truncated) = run_android_shell(backend, &cmd, cfg, 10)?;
            Ok(json!({ "exit_code": code, "lines": lines, "logcat": stdout, "stderr": stderr.trim(), "truncated": truncated }))
        }
        _ => Err(format!("unsupported android_ui op: {op}")),
    }
}

fn run_shell_exec(args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    let command = args
        .get("command")
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .ok_or("missing non-empty 'command'")?;
    if command.len() > 4096 {
        return Err("command exceeds 4 KiB limit".into());
    }
    let timeout_secs = args
        .get("timeout_secs")
        .and_then(Value::as_u64)
        .unwrap_or(DEFAULT_CMD_TIMEOUT_SECS)
        .clamp(1, MAX_CMD_TIMEOUT_SECS);
    let cwd = match args.get("subdir").and_then(Value::as_str) {
        Some(sub) if !sub.trim().is_empty() => {
            let p = resolve_workspace_path(cfg, sub, false)?;
            if !p.is_dir() {
                return Err(format!("subdir is not a directory: {sub}"));
            }
            p
        }
        _ => cfg.workspace_root.clone(),
    };
    let (code, timed_out, stdout, stderr, truncated) =
        run_command_timeout("sh", &["-c", command], &cwd, timeout_secs)?;
    Ok(json!({
        "cwd": cwd.display().to_string(),
        "exit_code": code,
        "timed_out": timed_out,
        "stdout": stdout,
        "stderr": stderr,
        "truncated": truncated,
    }))
}

pub fn execute_harness_tool(name: &str, args: &Value, cfg: &AppConfig) -> Result<Value, String> {
    match name {
        "device_probe" => run_device_probe(cfg),
        "fs_list" => run_fs_list(args, cfg),
        "fs_read" => run_fs_read(args, cfg),
        "fs_write" => run_fs_write(args, cfg),
        "fs_edit" => run_fs_edit(args, cfg),
        "fs_search" => run_fs_search(args, cfg),
        "git_inspect" => run_git_inspect(args, cfg),
        "termux_device" => run_termux_device(args, cfg),
        "android_ui" => run_android_ui(args, cfg),
        "shell_exec" => run_shell_exec(args, cfg),
        _ => Err(format!("unknown harness tool: {name}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_cfg(tag: &str) -> AppConfig {
        let dir = env::temp_dir().join(format!(
            "harness_test_{tag}_{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        AppConfig {
            app_name: "test-app".into(),
            display_name: "Test App".into(),
            subtitle: "Test".into(),
            version: "0.3.0".into(),
            workspace_root: fs::canonicalize(&dir).unwrap(),
            allow_android_storage: false,
        }
    }

    #[test]
    fn workspace_sandbox_blocks_traversal_and_git_writes() {
        let cfg = temp_cfg("sandbox");
        assert!(resolve_workspace_path(&cfg, "../etc/passwd", false).is_err());
        assert!(resolve_workspace_path(&cfg, ".git/config", true).is_err());
        assert!(resolve_workspace_path(&cfg, "sub/new_file.txt", true).is_ok());
        let _ = fs::remove_dir_all(&cfg.workspace_root);
    }

    #[test]
    fn fs_write_edit_read_search_roundtrip() {
        let cfg = temp_cfg("fs");
        let w = run_fs_write(
            &json!({"path": "notes/todo.txt", "content": "alpha\nbeta\ngamma\n"}),
            &cfg,
        )
        .unwrap();
        assert_eq!(w["bytes_written"], 17);

        let e = run_fs_edit(
            &json!({"path": "notes/todo.txt", "old_text": "beta", "new_text": "delta"}),
            &cfg,
        )
        .unwrap();
        assert_eq!(e["replacements"], 1);

        let r = run_fs_read(&json!({"path": "notes/todo.txt", "start_line": 2, "end_line": 2}), &cfg)
            .unwrap();
        assert!(r["content"].as_str().unwrap().contains("2: delta"));

        let s = run_fs_search(&json!({"query": "DELTA"}), &cfg).unwrap();
        assert_eq!(s["count"], 1);

        let _ = fs::remove_dir_all(&cfg.workspace_root);
    }

    #[test]
    fn parse_android_ui_xml_extracts_clickable_centers() {
        let xml = r#"<hierarchy rotation="0"><node index="0" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" clickable="false"><node index="1" text="设置" resource-id="com.android:id/btn" class="android.widget.Button" clickable="true" scrollable="false" enabled="true" bounds="[100,200][300,400]" /></node></hierarchy>"#;
        let nodes = parse_android_ui_xml(xml);
        assert_eq!(nodes.len(), 1);
        assert_eq!(nodes[0]["text"], "设置");
        assert_eq!(nodes[0]["center"], json!([200, 300]));
    }

    #[test]
    fn shell_exec_runs_and_enforces_timeout() {
        let cfg = temp_cfg("shell");
        let ok = run_shell_exec(&json!({"command": "echo hello_harness"}), &cfg).unwrap();
        assert_eq!(ok["exit_code"], 0);
        assert!(ok["stdout"].as_str().unwrap().contains("hello_harness"));

        let to = run_shell_exec(&json!({"command": "sleep 3", "timeout_secs": 1}), &cfg).unwrap();
        assert_eq!(to["timed_out"], true);
        let _ = fs::remove_dir_all(&cfg.workspace_root);
    }
}
