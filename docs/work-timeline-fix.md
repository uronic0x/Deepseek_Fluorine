# Work 过程顺序与轮次限制修复参考

基线：`uronic0x/Deepseek_Fluorine`，`main` 提交
`9f61eded8475ef0af92dcd7fc51caa12af0b6d1b`（v0.3.1）。

## 问题与改动

原生界面按“全部思考 → 全部工具 → 正文”渲染；流式正文又累计多个模型响应，
最终结果则通常只含最后一次响应。因此，中间说明会被挤到一起，完成后还可能消失。

本补丁增加 `WorkStep` / `work_steps`：每次模型响应独立保存思考、正文、引用和
该响应请求的工具记录。界面按“本轮说明 → 本轮工具 → 下一轮说明”顺序展示，
每组工具仍可折叠。只调用工具、没有说明文字的轮次也有自己的位置。

- Kotlin 引擎发出明确的 `RoundStarted` 事件，SSE 与 JSON 回退响应均写入过程记录。
- ViewModel 用单个事件队列更新界面，最终落盘前先排空，避免流式更新覆盖最终状态。
- Compose 逐轮渲染，保留每轮自己的引用坐标；移除消息顶部重复的总工具数标签。
- 会话 JSON 和 Android Markdown 历史导出保留过程顺序；旧数据缺少新字段时仍可读取。
- Rust 本地工具循环与 Web 展示使用同样的逐轮结构，中断时保留已经完成的工具记录。
- 原有的汇总字段继续供文件卡片、上下文等现有功能使用。

历史文件如果原本没有保存中间正文，就无法从旧的汇总数据恢复真实顺序。
新运行的 Work 任务会保存完整的逐轮展示数据。

## 设置

| 设置 | 原来 | 修复后 |
| --- | --- | --- |
| Android 工具轮次默认值 | 10 | 50 |
| Android 工具轮次范围 | 设置页 1–30；引擎 1–50 | 设置页、保存、读取、执行均为 1–500 |
| Rust / Web 工具轮次 | 默认 20，范围 1–50 | 默认 50，范围 1–500 |
| Android 历史消息默认值 | 24 条 | 64 条 |
| Android 历史消息范围 | 设置页、偏好读取 4–80；请求切片另有 100 条限制 | 统一为 4–1000 条 |

工具轮次按一批工具计数，一次模型响应可以请求多个工具。用完 N 批后，再允许
一次 `tool_choice: "none"` 的收尾响应。若上游仍要求执行工具，会保留当前输出并停止，
不会继续执行超预算工具。`max_rounds=1` 现在允许一批工具加一次收尾回复。

历史消息按用户/助手完整组截取，不拆散助手携带的工具子轮次。1000 条普通历史消息
通常相当于约 500 组用户/助手交互；它不是模型 Token 容量的保证。
设置页改为可直接输入整数，显示范围和当前有效值。已保存的有效偏好值继续保留，
默认值只影响没有保存设置的安装；已有用户可在设置中主动调大。

## 验证记录

| 检查 | 结果 |
| --- | --- |
| `cargo test` | 16 项通过 |
| `cargo build` | 通过 |
| `node --test tests/core.test.mjs` | 17 项通过 |
| `python3 tests/integration.py` | 5 项通过，使用本地模拟上游，无真实 API 请求 |
| Kotlin 2.2.10 / JVM 17 编译原生协议、引擎、工具及新增测试 | 通过 |
| 新增 `WorkTimelineTest`，通过 JUnit 4 独立运行 | 6 项通过 |
| Web 实际渲染函数的 DOM 检查 | 顺序、最终正文不重复、展开状态、仅工具中断、旧回复、文本转义均通过 |
| `node --check web/app.js`、Python 语法、`git diff --check` | 通过 |

新增 Kotlin 测试覆盖逐轮内容与引用持久化、旧结果兼容、扩大历史截取、单批工具后收尾、
JSON 回退和工具完成后取消。新增 Rust 集成测试验证 `max_rounds=1` 的过程顺序与收尾。

完整 Android Gradle 构建和 Compose 真机显示尚未验证：当前环境没有 Android SDK，
Gradle wrapper 下载也被 Java 网络环境阻断。独立 JVM 检查不等同于 APK 构建。
完整浏览器视觉测试未完成；Web 展示执行了实际函数的 DOM 检查。

Rust 1.100.0 下，原仓库 `cargo fmt --check` 本身存在格式差异；
`cargo clippy -- -D warnings` 在未修改的基线与修改版上均报告同样的 12 项既有问题，
包括单字符 `push_str`、索引循环、整除判断、固定块迭代和 JNI Safety 文档。
因此 `scripts/check.sh` 整体没有全部通过。本补丁保留这些既有实现，避免把无关清理混入修复。

## 本地复验

```sh
cargo test
cargo build
node --test tests/core.test.mjs
python3 tests/integration.py
```

有 Android SDK、Gradle 和依赖访问能力时，再执行：

```sh
bash gradlew :app:testDebugUnitTest
bash gradlew :app:assembleDebug
```

建议在设备上用两批以上工具交错输出短说明，检查生成过程中、生成完成后、切换会话后和
重新启动应用后，说明与工具是否仍按原顺序显示；再验证取消及轮次用尽时的部分输出。
