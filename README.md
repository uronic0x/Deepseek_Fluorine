# DeepSeek Chat / Work & Android Harness

极简、可审计的本地 DeepSeek 客户端（仅 `ureq` + `serde_json` 两个直接 Rust 依赖），对齐 DeepSeek 2026-11 API，并参考开源安卓 Agent Harness（AndroidHarness / DroidClaw / Termux-API / Self-ADB / Shizuku）设计了纯 Rust 本地工具集。

## 核心特性

1. **应用名不硬编码**
   - 默认读取 `Cargo.toml` 的 `CARGO_PKG_NAME`，支持通过环境变量动态覆盖：
     - `APP_NAME`：服务标识与导出文件名前缀
     - `APP_DISPLAY_NAME`：页面标题、品牌栏与助手标签
     - `APP_SUBTITLE`：副标题
     - `WORKSPACE_DIR`：Work 本地文件与命令执行沙盒根目录（默认当前目录）
     - `ALLOW_ANDROID_STORAGE=1`：允许只读访问 `/sdcard` 与 `/storage/emulated/0`
   - 服务端在 `/` 动态渲染模板，并提供 `GET /api/meta` 与 `GET /health`。

2. **对齐 DeepSeek 2026-11 API（Chat & Work 模式）**
   - 支持 `deepseek-flash`（`V4.1-Flash-1017`，支持官方联网工具 `deepseek_web_search` / `deepseek_read_url`）与 `deepseek-v4-pro`（`V4-Pro-0813`）。
   - 支持 `thinking` (`enabled` / `disabled`)、`reasoning_effort` (`none` / `low` / `high` / `max`)、最高 `384K` (`393216`) 输出预算与峰谷计费估算。
   - 严格区分多轮上下文规则：
     - 工具调用循环内保留中间 `assistant` 消息的 `reasoning_content` 与 `tool_calls`。
     - 新用户轮次自动剥离响应专用字段（`annotations`、`local_tool_calls`）及已完成轮次的 `reasoning_content`。
     - 从联网取证阶段（`server_tool_calls`）切换到本地函数工具执行阶段时自动清理 `server_tool_calls`，避免与自定义函数工具混用触发参数冲突。
   - 流式增量按 `index` 合并 `server_tool_calls`，并在 `step_reasoning_done` 时保留完整思考链路。

3. **Work 模式本地能力（19 项纯 Rust 工具）**
   - **9 项确定性计算工具**：`calc`、`stats`、`unit_convert`、`date_calc`、`text_metrics`、`json_query`、`csv_summary`、`text_diff`、`encode_hash`。
   - **5 项只读环境与工作区工具**：
     - `device_probe`：探测 OS/架构、Android `getprop`、Termux 环境、Termux-API/ADB/Shizuku 可用性、电量与内存。
     - `fs_list` / `fs_read` / `fs_search`：沙盒内目录列举、行号切片读取、跨文件搜索。
     - `git_inspect`：只读查看 `status`、`diff`、`log`、`branches`。
   - **5 项设备控制与修改工具（受前端“设备/Shell”与 `device_tools` 开关控制）**：
     - `fs_write` / `fs_edit`：沙盒内文件写入与精确替换编辑。
     - `termux_device`：调用 `termux-battery-status`、`termux-clipboard-*`、`termux-location`、`termux-wifi-connectioninfo`、`termux-sensor`、`termux-notification`、`termux-toast`、`termux-vibrate`、`termux-tts-speak`、`termux-open-url`（并在无 Termux-API 时自动回退到 `/sys/class/power_supply` 与 `getprop`）。
     - `android_ui`：通过 Self-ADB（无线调试）或 Shizuku (`rish`) 执行 `status`、`current_app`、`dump_ui`（纯 Rust 解析精简语义节点与中心点击坐标）、`screenshot`、`tap`、`swipe`、`input_text`、`keyevent`、`launch_app`、`notifications`、`logcat`。
     - `shell_exec`：工作区沙盒内带超时强杀与输出截断的受控命令执行。

## 本地运行

需要 Rust stable 工具链（桌面或 Android Termux 均可直接编译）：

```sh
cargo run --release
```

可选自定义应用名与工作区：

```sh
APP_DISPLAY_NAME="我的工作台" APP_NAME="my-workbench" WORKSPACE_DIR="$PWD" cargo run --release
```

## 测试与检查

```sh
sh scripts/check.sh
```
