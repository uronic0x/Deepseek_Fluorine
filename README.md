# Fluorine · DeepSeek 安卓原生工作台 & 本地计算 Harness

极简、可审计的 DeepSeek 原生客户端（包名 `com.uronic0x.fluorine`，显示名 `Fluorine`，仅 `arm64-v8a`），对齐 DeepSeek 2026-11 API，采用 **Rust JNI 直接流式回调 + Jetpack Compose** 原生架构，彻底去除本地 HTTP 回环与 WebView。

---

## 架构特点

1. **零 HTTP 回环 · 进程内 JNI 流式直连**
   - 摒弃 `127.0.0.1:8787` 本地明文 TCP 监听（无需开启 `usesCleartextTraffic`），消除同机应用探测本地端口与前后台断连风险。
   - Rust 核心 (`src/lib.rs`) 通过统一的 `EventSink` 特征与 C-ABI JNI 入口 (`FluorineBridge`) 直接将 SSE 事件帧实时回调至 Kotlin 层，并支持基于 `AtomicBool` 的毫秒级流中断 (`Abort`)。
   - 若构建宿主机未安装 `cargo-ndk`，Kotlin 层自动无缝回退至 1:1 协议对等的原生执行引擎 (`KotlinNativeEngine`)，确保任何环境下均可编译运行。

2. **可控依赖与极致体积**
   - Rust 侧仅保留 `ureq` (`rustls`) 与 `serde_json` 两个直接依赖，开启 `lto = true`、`codegen-units = 1`、`strip = true`。
   - Android 侧采用 Jetpack Compose 构建温润纸感（Warm Editorial Paper & Forest Ink）原生界面，不引入 WebView、不引入 OkHttp/Retrofit/kotlinx-serialization，JSON 解析直接使用 Android 平台内置 `org.json`。

3. **对齐 DeepSeek 2026-11 API（Chat & Work 四步协同）**
   - 支持 `deepseek-flash`（`V4.1-Flash-1017`，支持官方联网工具 `deepseek_web_search` / `deepseek_read_url`）与 `deepseek-v4-pro`（`V4-Pro-0813`）。
   - 支持 `thinking` (`enabled` / `disabled`)、`reasoning_effort` (`none` / `low` / `high` / `max`)、最高 `384K` (`393216`) 输出预算与 UTC 峰谷动态计费估算。
   - Work 四步状态机：`01 拆解目标 (plan)` → `02 联网取证 (research)` → `03 本地工具执行 (execute)` → `04 文本审查 (review)`。

4. **19 项本地确定性计算与设备 Harness 工具**
   - **9 项确定性计算工具**：`calc`、`stats`、`unit_convert`、`date_calc`、`text_metrics`、`json_query`、`csv_summary`、`text_diff`、`encode_hash`。
   - **5 项只读环境与工作区工具**：`device_probe`、`fs_list`、`fs_read`、`fs_search`、`git_inspect`。
   - **5 项设备控制与修改工具（受“设备/Shell”开关保护）**：`fs_write`、`fs_edit`、`termux_device`、`android_ui`、`shell_exec`。

---

## Android APK 沙箱已知能力边界

- **应用私有工作区**：默认工作区位于 `context.filesDir/workspace`（无需额外存储权限即可完整执行 `fs_list`、`fs_read`、`fs_write`、`fs_edit`、`fs_search` 及全部确定性计算工具）。
- **共享存储只读访问 (`/sdcard`)**：在「连接设置」中开启「允许只读访问 /sdcard 共享存储」并授予权限后，`fs_list` / `fs_read` / `fs_search` 可只读访问 `/sdcard`，永不修改工作区外文件。
- **外部二进制探测 (`device_probe`)**：标准 Android APK 沙箱的 `PATH` 内通常不包含 `adb`、`rish`、`git`、`termux-*` 等外部二进制，`device_probe` 会如实返回 `false`；同时电池、内存、剪贴板、震动、Toast、浏览器跳转均已对接 Android 原生系统服务。
- **隐私与内存驻留**：API Key 与会话上下文仅在当前进程内存中保留，无第三方中转或云端同步，关闭应用即销毁。

---

## 构建说明

### 1. 构建 Android APK（含可选 Rust JNI 交叉编译）

若宿主机已配置 Rust、`aarch64-linux-android` target 与 `cargo-ndk`，Gradle 的 `preBuild` 任务会自动调用 `buildRustSo` 编译并打包 `app/src/main/jniLibs/arm64-v8a/libfluorine.so`：

```sh
gradle assembleDebug
```

输出产物：`app/build/outputs/apk/debug/app-debug.apk`

### 2. 桌面端 CLI 模式与测试

```sh
cargo test
gradle :app:testDebugUnitTest
```
