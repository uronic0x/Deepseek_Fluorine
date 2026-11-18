package com.uronic0x.fluorine.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ModelOption(val id: String, val label: String)
data class EffortOption(val id: String, val label: String)
data class MaxTokenOption(val value: Int, val label: String)

data class ModelCapabilityInfo(
    val id: String,
    val name: String = "",
    val contextWindow: Int = 0,
    val maxOutputTokens: Int = 0,
    val inputModalities: List<String> = emptyList()
)

data class ChatAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val kind: String, // "image" | "document" | "text"
    val localPath: String,
    val sizeBytes: Long = 0L,
    val fileId: String = "",
    val parsePurpose: String = "user_data" // "user_data" | "text_recognition" | "rich_layout_recognition"
)

data class ToolDescriptor(
    val name: String,
    val category: String,
    val label: String
)

data class WorkToolsConfig(
    val deterministic: Boolean = true,
    val workspaceRead: Boolean = true,
    val workspaceWrite: Boolean = true,
    val git: Boolean = true,
    val shellExec: Boolean = false,
    val deviceControl: Boolean = false,
    val disabledTools: Set<String> = emptySet()
) {
    fun enabledToolNames(): Set<String> {
        val out = linkedSetOf<String>()
        if (deterministic) out.addAll(DETERMINISTIC_TOOL_NAMES)
        if (workspaceRead) out.addAll(WORKSPACE_READ_TOOL_NAMES)
        if (workspaceWrite) out.addAll(WORKSPACE_WRITE_TOOL_NAMES)
        if (git) out.addAll(GIT_TOOL_NAMES)
        if (shellExec) out.addAll(SHELL_TOOL_NAMES)
        if (deviceControl) out.addAll(DEVICE_CONTROL_TOOL_NAMES)
        val canonicalDisabled = disabledTools.mapTo(mutableSetOf()) { raw ->
            when (raw) {
                "calc_eval" -> "calc"
                "stats_summary" -> "stats"
                else -> raw
            }
        }
        out.removeAll(canonicalDisabled)
        return out
    }

    fun isToolEnabled(rawName: String): Boolean {
        val canonical = when (rawName) {
            "calc_eval" -> "calc"
            "stats_summary" -> "stats"
            else -> rawName
        }
        return canonical in enabledToolNames()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("deterministic", deterministic)
        .put("workspace_read", workspaceRead)
        .put("workspace_write", workspaceWrite)
        .put("git", git)
        .put("shell_exec", shellExec)
        .put("device_control", deviceControl)
        .put("disabled_tools", JSONArray(disabledTools.sorted()))

    companion object {
        val DETERMINISTIC_TOOL_NAMES = setOf(
            "calc", "stats", "unit_convert", "date_calc", "text_metrics",
            "json_query", "csv_summary", "text_diff", "encode_hash"
        )
        val WORKSPACE_READ_TOOL_NAMES = setOf("fs_list", "fs_read", "fs_search")
        val WORKSPACE_WRITE_TOOL_NAMES = setOf("fs_write", "fs_edit")
        val GIT_TOOL_NAMES = setOf("git_cmd")
        val SHELL_TOOL_NAMES = setOf("shell_exec")
        val DEVICE_CONTROL_TOOL_NAMES = setOf("termux_api", "self_adb")

        val ALL_DESCRIPTORS = listOf(
            ToolDescriptor("calc", "deterministic", "calc · 数学表达式计算"),
            ToolDescriptor("stats", "deterministic", "stats · 描述性统计"),
            ToolDescriptor("unit_convert", "deterministic", "unit_convert · 单位换算"),
            ToolDescriptor("date_calc", "deterministic", "date_calc · 日期与工作日"),
            ToolDescriptor("text_metrics", "deterministic", "text_metrics · 字符与词数统计"),
            ToolDescriptor("json_query", "deterministic", "json_query · JSON 查询与校验"),
            ToolDescriptor("csv_summary", "deterministic", "csv_summary · CSV 汇总统计"),
            ToolDescriptor("text_diff", "deterministic", "text_diff · 文本逐行比对"),
            ToolDescriptor("encode_hash", "deterministic", "encode_hash · 编解码与哈希"),
            ToolDescriptor("fs_list", "workspace_read", "fs_list · 列举工作区目录"),
            ToolDescriptor("fs_read", "workspace_read", "fs_read · 读取工作区文本"),
            ToolDescriptor("fs_search", "workspace_read", "fs_search · 搜索工作区文本"),
            ToolDescriptor("fs_write", "workspace_write", "fs_write · 写入或追加文件"),
            ToolDescriptor("fs_edit", "workspace_write", "fs_edit · 局部替换编辑文件"),
            ToolDescriptor("git_cmd", "git", "git_cmd · Git 仓库检查"),
            ToolDescriptor("shell_exec", "shell_exec", "shell_exec · 任意 Shell 命令"),
            ToolDescriptor("termux_api", "device_control", "termux_api · Termux 设备接口"),
            ToolDescriptor("self_adb", "device_control", "self_adb · Android 本机诊断与控制")
        )

        fun fromJson(obj: JSONObject?): WorkToolsConfig {
            if (obj == null) return WorkToolsConfig()
            val disabled = mutableSetOf<String>()
            val arr = obj.optJSONArray("disabled_tools")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) disabled.add(s)
                }
            }
            return WorkToolsConfig(
                deterministic = obj.optBoolean("deterministic", true),
                workspaceRead = obj.optBoolean("workspace_read", true),
                workspaceWrite = obj.optBoolean("workspace_write", true),
                git = obj.optBoolean("git", true),
                shellExec = obj.optBoolean("shell_exec", false),
                deviceControl = obj.optBoolean("device_control", false),
                disabledTools = disabled
            )
        }
    }
}

sealed interface StreamUiEvent {
    data class RoundStarted(val round: Int) : StreamUiEvent
    data class ThinkingDelta(val delta: String) : StreamUiEvent
    data class ContentDelta(val delta: String) : StreamUiEvent
    data class Status(val text: String) : StreamUiEvent
    data class ToolRecord(val record: LocalToolCallRecord) : StreamUiEvent
    data class SourcesUpdated(val sources: List<SearchSource>) : StreamUiEvent
}

data class SearchSource(
    val title: String,
    val url: String,
    val snippet: String = "",
    val siteName: String = "",
    val publishedAt: String = "",
    val citeIndex: Int? = null
)

data class CitationPoint(
    val offset: Int,
    val indexes: List<Int>,
    val startIndex: Int = -1,
    val endIndex: Int = -1,
    val url: String = "",
    val title: String = ""
)

data class LocalToolCallRecord(
    val round: Int,
    val id: String,
    val name: String,
    val arguments: String,
    val output: String
)

/** One model response followed by the tools it requested; content/citations stay local to this round. */
data class WorkStep(
    val round: Int,
    val content: String = "",
    val thinking: String = "",
    val localToolCalls: List<LocalToolCallRecord> = emptyList(),
    val citations: List<CitationPoint> = emptyList()
)

class WorkTimeline {
    var steps: List<WorkStep> = emptyList()
        private set

    fun accept(event: StreamUiEvent) {
        if (event is StreamUiEvent.RoundStarted) {
            steps = steps + WorkStep(event.round)
            return
        }
        val last = steps.lastOrNull() ?: return
        val updated = when (event) {
            is StreamUiEvent.ContentDelta -> last.copy(content = last.content + event.delta)
            is StreamUiEvent.ThinkingDelta -> last.copy(thinking = last.thinking + event.delta)
            is StreamUiEvent.ToolRecord -> last.copy(localToolCalls = last.localToolCalls + event.record)
            else -> return
        }
        steps = steps.dropLast(1) + updated
    }

    fun setCitations(citations: List<CitationPoint>) {
        val last = steps.lastOrNull() ?: return
        steps = steps.dropLast(1) + last.copy(citations = citations)
    }
}

data class WorkspaceFileArtifact(
    val relativePath: String,
    val fileName: String,
    val bytes: Long,
    val operation: String // "fs_write" | "fs_edit" | "workspace"
)

data class ChatMessage(
    val id: String,
    val role: String, // "user" | "assistant"
    val text: String,
    val tags: List<String> = emptyList(),
    val thinking: String = "",
    val sources: List<SearchSource> = emptyList(),
    val citations: List<CitationPoint> = emptyList(),
    val localToolCalls: List<LocalToolCallRecord> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(),
    val usageText: String = "",
    val incompleteNotice: String = "",
    val isError: Boolean = false,
    val workSteps: List<WorkStep> = emptyList()
)

/**
 * Represents a turn in the conversation context history.
 * For DeepSeek 2026-11 tool-use history & prefix cache compliance:
 * - `reasoningContent` is stored on assistant turns so when `tools` is present in subsequent requests,
 *   the engine can include `reasoning_content` on assistant messages as required by DeepSeek specs.
 * - `subturnMessages` stores intermediate `assistant(tool_calls)` (including `reasoning_content`) and `tool` messages.
 */
data class ContextTurn(
    val role: String,
    val content: String,
    val subturnMessages: List<String> = emptyList(),
    val reasoningContent: String = "",
    val attachments: List<ChatAttachment> = emptyList()
)

data class ConversationSession(
    val id: String,
    val title: String,
    val mode: String = "chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val contextHistory: List<ContextTurn> = emptyList()
)

data class EngineTurnResult(
    val ok: Boolean,
    val model: String = "",
    val stage: String = "chat",
    val content: String = "",
    val thinking: String = "",
    val sources: List<SearchSource> = emptyList(),
    val citations: List<CitationPoint> = emptyList(),
    val localToolCalls: List<LocalToolCallRecord> = emptyList(),
    val subturnMessages: List<String> = emptyList(),
    val usageText: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val contextTokens: Int = 0,
    val cumulativePromptTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val serverToolUseCount: Int = 0,
    val finishReason: String = "",
    val rounds: Int = 1,
    val incompleteNotice: String = "",
    val error: String = "",
    val workSteps: List<WorkStep> = emptyList()
)

object CoreProtocol {

    const val DEFAULT_MAX_TOOL_ROUNDS = 50
    const val MAX_TOOL_ROUNDS = 500
    const val DEFAULT_MAX_HISTORY_MESSAGES = 64
    const val MAX_HISTORY_MESSAGES = 1000

    /** Slice whole user/assistant groups; tool subturns remain attached to their assistant. */
    fun recentContext(history: List<ContextTurn>, maxMessages: Int): List<ContextTurn> {
        val count = maxMessages.coerceIn(4, MAX_HISTORY_MESSAGES)
        var start = (history.size - count).coerceAtLeast(0)
        while (start < history.size && history[start].role != "user") start++
        return history.drop(start)
    }

    const val DEFAULT_API_BASE_URL = "https://api.deepseek.com"

    const val DEFAULT_CHAT_SYSTEM_PROMPT =
        "你是 Fluorine（DeepSeek 本地工作台），严谨、直接、重事实的中文技术与研究助手。优先给出可核验结论、关键依据、清晰推导与边界条件。数学公式请使用标准 LaTeX 语法（行内 \$...\$，独立公式块 \$\$...\$\$）。若证据不足或未联网核实，请明确说明不确定性，绝不编造来源、命令输出或实验数据。"

    const val DEFAULT_WORK_SYSTEM_PROMPT =
        "你是 Fluorine（DeepSeek 本地工作台），具备自主规划、取证与本地执行能力的中文技术 Agent。没有固定死板的工作流步骤限制：请根据用户的具体目标，自行判断是否需要拆解计划、是否需要调用联网工具，或使用当前已注入的本地工具（如计算/统计/文本处理、工作区读写、Git、Shell 或设备控制工具）。简单问题直接回答或一步执行；复杂任务自主多轮调用工具验证后再汇总输出。数学公式请使用标准 LaTeX（\$...\$ 或 \$\$...\$\$）。生成或修改文件请优先写入工作区，方便用户直接导出产物。"

    val MODEL_OPTIONS = listOf(
        ModelOption("deepseek-flash", "deepseek-flash · DeepSeek Flash (V4.1-Flash · 多模态 + 联网)"),
        ModelOption("deepseek-v4-pro", "deepseek-v4-pro · DeepSeek V4 Pro (V4-Pro · 深度推理 · 纯文本)")
    )

    val DEFAULT_MODEL_CAPABILITIES = mapOf(
        "deepseek-flash" to ModelCapabilityInfo(
            id = "deepseek-flash",
            name = "DeepSeek-V4.1-Flash",
            contextWindow = 1048576,
            maxOutputTokens = 131072,
            inputModalities = listOf("text", "image")
        ),
        "deepseek-v4-pro" to ModelCapabilityInfo(
            id = "deepseek-v4-pro",
            name = "DeepSeek-V4-Pro",
            contextWindow = 1048576,
            maxOutputTokens = 131072,
            inputModalities = listOf("text")
        )
    )

    fun supportsMultimodal(
        modelId: String,
        discoveredCapabilities: Map<String, ModelCapabilityInfo> = emptyMap()
    ): Boolean {
        val norm = modelId.trim()
        val info = discoveredCapabilities[norm] ?: DEFAULT_MODEL_CAPABILITIES[norm]
        if (info != null && info.inputModalities.isNotEmpty()) {
            return info.inputModalities.any {
                it.equals("image", ignoreCase = true) ||
                    it.equals("file", ignoreCase = true) ||
                    it.equals("vision", ignoreCase = true)
            }
        }
        // Known text-only legacy aliases
        if (norm == "deepseek-v4-pro" || norm == "deepseek-reasoner" || norm == "deepseek-chat") {
            return false
        }
        return true
    }

    val THINKING_MODE_OPTIONS = listOf(
        "auto" to "跟随底部开关",
        "enabled" to "强制开启 (enabled)",
        "disabled" to "强制关闭 (disabled)"
    )

    val EFFORT_OPTIONS = listOf(
        EffortOption("none", "none · 极速无深度思考"),
        EffortOption("low", "low · 轻量推理"),
        EffortOption("medium", "medium -> high · 标准映射高强度"),
        EffortOption("high", "high · 深度推理"),
        EffortOption("max", "max · 极限推理")
    )

    val MAX_TOKEN_OPTIONS = listOf(
        MaxTokenOption(4096, "4096 (快速)"),
        MaxTokenOption(8192, "8192 (标准)"),
        MaxTokenOption(16384, "16384 (推荐)"),
        MaxTokenOption(32768, "32768 (长文)"),
        MaxTokenOption(65536, "65536 (深度研究)"),
        MaxTokenOption(131072, "131072 (超长输出)")
    )

    object AppLogger {
        private const val MAX_ENTRIES = 500
        private val entries = ArrayDeque<String>(MAX_ENTRIES)
        private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        @Synchronized
        fun log(level: String, tag: String, message: String) {
            val ts = fmt.format(Date())
            val line = "[$ts] [$level/$tag] $message"
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }

        fun info(tag: String, message: String) = log("INFO", tag, message)
        fun warn(tag: String, message: String) = log("WARN", tag, message)
        fun error(tag: String, message: String) = log("ERROR", tag, message)

        @Synchronized
        fun dumpText(): String {
            if (entries.isEmpty()) return "暂无运行日志"
            return entries.joinToString("\n")
        }

        @Synchronized
        fun clear() {
            entries.clear()
        }
    }

    fun buildLatestReminder(date: Date = Date(), platform: String = "App"): String {
        val cal = Calendar.getInstance()
        cal.time = date
        val days = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        val y = cal.get(Calendar.YEAR)
        val m = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val d = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val weekday = days[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return "$y-$m-$d,$weekday,Local,$platform,中文"
    }

    fun parseEngineTurnResult(rawJson: String): EngineTurnResult {
        return try {
            val obj = JSONObject(rawJson)
            val ok = obj.optBoolean("ok", false)
            val err = obj.optString("error", "")

            val sources = parseSourcesArray(obj.optJSONArray("sources"))
            val citations = parseCitationsArray(obj.optJSONArray("citations"))
            val toolCalls = parseToolCallsArray(obj.optJSONArray("local_tool_calls"))

            val subturnMessages = mutableListOf<String>()
            val subArr = obj.optJSONArray("subturn_messages") ?: JSONArray()
            for (i in 0 until subArr.length()) {
                val m = subArr.optJSONObject(i) ?: subArr.optString(i, "").takeIf { it.isNotBlank() }
                if (m != null) subturnMessages.add(m.toString())
            }

            val usage = obj.optJSONObject("usage")
            val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
            val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
            val contextTokens = usage?.optInt("context_tokens", 0)
                ?.takeIf { it > 0 } ?: (promptTokens + completionTokens)
            val cumulativePromptTokens = usage?.optInt("cumulative_prompt_tokens", 0)
                ?.takeIf { it > 0 } ?: promptTokens
            val cacheHitTokens = usage?.optInt("prompt_cache_hit_tokens", 0) ?: 0
            val cacheMissTokens = usage?.optInt("prompt_cache_miss_tokens", 0) ?: 0
            val serverToolUse = usage?.optJSONObject("server_tool_use")?.let { stu ->
                stu.optInt("web_search_requests", 0) + stu.optInt("read_url_requests", 0)
            } ?: 0
            val rounds = obj.optInt("rounds", 1)

            val usageText = buildString {
                if (promptTokens > 0 || completionTokens > 0) {
                    append("输入 $promptTokens")
                    if (cacheHitTokens > 0 || cacheMissTokens > 0) {
                        val totalPrompt = (cacheHitTokens + cacheMissTokens).coerceAtLeast(promptTokens).coerceAtLeast(1)
                        val pct = (cacheHitTokens * 100) / totalPrompt
                        append(" (缓存命中 $cacheHitTokens · $pct%)")
                    }
                    append(" · 输出 $completionTokens tokens")
                    if ((rounds > 1 || cumulativePromptTokens > promptTokens) && contextTokens > 0) {
                        append(" · 上下文 $contextTokens")
                        if (cumulativePromptTokens > promptTokens) {
                            append(" (累计输入 $cumulativePromptTokens)")
                        }
                    }
                }
                if (serverToolUse > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("服务端联网调用 $serverToolUse 次")
                }
                if (rounds > 1) {
                    if (isNotEmpty()) append(" · ")
                    append("$rounds 轮工具交互")
                }
            }

            val finishReason = obj.optString("finish_reason", "")
            val incompleteNotice = obj.optString("incomplete_notice", "").ifBlank {
                if (!ok && err.isNotBlank()) err else ""
            }

            if (!ok) {
                AppLogger.error("Engine", "Turn failed/incomplete: $err")
            } else {
                AppLogger.info(
                    "Engine",
                    "Turn ok: model=${obj.optString("model")} rounds=$rounds tools=${toolCalls.size} $usageText"
                )
            }

            EngineTurnResult(
                ok = ok,
                model = obj.optString("model", ""),
                stage = obj.optString("stage", "chat"),
                content = obj.optString("content", ""),
                thinking = obj.optString("thinking", ""),
                sources = sources,
                citations = citations,
                localToolCalls = toolCalls,
                workSteps = parseWorkSteps(obj.optJSONArray("work_steps")),
                subturnMessages = subturnMessages,
                usageText = usageText,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                contextTokens = contextTokens,
                cumulativePromptTokens = cumulativePromptTokens,
                cacheHitTokens = cacheHitTokens,
                cacheMissTokens = cacheMissTokens,
                serverToolUseCount = serverToolUse,
                finishReason = finishReason,
                rounds = rounds,
                incompleteNotice = incompleteNotice,
                error = if (!ok) err.ifBlank { "未知引擎错误" } else ""
            )
        } catch (e: Exception) {
            AppLogger.error("Engine", "Parse turn result error: ${e.message}")
            EngineTurnResult(ok = false, error = "解析响应失败: ${e.message}")
        }
    }

    fun parseSourcesArray(srcArr: JSONArray?): List<SearchSource> {
        if (srcArr == null) return emptyList()
        val sources = mutableListOf<SearchSource>()
        for (i in 0 until srcArr.length()) {
            val s = srcArr.optJSONObject(i) ?: continue
            sources.add(
                SearchSource(
                    title = s.optString("title", "").ifBlank { s.optString("url", "来源 #${i + 1}") },
                    url = s.optString("url", ""),
                    snippet = s.optString("snippet", ""),
                    siteName = s.optString("site_name", ""),
                    publishedAt = s.optString("published_at", ""),
                    citeIndex = if (s.has("cite_index") && !s.isNull("cite_index")) s.optInt("cite_index") else null
                )
            )
        }
        return sources
    }

    fun parseCitationsArray(citArr: JSONArray?): List<CitationPoint> {
        if (citArr == null) return emptyList()
        val citations = mutableListOf<CitationPoint>()
        for (i in 0 until citArr.length()) {
            val c = citArr.optJSONObject(i) ?: continue
            val offset = c.optInt("offset", c.optInt("end_index", -1))
            val startIndex = c.optInt("start_index", -1)
            val endIndex = c.optInt("end_index", -1)
            val url = c.optString("url", "")
            val title = c.optString("title", "")
            val idxArr = c.optJSONArray("indexes")
            val idxList = mutableListOf<Int>()
            if (idxArr != null) {
                for (j in 0 until idxArr.length()) {
                    idxList.add(idxArr.optInt(j))
                }
            }
            if (offset >= 0 && (idxList.isNotEmpty() || url.isNotBlank())) {
                citations.add(
                    CitationPoint(
                        offset = offset,
                        indexes = idxList,
                        startIndex = startIndex,
                        endIndex = endIndex,
                        url = url,
                        title = title
                    )
                )
            }
        }
        return citations
    }

    fun parseToolCallsArray(tcArr: JSONArray?): List<LocalToolCallRecord> {
        if (tcArr == null) return emptyList()
        val toolCalls = mutableListOf<LocalToolCallRecord>()
        for (i in 0 until tcArr.length()) {
            val tc = tcArr.optJSONObject(i) ?: continue
            toolCalls.add(
                LocalToolCallRecord(
                    round = tc.optInt("round", 1),
                    id = tc.optString("id", ""),
                    name = tc.optString("name", ""),
                    arguments = tc.optString("arguments", "{}"),
                    output = tc.optString("output", "")
                )
            )
        }
        return toolCalls
    }

    fun parseWorkSteps(arr: JSONArray?): List<WorkStep> = buildList {
        if (arr != null) for (i in 0 until arr.length()) {
            val step = arr.optJSONObject(i) ?: continue
            add(WorkStep(
                round = step.optInt("round", i + 1),
                content = step.optString("content", ""),
                thinking = step.optString("thinking", ""),
                localToolCalls = parseToolCallsArray(step.optJSONArray("local_tool_calls")),
                citations = parseCitationsArray(step.optJSONArray("citations"))
            ))
        }
    }

    fun serializeWorkSteps(steps: List<WorkStep>): JSONArray = JSONArray().apply {
        for (step in steps) put(JSONObject()
            .put("round", step.round)
            .put("content", step.content)
            .put("thinking", step.thinking)
            .put("local_tool_calls", JSONArray().apply {
                for (tc in step.localToolCalls) put(JSONObject()
                    .put("round", tc.round).put("id", tc.id).put("name", tc.name)
                    .put("arguments", tc.arguments).put("output", tc.output))
            })
            .put("citations", JSONArray().apply {
                for (c in step.citations) put(JSONObject()
                    .put("offset", c.offset).put("indexes", JSONArray(c.indexes))
                    .put("start_index", c.startIndex).put("end_index", c.endIndex)
                    .put("url", c.url).put("title", c.title))
            }))
    }

    /**
     * Extracts workspace file artifacts generated or modified by `fs_write` or `fs_edit`
     * from a message's local tool calls so the UI can render rounded-corner export cards.
     */
    fun extractWorkspaceArtifacts(toolCalls: List<LocalToolCallRecord>): List<WorkspaceFileArtifact> {
        val map = linkedMapOf<String, WorkspaceFileArtifact>()
        for (tc in toolCalls) {
            if (tc.name != "fs_write" && tc.name != "fs_edit") continue
            val outObj = runCatching { JSONObject(tc.output) }.getOrNull() ?: continue
            if (!outObj.optBoolean("ok", false)) continue

            val argObj = runCatching { JSONObject(tc.arguments) }.getOrNull()
            val rawPath = outObj.optString("path", "").ifBlank {
                argObj?.optString("path", "").orEmpty()
            }.trim().removePrefix("./")
            if (rawPath.isBlank()) continue

            val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
            val bytes = outObj.optLong("bytes", -1L).let { b ->
                if (b >= 0L) b
                else argObj?.optString("content", "")?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
            }
            map[rawPath] = WorkspaceFileArtifact(
                relativePath = rawPath,
                fileName = fileName,
                bytes = bytes,
                operation = tc.name
            )
        }
        return map.values.toList()
    }

    /**
     * Lists user-generated files inside the app's workspace directory,
     * rejecting symlinks whose canonical target escapes `workspaceRoot`.
     */
    fun listWorkspaceFiles(workspaceRoot: File): List<WorkspaceFileArtifact> {
        if (!workspaceRoot.exists() || !workspaceRoot.isDirectory) return emptyList()
        val rootCanonical = runCatching { workspaceRoot.canonicalFile }.getOrNull() ?: return emptyList()
        val rootPrefix = rootCanonical.path
        val results = mutableListOf<WorkspaceFileArtifact>()

        fun insideRoot(f: File): Boolean {
            val cPath = runCatching { f.canonicalFile.path }.getOrNull() ?: return false
            return cPath == rootPrefix || cPath.startsWith(rootPrefix + File.separator)
        }

        workspaceRoot.walkTopDown()
            .onEnter { dir ->
                dir.name != ".git" && dir.name != "target" && dir.name != "build" && insideRoot(dir)
            }
            .forEach { f ->
                if (f.isFile && results.size < 200 && insideRoot(f)) {
                    val cPath = runCatching { f.canonicalFile.path }.getOrDefault(f.path)
                    if (cPath.startsWith(rootPrefix + File.separator)) {
                        val rel = cPath.substring(rootPrefix.length + 1).replace('\\', '/')
                        results.add(
                            WorkspaceFileArtifact(
                                relativePath = rel,
                                fileName = f.name,
                                bytes = f.length(),
                                operation = "workspace"
                            )
                        )
                    }
                }
            }
        return results.sortedBy { it.relativePath }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun hostOf(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun defaultSessionTitleForMode(mode: String): String =
        if (mode.trim().lowercase(Locale.ROOT) == "work") "新 Work 任务" else "新对话"

    fun isUntouchedBlankSession(session: ConversationSession): Boolean {
        if (session.chatMessages.isNotEmpty() || session.contextHistory.isNotEmpty()) return false
        val t = session.title.trim()
        return t.isEmpty() || t == "新对话" || t == "新 Work 任务" || t == "默认会话" || t == "未命名会话"
    }

    /**
     * Normalizes restored sessions from disk:
     * - Deduplicates by session ID while preserving order.
     * - Drops accumulated untouched blank sessions when at least one non-empty (or user-renamed) session exists.
     * - Collapses multiple untouched blank sessions down to a single session when all sessions are blank.
     * - Resolves the active session ID to `savedActiveId` if present, otherwise the most recently updated session.
     */
    fun normalizeRestoredSessions(
        sessions: List<ConversationSession>,
        savedActiveId: String?
    ): Pair<List<ConversationSession>, String?> {
        if (sessions.isEmpty()) return emptyList<ConversationSession>() to null
        val deduped = sessions.distinctBy { it.id }
        val nonBlank = deduped.filterNot { isUntouchedBlankSession(it) }
        val cleaned = if (nonBlank.isNotEmpty()) {
            nonBlank
        } else {
            val preferredBlank = deduped.firstOrNull { it.id == savedActiveId } ?: deduped.first()
            listOf(preferredBlank)
        }
        val resolvedActiveId = cleaned.firstOrNull { it.id == savedActiveId }?.id
            ?: cleaned.maxByOrNull { it.updatedAt }?.id
            ?: cleaned.first().id
        return cleaned to resolvedActiveId
    }

    fun serializeSessionsStore(
        sessions: List<ConversationSession>,
        activeSessionId: String
    ): String {
        val (cleanedSessions, resolvedActiveId) = normalizeRestoredSessions(sessions, activeSessionId)
        val root = JSONObject()
        root.put("version", 3)
        root.put("active_session_id", resolvedActiveId ?: activeSessionId)
        val sessionsArr = JSONArray()
        for (s in cleanedSessions) {
            sessionsArr.put(serializeSingleSessionObject(s))
        }
        root.put("sessions", sessionsArr)
        return root.toString()
    }

    fun deserializeSessionsStore(rawJson: String): Pair<List<ConversationSession>, String?> {
        if (rawJson.isBlank()) return emptyList<ConversationSession>() to null
        return try {
            val root = JSONObject(rawJson)
            val activeId = root.optString("active_session_id", "").takeIf { it.isNotBlank() }
            val sessionsArr = root.optJSONArray("sessions") ?: JSONArray()
            val sessions = mutableListOf<ConversationSession>()
            for (i in 0 until sessionsArr.length()) {
                val obj = sessionsArr.optJSONObject(i) ?: continue
                sessions.add(deserializeSingleSessionObject(obj))
            }
            normalizeRestoredSessions(sessions, activeId)
        } catch (_: Exception) {
            emptyList<ConversationSession>() to null
        }
    }

    private fun serializeAttachments(list: List<ChatAttachment>): JSONArray {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("mime_type", a.mimeType)
                    .put("kind", a.kind)
                    .put("local_path", a.localPath)
                    .put("size_bytes", a.sizeBytes)
                    .put("file_id", a.fileId)
                    .put("parse_purpose", a.parsePurpose)
            )
        }
        return arr
    }

    private fun deserializeAttachments(arr: JSONArray?): List<ChatAttachment> {
        if (arr == null) return emptyList()
        val list = mutableListOf<ChatAttachment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                ChatAttachment(
                    id = o.optString("id", "att_$i"),
                    name = o.optString("name", "file"),
                    mimeType = o.optString("mime_type", "application/octet-stream"),
                    kind = o.optString("kind", "document"),
                    localPath = o.optString("local_path", ""),
                    sizeBytes = o.optLong("size_bytes", 0L),
                    fileId = o.optString("file_id", ""),
                    parsePurpose = o.optString("parse_purpose", "user_data")
                )
            )
        }
        return list
    }

    private fun serializeSingleSessionObject(session: ConversationSession): JSONObject {
        val obj = JSONObject()
        obj.put("id", session.id)
        obj.put("title", session.title)
        obj.put("mode", session.mode)
        obj.put("created_at", session.createdAt)
        obj.put("updated_at", session.updatedAt)

        val msgArr = JSONArray()
        for (m in session.chatMessages) {
            val mObj = JSONObject()
                .put("id", m.id)
                .put("role", m.role)
                .put("text", m.text)
                .put("tags", JSONArray(m.tags))
                .put("thinking", m.thinking)
                .put("usageText", m.usageText)
                .put("incompleteNotice", m.incompleteNotice)
                .put("isError", m.isError)
                .put("work_steps", serializeWorkSteps(m.workSteps))

            val srcArr = JSONArray()
            for (s in m.sources) {
                srcArr.put(
                    JSONObject()
                        .put("title", s.title)
                        .put("url", s.url)
                        .put("snippet", s.snippet)
                        .put("site_name", s.siteName)
                        .put("published_at", s.publishedAt)
                        .put("cite_index", s.citeIndex)
                )
            }
            mObj.put("sources", srcArr)

            val citArr = JSONArray()
            for (c in m.citations) {
                citArr.put(
                    JSONObject()
                        .put("offset", c.offset)
                        .put("indexes", JSONArray(c.indexes))
                        .put("start_index", c.startIndex)
                        .put("end_index", c.endIndex)
                        .put("url", c.url)
                        .put("title", c.title)
                )
            }
            mObj.put("citations", citArr)

            val tcArr = JSONArray()
            for (tc in m.localToolCalls) {
                tcArr.put(
                    JSONObject()
                        .put("round", tc.round)
                        .put("id", tc.id)
                        .put("name", tc.name)
                        .put("arguments", tc.arguments)
                        .put("output", tc.output)
                )
            }
            mObj.put("localToolCalls", tcArr)
            if (m.attachments.isNotEmpty()) {
                mObj.put("attachments", serializeAttachments(m.attachments))
            }
            msgArr.put(mObj)
        }
        obj.put("chatMessages", msgArr)

        val ctxArr = JSONArray()
        for (c in session.contextHistory) {
            val cObj = JSONObject()
                .put("role", c.role)
                .put("content", c.content)
            if (c.reasoningContent.isNotEmpty()) {
                cObj.put("reasoning_content", c.reasoningContent)
            }
            if (c.subturnMessages.isNotEmpty()) {
                cObj.put("subturn_messages", JSONArray(c.subturnMessages))
            }
            if (c.attachments.isNotEmpty()) {
                cObj.put("attachments", serializeAttachments(c.attachments))
            }
            ctxArr.put(cObj)
        }
        obj.put("contextHistory", ctxArr)
        return obj
    }

    private fun deserializeSingleSessionObject(obj: JSONObject): ConversationSession {
        val msgList = mutableListOf<ChatMessage>()
        val msgArr = obj.optJSONArray("chatMessages") ?: JSONArray()
        for (i in 0 until msgArr.length()) {
            val m = msgArr.optJSONObject(i) ?: continue
            val tags = mutableListOf<String>()
            val tArr = m.optJSONArray("tags") ?: JSONArray()
            for (j in 0 until tArr.length()) tags.add(tArr.optString(j))

            val sources = parseSourcesArray(m.optJSONArray("sources"))
            val citations = parseCitationsArray(m.optJSONArray("citations"))
            val toolCalls = parseToolCallsArray(m.optJSONArray("localToolCalls"))
            val attachments = deserializeAttachments(m.optJSONArray("attachments"))

            msgList.add(
                ChatMessage(
                    id = m.optString("id", "msg_$i"),
                    role = m.optString("role", "assistant"),
                    text = m.optString("text", ""),
                    tags = tags,
                    thinking = m.optString("thinking", ""),
                    sources = sources,
                    citations = citations,
                    localToolCalls = toolCalls,
                    workSteps = parseWorkSteps(m.optJSONArray("work_steps")),
                    attachments = attachments,
                    usageText = m.optString("usageText", ""),
                    incompleteNotice = m.optString("incompleteNotice", ""),
                    isError = m.optBoolean("isError", false)
                )
            )
        }

        val ctxList = mutableListOf<ContextTurn>()
        val ctxArr = obj.optJSONArray("contextHistory") ?: JSONArray()
        for (i in 0 until ctxArr.length()) {
            val c = ctxArr.optJSONObject(i) ?: continue
            val subturns = mutableListOf<String>()
            val subArr = c.optJSONArray("subturn_messages")
            if (subArr != null) {
                for (j in 0 until subArr.length()) {
                    val s = subArr.optString(j, "")
                    if (s.isNotBlank()) subturns.add(s)
                }
            }
            ctxList.add(
                ContextTurn(
                    role = c.optString("role", "user"),
                    content = c.optString("content", ""),
                    subturnMessages = subturns,
                    reasoningContent = c.optString("reasoning_content", ""),
                    attachments = deserializeAttachments(c.optJSONArray("attachments"))
                )
            )
        }

        val now = System.currentTimeMillis()
        return ConversationSession(
            id = obj.optString("id", "session_$now"),
            title = obj.optString("title", "未命名会话"),
            mode = obj.optString("mode", "chat"),
            createdAt = obj.optLong("created_at", now),
            updatedAt = obj.optLong("updated_at", now),
            chatMessages = msgList,
            contextHistory = ctxList
        )
    }

    fun serializeSession(
        chatMessages: List<ChatMessage>,
        contextHistory: List<ContextTurn>
    ): String {
        val tempSession = ConversationSession(
            id = "default",
            title = "默认会话",
            chatMessages = chatMessages,
            contextHistory = contextHistory
        )
        return serializeSingleSessionObject(tempSession).toString()
    }

    fun deserializeSession(rawJson: String): Pair<List<ChatMessage>, List<ContextTurn>> {
        if (rawJson.isBlank()) return emptyList<ChatMessage>() to emptyList()
        return try {
            val s = deserializeSingleSessionObject(JSONObject(rawJson))
            s.chatMessages to s.contextHistory
        } catch (_: Exception) {
            emptyList<ChatMessage>() to emptyList()
        }
    }
}
