# Fluorine · DeepSeek Native Android Workbench

[English](#english) · [中文](#中文)

A minimal, auditable DeepSeek native client (package `com.uronic0x.fluorine`, display name `Fluorine`, `arm64-v8a` only), aligned with the DeepSeek 2026-11 API, built on a **Rust JNI core (`libfluorine.so`) + Jetpack Compose** architecture with **no local HTTP loopback, no WebView, and no Kotlin engine fallback**.

---

<a id="english"></a>
## English

### Overview

- **Zero HTTP loopback, Rust-only in-process execution**: all chat streaming, model discovery, file uploads, local tool execution, and upstream payload building run inside the Rust core (`src/lib.rs`) bridged over JNI (`FluorineBridge`). There is no Kotlin HTTP/tool fallback path; `buildRustSo` fails the APK build if `libfluorine.so` cannot be produced.
- **Theming & rendering**: pure carbon dark / white light themes (dark by default), native Compose Markdown + LaTeX math rendering, collapsible web-search citations, safe-area inset handling, and auto-hide keyboard on list drag.
- **DeepSeek 2026-11 API**: official `deepseek-flash` / `deepseek-v4-pro` presets plus validated custom model ID / Base URL (`https://` or local loopback `http://127.0.0.1:<port>` / `http://localhost:<port>`); autonomous Work agent instead of a fixed four-step workflow; Prefix Cache-friendly history (static `system`/`tools` prefix, per-round `reasoning_content`, stripped historical reasoning).
- **Local tools & workspace artifacts**: 19 Rust local tools (9 deterministic + 10 workspace/Git/shell/Termux/Android harness tools); `fs_write`/`fs_edit` artifacts appear as preview/share/SAF-export cards; multi-session persistence with ordered IO writes, JSON/Markdown export, and log export.

### Repository maintenance notes

- **`Cargo.lock` is tracked on purpose.** This is a binary application, so locking the dependency graph makes builds reproducible. When upgrading Rust dependencies, run `cargo update` (or `cargo update -p <crate>`) and commit the resulting `Cargo.lock` diff together with the source change.
- **`metadata.json` is intentionally absent.** It is an AI Studio template remnant (with a `SERVER_SIDE_GEMINI_API` capability that has nothing to do with this project) and must not be re-added.
- **`latest_reminder` uses the official DeepSeek format**: a dedicated `role: "latest_reminder"` message placed directly before the `user` message, with content `<date>,<weekday>,<location>,<platform>,<language>` (e.g. `2026-11-17,星期二,Local,App,中文`) across both Android and Web. Do not revert to free-form text or attach it as a field on the user message — the API returns `400` otherwise.

### Build & test

```sh
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest -PskipRustBuild=true
cargo test
node --test tests/core.test.mjs
./scripts/check.sh
```

---

<a id="中文"></a>
## 中文

### 概览

- **零 HTTP 回环 · 纯 Rust 进程内原生执行**：所有流式对话、模型能力探测、附件上传、本地工具执行与上游请求构建均通过 JNI (`FluorineBridge`) 直接在 Rust 核心 (`src/lib.rs` / `libfluorine.so`) 内完成，不再保留 Kotlin 回退引擎；打包 APK 时若 `libfluorine.so` 构建失败或缺失会直接报错阻断。
- **主题、渲染与交互**：纯碳黑暗 / 纯白亮色双主题（默认暗色）、原生 Compose Markdown + LaTeX 数学公式渲染、联网引用折叠展开、安全区域 Insets 适配与小屏/大字号自适应排版、列表拖动自动收起键盘。
- **DeepSeek 2026-11 API**：官方 `deepseek-flash` / `deepseek-v4-pro` 预设，并支持严格校验的自定义模型 ID 与 Base URL（仅允许 `https://` 或本地回环 `http://127.0.0.1:<port>` / `http://localhost:<port>`）；Work 模式为自主 Agent 而非固定四步流程；历史处理保障 Prefix Cache 命中（`system`/`tools` 前缀静态、工具循环内保留当轮 `reasoning_content`、跨轮剥离历史推理）。
- **本地工具链与工作区产物**：19 个 Rust 本地工具（9 个确定性工具 + 10 个工作区/Git/Shell/Termux/Android 工具）；`fs_write`/`fs_edit` 产物以预览/分享/SAF 导出卡片呈现；顺序化持久化的多会话存储，支持 JSON/Markdown 历史导出与运行日志导出。

### 仓库维护说明

- **`Cargo.lock` 有意纳入版本管理。** 本项目是二进制应用，锁定依赖图可保证构建可复现。升级 Rust 依赖时请运行 `cargo update`（或 `cargo update -p <crate>`），并把生成的 `Cargo.lock` 差异与源码改动一并提交。
- **`metadata.json` 有意删除。** 它是 AI Studio 模板残留（含与本项目无关的 `SERVER_SIDE_GEMINI_API` 能力声明），不应再次添加。
- **`latest_reminder` 采用 DeepSeek 官方格式**：独立 `role: "latest_reminder"` 消息、紧邻 `user` 消息之前，Android 与 Web 统一采用 `<date>,<weekday>,<location>,<platform>,<language>`（例如 `2026-11-17,星期二,Local,App,中文`）。不要改回自由文本，也不要作为字段附加在 user 消息上——否则 API 返回 `400`。

### 构建与测试

```sh
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest -PskipRustBuild=true
cargo test
node --test tests/core.test.mjs
./scripts/check.sh
```
