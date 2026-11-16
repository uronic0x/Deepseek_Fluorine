# 澄明 (qingming) · DeepSeek 本地工作台

基于 **2026-11-13 / 2026-11-16 最新 DeepSeek 官方 API 文档** 实现的极简、可审计本地客户端与工作台（BYOK），并参考 GitHub 开源 Agent Harness 与 Android/移动端窄屏适配实践设计。

## 核心特性

1. **极简 Rust 依赖（仅 2 个直接 crate，零异步运行时/宏膨胀）**
   - `ureq`（`rustls` 后端，无 OpenSSL / tokio / reqwest）+ `serde_json`（无 `serde_derive` / `syn` / `proc-macro2`）。
   - 本地 HTTP/1.1 与 SSE 服务器由 `src/http.rs` 基于 `std::net::TcpListener` 直接实现，零 `tiny_http` / `axum` / `hyper`。
   - 提供 `GET /api/tools` 自描述工具目录端点，便于审计与前端/脚本核对。
2. **完整对齐最新 DeepSeek API 规范**
   - 支持 `deepseek-flash`（`DeepSeek-V4.1-Flash-1017`）与 `deepseek-v4-pro`（`DeepSeek-V4-Pro-0813`），1M 上下文，输出预算支持至 `393,216`（384K，UI 默认可选 8K–128K）。
   - 支持 Thinking Mode 开关与强度控制（`none` / `low` / `high` / `max`），并在界面中提供可折叠的“思考过程”查看器。
   - 自动注入并保留官方 `latest_reminder` 用户元信息标记（日期、星期、平台、语言），提升时效回答与上下文缓存命中率。
   - Chat 模式与 Work 联网阶段启用 `deepseek_web_search`、`deepseek_read_url`、`safety_check: high` 与首轮 `title`，并实时展示搜索词与读页进度。
   - **严格遵守协议隔离**：自定义 `function` 工具不与 `deepseek_web_search` / `deepseek_read_url` / `safety_check` / `title` 混用（避免 400），在 Work 模式中拆分为 `联网取证 (research)` 与 `本地计算与交付 (execute)` 两个阶段；在本地工具多轮循环中完整回传 `reasoning_content`，并处理非流式 HTTP 200 `{error, usage}` 异常载荷。
3. **Work 模式 9 项纯 Rust 本地确定性工具（零额外依赖）**
   - `calc`：Shunting-Yard 算术与科学函数求值器（`+ - * / % ^`、`sqrt`、`sin/cos/tan/asin/acos/atan`、`ln/log2/log10/exp`、`pow/min/max/clamp`、`pi/e`）。
   - `stats`：数组描述性统计（计数、求和、均值、中位数、极值、极差、P90/P95、总体/样本标准差与样本方差）。
   - `unit_convert`：长度、面积、质量、温度、存储容量（SI/IEC）、时间、能量、压强单位换算。
   - `date_calc`：公历日期差、`workdays` 工作日（周一至周五）统计、加减天数与星期推算。
   - `text_metrics`：Unicode 字符数、CJK 汉字数、UTF-8 字节数、词数、行数与 token 估算。
   - `json_query`：JSON 校验、格式化、对象键列表与 RFC 6901 JSON Pointer 精确提取。
   - `csv_summary`：RFC 4180 CSV/TSV 解析与数值列汇总（计数、求和、均值、极值）。
   - `text_diff`：基于 LCS 的逐行文本对比，返回增删统计与紧凑 diff。
   - `encode_hash`：Base64 / Hex / URL 编解码与 CRC32 / FNV-1a 64 / SHA-256（纯 Rust FIPS 180-4）摘要计算。

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
