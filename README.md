# 澄明 (qingming) · DeepSeek 本地工作台

基于 **2026-11-13 / 2026-11-16 最新 DeepSeek 官方 API 文档** 实现的极简、可审计本地客户端与工作台（BYOK）。

## 核心特性

1. **极简 Rust 依赖（仅 2 个直接 crate）**
   - `ureq`（`rustls` 后端，无 OpenSSL/tokio/reqwest）+ `serde_json`（无 `serde_derive` / `syn` / `proc-macro2`）。
   - 本地 HTTP/1.1 与 SSE 服务器由 `src/http.rs` 基于 `std::net::TcpListener` 直接实现，零 `tiny_http` / `axum` / `hyper`。
2. **完整对齐最新 DeepSeek API 规范**
   - 支持 `deepseek-flash`（`DeepSeek-V4.1-Flash-1017`）与 `deepseek-v4-pro`（`DeepSeek-V4-Pro-0813`），1M 上下文，最高 65,536 输出预算设置（上游上限 384K）。
   - 支持 Thinking Mode 开关与强度控制（`none` / `low` / `high` / `max`）。
   - Chat 模式与 Work 联网阶段启用 `deepseek_web_search`、`deepseek_read_url`、`safety_check: high` 与首轮 `title`。
   - **严格遵守协议隔离**：自定义 `function` 工具不与 `deepseek_web_search` / `deepseek_read_url` / `safety_check` / `title` 混用（避免 400），在 Work 模式中拆分为 `联网取证 (research)` 与 `本地计算与交付 (execute)` 两个阶段；并在本地工具多轮循环中完整回传 `reasoning_content`。
3. **Work 模式本地计算与工具调用（纯 Rust 零额外依赖）**
   - `calc`：Shunting-Yard 算术与科学函数求值器（`+ - * / % ^`、`sqrt`、`sin/cos/tan`、`ln/log10`、`pow/min/max`、`pi/e`）。
   - `stats`：数组描述性统计（均值、中位数、极值、总体/样本标准差）。
   - `unit_convert`：长度、质量、温度、存储容量（SI/IEC）、时间单位换算。
   - `date_calc`：公历日期差、加减天数与星期推算。
   - `text_metrics`：Unicode 字符数、UTF-8 字节数、词数、行数与 token 估算。

## 本地运行

需要 Rust stable 工具链：

```sh
cargo run --release
```

启动后访问 **http://127.0.0.1:8787**，在“连接设置”中填入你自己的 DeepSeek API Key。

## 测试与检查

```sh
sh scripts/check.sh
```

> 注：本交付仓库已完成 `git init` 与提交，并附带可离线克隆/拉取的 `qingming.bundle`（使用 `git clone qingming.bundle qingming` 即可还原完整 Git 历史）。
