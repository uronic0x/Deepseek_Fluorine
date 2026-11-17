package com.uronic0x.fluorine.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

enum class WorkMode(val id: String) {
    CHAT("chat"),
    WORK("work")
}

data class ModelOption(
    val id: String,
    val label: String,
    val shortLabel: String,
    val supportsWeb: Boolean
)

val MODEL_OPTIONS = listOf(
    ModelOption(
        id = "deepseek-flash",
        label = "DeepSeek Flash (V4.1-Flash-1017) · 支持官方联网与本地工具",
        shortLabel = "DeepSeek Flash",
        supportsWeb = true
    ),
    ModelOption(
        id = "deepseek-v4-pro",
        label = "DeepSeek V4 Pro (V4-Pro-0813) · 支持本地工具（不支持官方联网）",
        shortLabel = "DeepSeek V4 Pro",
        supportsWeb = false
    )
)

data class EffortOption(
    val id: String,
    val label: String
)

val EFFORT_OPTIONS = listOf(
    EffortOption("none", "关闭 · 更快"),
    EffortOption("low", "Low"),
    EffortOption("high", "High (推荐)"),
    EffortOption("max", "Max · 更多时间与用量")
)

data class BudgetOption(
    val tokens: Int,
    val label: String
)

val BUDGET_OPTIONS = listOf(
    BudgetOption(8192, "8,192 tokens"),
    BudgetOption(16384, "16,384 tokens (默认)"),
    BudgetOption(32768, "32,768 tokens"),
    BudgetOption(65536, "65,536 tokens (64K 思考默认)"),
    BudgetOption(131072, "131,072 tokens (128K)"),
    BudgetOption(393216, "393,216 tokens (384K 上限)")
)

data class CitationPoint(
    val startIndex: Int,
    val endIndex: Int,
    val url: String,
    val title: String,
    val number: Int
)

data class SourceLink(
    val url: String,
    val title: String
)

data class ToolCallRecord(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String
)

data class TokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cacheHitTokens: Long = 0,
    val cacheMissTokens: Long? = null,
    val reasoningTokens: Long = 0,
    val toolSearchCount: Int = 0,
    val toolImageSearchCount: Int = 0,
    val toolFetchCount: Int = 0
)

data class ProtocolMessage(
    val role: String,
    val content: String = "",
    val reasoningContent: String = "",
    val serverToolCallsJson: JSONArray? = null,
    val annotationsJson: JSONArray? = null,
    val localToolCalls: List<ToolCallRecord> = emptyList()
)

data class UiChatMessage(
    val id: Long,
    val role: String, // "user", "assistant", "error"
    val content: String,
    val reasoningContent: String = "",
    val progressText: String? = null,
    val citations: List<CitationPoint> = emptyList(),
    val sources: List<SourceLink> = emptyList(),
    val localToolCalls: List<ToolCallRecord> = emptyList(),
    val usageText: String? = null,
    val incompleteNote: String? = null
)

data class WorkReport(
    val prompt: String,
    val mode: String,
    val model: String,
    val effort: String,
    val web: Boolean,
    val localTools: Boolean,
    val deviceTools: Boolean,
    val plan: String = "",
    val toolsLog: String = "",
    val review: String = "",
    val answer: ProtocolMessage? = null,
    val usages: List<TokenUsage?> = emptyList()
)

data class SessionSnapshot(
    val convTitle: String = "Fluorine",
    val mode: WorkMode = WorkMode.CHAT,
    val messages: List<UiChatMessage> = emptyList(),
    val history: List<ProtocolMessage> = emptyList(),
    val reports: List<WorkReport> = emptyList(),
    val showTaskPanel: Boolean = false,
    val activeWorkStep: Int = -1,
    val taskStatusText: String = "准备开始",
    val planText: String = "",
    val toolsLogText: String = "",
    val reviewText: String = ""
)

class ContentFilterException(message: String) : Exception(message)
class StreamAbortedException(message: String = "已停止本地接收；上游可能仍有已计费用量。") : Exception(message)

/**
 * 1:1 Kotlin port of `web/core.js` using Android platform `org.json` (zero third-party JSON dependencies).
 */
object CoreProtocol {

    fun safeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if ((scheme == "http" || scheme == "https") && uri.userInfo == null && !uri.host.isNullOrBlank()) {
                uri.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun slugifyAppName(name: String? = "app"): String {
        val raw = (name ?: "app").trim().lowercase(Locale.ROOT)
        val slug = raw
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
        return slug.ifEmpty { "app" }
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

    fun sanitizeHistoryMessage(
        msg: ProtocolMessage,
        keepServerToolCalls: Boolean = false
    ): JSONObject {
        val out = JSONObject()
        out.put("role", msg.role)
        out.put("content", msg.content)
        if (msg.role == "assistant" &&
            keepServerToolCalls &&
            msg.serverToolCallsJson != null &&
            msg.serverToolCallsJson.length() > 0
        ) {
            out.put("server_tool_calls", JSONArray(msg.serverToolCallsJson.toString()))
        }
        return out
    }

    fun formatServerToolEvent(event: JSONObject?): String? {
        if (event == null || event.optString("type") != "call_started") return null
        val action = event.optJSONObject("call")?.optJSONObject("action") ?: JSONObject()
        val actionType = action.optString("type")
        if (actionType == "search") {
            val queriesArr = action.optJSONArray("queries")
            if (queriesArr != null && queriesArr.length() > 0) {
                val list = mutableListOf<String>()
                for (i in 0 until minOf(2, queriesArr.length())) {
                    val q = queriesArr.optString(i)
                    if (q.isNotBlank()) list.add(q)
                }
                if (list.isNotEmpty()) {
                    return "正在联网搜索：${list.joinToString(" · ")}"
                }
            }
        }
        if ((actionType == "open_page" || actionType == "read_link")) {
            val url = action.optString("url")
            if (url.isNotBlank()) return "正在读取页面：$url"
        }
        if (actionType == "find_in_page") {
            val pattern = action.optString("pattern")
            if (pattern.isNotBlank()) return "正在页内查找：$pattern"
        }
        return "正在联网：${actionType.ifBlank { "检索" }}"
    }

    fun citationPoints(message: ProtocolMessage): List<CitationPoint> {
        val codePointsCount = message.content.codePointCount(0, message.content.length)
        val annotations = message.annotationsJson ?: return emptyList()
        val points = mutableListOf<CitationPoint>()
        for (i in 0 until annotations.length()) {
            val a = annotations.optJSONObject(i) ?: continue
            if (a.optString("type") != "url_citation") continue
            val c = a.optJSONObject("url_citation") ?: continue
            if (!c.has("start_index") || !c.has("end_index")) continue
            val startIdx = c.optInt("start_index", -1)
            val endIdx = c.optInt("end_index", -1)
            val url = safeUrl(c.optString("url")) ?: continue
            if (startIdx < 0 || startIdx >= endIdx || endIdx > codePointsCount) continue
            val title = c.optString("title").ifBlank { url }
            points.add(
                CitationPoint(
                    startIndex = startIdx,
                    endIndex = endIdx,
                    url = url,
                    title = title,
                    number = i + 1
                )
            )
        }
        return points.sortedBy { it.endIndex }
    }

    fun sourceList(message: ProtocolMessage?): List<SourceLink> {
        if (message == null) return emptyList()
        val found = LinkedHashMap<String, SourceLink>()
        fun addSource(obj: JSONObject?) {
            if (obj == null) return
            val url = safeUrl(obj.optString("url")) ?: return
            if (!found.containsKey(url)) {
                val hostFallback = try {
                    URI(url).host ?: url
                } catch (_: Exception) {
                    url
                }
                val title = obj.optString("title").ifBlank { hostFallback }
                found[url] = SourceLink(url = url, title = title)
            }
        }

        val annotations = message.annotationsJson
        if (annotations != null) {
            for (i in 0 until annotations.length()) {
                val a = annotations.optJSONObject(i) ?: continue
                if (a.optString("type") == "url_citation") {
                    addSource(a.optJSONObject("url_citation"))
                }
            }
        }
        val stc = message.serverToolCallsJson
        if (stc != null) {
            for (i in 0 until stc.length()) {
                val step = stc.optJSONObject(i) ?: continue
                val calls = step.optJSONArray("calls") ?: continue
                for (j in 0 until calls.length()) {
                    val call = calls.optJSONObject(j) ?: continue
                    val sources = call.optJSONArray("sources")
                    if (sources != null) {
                        for (k in 0 until sources.length()) {
                            addSource(sources.optJSONObject(k))
                        }
                    }
                    addSource(call.optJSONObject("page"))
                }
            }
        }
        return found.values.toList()
    }

    fun isPeakUtc(date: Date = Date()): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.time = date
        val day = cal.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SUNDAY || day == Calendar.SATURDAY) return false
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return (h in 1..3) || (h in 6..9)
    }

    fun parseUsage(u: JSONObject?): TokenUsage? {
        if (u == null) return null
        val prompt = if (u.has("prompt_tokens")) u.optLong("prompt_tokens", 0)
        else u.optLong("input_tokens", 0)
        val hit = if (u.has("prompt_cache_hit_tokens")) u.optLong("prompt_cache_hit_tokens", 0)
        else u.optLong("cache_read_input_tokens", 0)
        val miss = if (u.has("prompt_cache_miss_tokens") && !u.isNull("prompt_cache_miss_tokens")) {
            u.optLong("prompt_cache_miss_tokens", max(0L, prompt - hit))
        } else {
            null
        }
        val out = if (u.has("completion_tokens")) u.optLong("completion_tokens", 0)
        else u.optLong("output_tokens", 0)
        val reasoning = u.optJSONObject("completion_tokens_details")?.optLong("reasoning_tokens", 0) ?: 0
        val tools = u.optJSONObject("server_tool_use")
        return TokenUsage(
            promptTokens = prompt,
            completionTokens = out,
            cacheHitTokens = hit,
            cacheMissTokens = miss,
            reasoningTokens = reasoning,
            toolSearchCount = tools?.optInt("tool_search", 0) ?: 0,
            toolImageSearchCount = tools?.optInt("tool_image_search", 0) ?: 0,
            toolFetchCount = tools?.optInt("tool_fetch", 0) ?: 0
        )
    }

    fun estimateCostUsd(model: String, u: TokenUsage?, date: Date = Date()): Double? {
        if (u == null) return null
        val peak = if (isPeakUtc(date)) 2.0 else 1.0
        val hitRate: Double
        val missRate: Double
        val outRate: Double
        if (model == "deepseek-v4-pro") {
            hitRate = 0.022 * peak
            missRate = 0.66 * peak
            outRate = 1.98 * peak
        } else {
            hitRate = 0.003 * peak
            missRate = 0.15 * peak
            outRate = 0.6 * peak
        }
        val hit = u.cacheHitTokens
        val miss = u.cacheMissTokens ?: max(0L, u.promptTokens - hit)
        val out = u.completionTokens
        val webCost = (u.toolSearchCount + u.toolImageSearchCount + u.toolFetchCount) * 0.00075
        val tokenCost = (hit * hitRate + miss * missRate + out * outRate) / 1_000_000.0
        return tokenCost + webCost
    }

    fun usageText(u: TokenUsage?, model: String = "deepseek-flash"): String {
        if (u == null) return "上游未返回完整用量"
        val cost = estimateCostUsd(model, u)
        val costStr = if (cost != null) String.format(Locale.US, " · 约 $%.5f", cost) else ""
        return "输入 ${u.promptTokens} · 输出 ${u.completionTokens} tokens · 搜索 ${u.toolSearchCount} · 读页 ${u.toolFetchCount}$costStr"
    }

    fun clipText(s: String, maxLen: Int = 72): String {
        return if (s.length > maxLen) s.take(maxLen - 1) + "…" else s
    }

    private fun mergeServerToolCalls(existing: JSONArray?, incoming: JSONArray): JSONArray {
        if (existing == null) return JSONArray(incoming.toString())
        val byIndex = sortedMapOf<Int, JSONObject>()
        val unindexed = mutableListOf<Any>()
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i)
            if (item != null && item.has("index") && !item.isNull("index")) {
                byIndex[item.optInt("index")] = item
            } else if (item != null) {
                unindexed.add(item)
            }
        }
        for (i in 0 until incoming.length()) {
            val item = incoming.optJSONObject(i)
            if (item != null && item.has("index") && !item.isNull("index")) {
                byIndex[item.optInt("index")] = item
            } else if (item != null) {
                unindexed.add(item)
            }
        }
        val out = JSONArray()
        for ((_, v) in byIndex) out.put(v)
        for (u in unindexed) out.put(u)
        return out
    }

    data class StreamUpdate(
        val message: ProtocolMessage,
        val usage: TokenUsage?,
        val title: String?,
        val serverToolEvent: JSONObject?,
        val localToolEvent: JSONObject?,
        val thinking: Boolean
    )

    data class StreamResult(
        val message: ProtocolMessage,
        val usage: TokenUsage?,
        val title: String?
    )

    class SseAccumulator(
        private val onUpdate: (StreamUpdate) -> Unit = {}
    ) {
        private var contentBuilder = StringBuilder()
        private var reasoningBuilder = StringBuilder()
        private var serverToolCalls: JSONArray? = null
        private var annotations: JSONArray? = null
        private var localToolCalls: List<ToolCallRecord> = emptyList()
        private var finishReason: String? = null
        private var usage: TokenUsage? = null
        private var title: String? = null
        private var done: Boolean = false

        private fun currentMessage(): ProtocolMessage {
            return ProtocolMessage(
                role = "assistant",
                content = contentBuilder.toString(),
                reasoningContent = reasoningBuilder.toString(),
                serverToolCallsJson = serverToolCalls,
                annotationsJson = annotations,
                localToolCalls = localToolCalls
            )
        }

        fun pushFrame(rawData: String) {
            val trimmed = rawData.trim()
            if (trimmed.isEmpty()) return
            if (trimmed == "[DONE]") {
                done = true
                return
            }
            val chunk = try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                throw IllegalStateException("上游事件 JSON 格式错误。")
            }
            pushJson(chunk)
        }

        fun pushJson(chunk: JSONObject) {
            val errObj = chunk.optJSONObject("error")
            if (errObj != null) {
                val msg = errObj.optString("message").ifBlank {
                    "上游流式生成失败；已发生的用量仍可能计费。"
                }
                throw IllegalStateException(msg)
            }
            if (chunk.has("usage") && !chunk.isNull("usage")) {
                usage = parseUsage(chunk.optJSONObject("usage"))
            }
            val choices = chunk.optJSONArray("choices")
            val choice = if (choices != null && choices.length() > 0) choices.optJSONObject(0) else null
            val localEvent = chunk.optJSONObject("local_tool_event")
            if (choice == null) {
                onUpdate(
                    StreamUpdate(
                        message = currentMessage(),
                        usage = usage,
                        title = title,
                        serverToolEvent = null,
                        localToolEvent = localEvent,
                        thinking = false
                    )
                )
                return
            }

            val delta = choice.optJSONObject("delta") ?: JSONObject()
            if (delta.has("content") && !delta.isNull("content")) {
                contentBuilder.append(delta.optString("content"))
            }
            val hasReasoningDelta = delta.has("reasoning_content") && !delta.isNull("reasoning_content") &&
                delta.optString("reasoning_content").isNotEmpty()
            if (hasReasoningDelta) {
                reasoningBuilder.append(delta.optString("reasoning_content"))
            }
            if (delta.has("title") && !delta.isNull("title")) {
                val t = delta.optString("title")
                if (t.isNotBlank()) title = t
            }
            val stcDelta = delta.optJSONArray("server_tool_calls")
            if (stcDelta != null) {
                serverToolCalls = mergeServerToolCalls(serverToolCalls, stcDelta)
            }
            val annDelta = delta.optJSONArray("annotations")
            if (annDelta != null) {
                val target = annotations ?: JSONArray()
                for (i in 0 until annDelta.length()) {
                    target.put(annDelta.get(i))
                }
                annotations = target
            }
            val ltcDelta = delta.optJSONArray("local_tool_calls")
            if (ltcDelta != null) {
                val list = mutableListOf<ToolCallRecord>()
                for (i in 0 until ltcDelta.length()) {
                    val obj = ltcDelta.optJSONObject(i) ?: continue
                    list.add(
                        ToolCallRecord(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            arguments = obj.optString("arguments"),
                            result = obj.optString("result")
                        )
                    )
                }
                localToolCalls = list
            }

            val serverEvent = choice.optJSONObject("server_tool_event")
            if (serverEvent?.optString("type") == "step_reasoning_done" &&
                reasoningBuilder.isNotEmpty() &&
                !reasoningBuilder.endsWith("\n")
            ) {
                reasoningBuilder.append("\n")
            }

            if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                val fr = choice.optString("finish_reason")
                if (fr.isNotBlank()) finishReason = fr
            }

            if (contentBuilder.length + reasoningBuilder.length > 2 * 1024 * 1024) {
                throw IllegalStateException("单条回复过大，已停止接收。")
            }

            onUpdate(
                StreamUpdate(
                    message = currentMessage(),
                    usage = usage,
                    title = title,
                    serverToolEvent = serverEvent,
                    localToolEvent = localEvent,
                    thinking = hasReasoningDelta
                )
            )
        }

        fun result(): StreamResult {
            if (!done || finishReason.isNullOrBlank()) {
                throw IllegalStateException("事件流中断，回复未完成；请手动重试。")
            }
            if (finishReason == "content_filter") {
                throw ContentFilterException("上游内容审查拒绝了本轮回复。")
            }
            if (finishReason != "stop") {
                val reasonMsg = when (finishReason) {
                    "length" -> "输出预算耗尽，当前回复未完成。请提高预算后重试。"
                    "insufficient_system_resource" -> "上游推理资源暂时不足，请稍后手动重试。"
                    "aborted" -> "上游生成过程被中断。"
                    else -> "异常结束状态：$finishReason"
                }
                throw IllegalStateException(reasonMsg)
            }
            val msg = currentMessage()
            if (msg.content.trim().isEmpty()) {
                throw IllegalStateException("上游未返回正文；不会将思考内容冒充答案。")
            }
            return StreamResult(
                message = msg,
                usage = usage,
                title = title
            )
        }
    }

    fun buildMarkdownReport(reports: List<WorkReport>): String {
        return reports.joinToString("\n---\n\n") { r ->
            val sb = StringBuilder()
            sb.append("# ${r.prompt}\n\n")
            sb.append("模式：${r.mode} | 模型：${r.model} | effort：${r.effort} | 联网：${r.web} | 本地工具：${r.localTools} | 设备/Shell：${r.deviceTools}\n")
            if (r.plan.isNotBlank()) {
                sb.append("\n## 计划\n\n${r.plan}\n")
            }
            if (r.toolsLog.isNotBlank()) {
                sb.append("\n## 本地工具记录\n\n```\n${r.toolsLog.trim()}\n```\n")
            }
            sb.append("\n## 交付物\n\n${r.answer?.content ?: ""}\n")
            val sources = sourceList(r.answer)
            sb.append("\n## 来源\n\n")
            if (sources.isNotEmpty()) {
                sb.append(
                    sources.joinToString("\n") { s ->
                        val cleanTitle = s.title.replace("[", "").replace("]", "")
                        "- [$cleanTitle](${s.url})"
                    }
                )
                sb.append("\n")
            }
            if (r.review.isNotBlank()) {
                sb.append("\n## 文本审查（非独立联网核查）\n\n${r.review}\n")
            }
            sb.append("\n## 用量\n\n")
            sb.append(
                r.usages.mapIndexed { idx, u ->
                    "- 第 ${idx + 1} 次：${usageText(u, r.model)}"
                }.joinToString("\n")
            )
            sb.append("\n")
            sb.toString()
        }
    }

    private fun toolCallToJson(tc: ToolCallRecord): JSONObject =
        JSONObject()
            .put("id", tc.id)
            .put("name", tc.name)
            .put("arguments", tc.arguments)
            .put("result", tc.result)

    private fun jsonToToolCall(obj: JSONObject): ToolCallRecord =
        ToolCallRecord(
            id = obj.optString("id"),
            name = obj.optString("name"),
            arguments = obj.optString("arguments"),
            result = obj.optString("result")
        )

    private fun protocolMessageToJson(msg: ProtocolMessage): JSONObject {
        val obj = JSONObject()
            .put("role", msg.role)
            .put("content", msg.content)
            .put("reasoningContent", msg.reasoningContent)
        if (msg.serverToolCallsJson != null) {
            obj.put("serverToolCallsJson", msg.serverToolCallsJson)
        }
        if (msg.annotationsJson != null) {
            obj.put("annotationsJson", msg.annotationsJson)
        }
        if (msg.localToolCalls.isNotEmpty()) {
            val arr = JSONArray()
            msg.localToolCalls.forEach { arr.put(toolCallToJson(it)) }
            obj.put("localToolCalls", arr)
        }
        return obj
    }

    private fun jsonToProtocolMessage(obj: JSONObject): ProtocolMessage {
        val ltcArr = obj.optJSONArray("localToolCalls")
        val ltc = mutableListOf<ToolCallRecord>()
        if (ltcArr != null) {
            for (i in 0 until ltcArr.length()) {
                ltcArr.optJSONObject(i)?.let { ltc.add(jsonToToolCall(it)) }
            }
        }
        return ProtocolMessage(
            role = obj.optString("role", "user"),
            content = obj.optString("content", ""),
            reasoningContent = obj.optString("reasoningContent", ""),
            serverToolCallsJson = obj.optJSONArray("serverToolCallsJson"),
            annotationsJson = obj.optJSONArray("annotationsJson"),
            localToolCalls = ltc
        )
    }

    private fun usageToJson(u: TokenUsage?): JSONObject? {
        if (u == null) return null
        val obj = JSONObject()
            .put("promptTokens", u.promptTokens)
            .put("completionTokens", u.completionTokens)
            .put("cacheHitTokens", u.cacheHitTokens)
            .put("reasoningTokens", u.reasoningTokens)
            .put("toolSearchCount", u.toolSearchCount)
            .put("toolImageSearchCount", u.toolImageSearchCount)
            .put("toolFetchCount", u.toolFetchCount)
        if (u.cacheMissTokens != null) {
            obj.put("cacheMissTokens", u.cacheMissTokens)
        }
        return obj
    }

    private fun jsonToUsage(obj: JSONObject?): TokenUsage? {
        if (obj == null) return null
        return TokenUsage(
            promptTokens = obj.optLong("promptTokens", 0L),
            completionTokens = obj.optLong("completionTokens", 0L),
            cacheHitTokens = obj.optLong("cacheHitTokens", 0L),
            cacheMissTokens = if (obj.has("cacheMissTokens") && !obj.isNull("cacheMissTokens")) {
                obj.optLong("cacheMissTokens")
            } else null,
            reasoningTokens = obj.optLong("reasoningTokens", 0L),
            toolSearchCount = obj.optInt("toolSearchCount", 0),
            toolImageSearchCount = obj.optInt("toolImageSearchCount", 0),
            toolFetchCount = obj.optInt("toolFetchCount", 0)
        )
    }

    fun serializeSession(snapshot: SessionSnapshot): String {
        val root = JSONObject()
        root.put("convTitle", snapshot.convTitle)
        root.put("mode", snapshot.mode.id)
        root.put("showTaskPanel", snapshot.showTaskPanel)
        root.put("activeWorkStep", snapshot.activeWorkStep)
        root.put("taskStatusText", snapshot.taskStatusText)
        root.put("planText", snapshot.planText)
        root.put("toolsLogText", snapshot.toolsLogText)
        root.put("reviewText", snapshot.reviewText)

        val msgArr = JSONArray()
        snapshot.messages.forEach { m ->
            val mObj = JSONObject()
                .put("id", m.id)
                .put("role", m.role)
                .put("content", m.content)
                .put("reasoningContent", m.reasoningContent)
            if (m.usageText != null) mObj.put("usageText", m.usageText)
            if (m.incompleteNote != null) mObj.put("incompleteNote", m.incompleteNote)

            if (m.citations.isNotEmpty()) {
                val cArr = JSONArray()
                m.citations.forEach { c ->
                    cArr.put(
                        JSONObject()
                            .put("startIndex", c.startIndex)
                            .put("endIndex", c.endIndex)
                            .put("url", c.url)
                            .put("title", c.title)
                            .put("number", c.number)
                    )
                }
                mObj.put("citations", cArr)
            }
            if (m.sources.isNotEmpty()) {
                val sArr = JSONArray()
                m.sources.forEach { s ->
                    sArr.put(JSONObject().put("url", s.url).put("title", s.title))
                }
                mObj.put("sources", sArr)
            }
            if (m.localToolCalls.isNotEmpty()) {
                val tArr = JSONArray()
                m.localToolCalls.forEach { tArr.put(toolCallToJson(it)) }
                mObj.put("localToolCalls", tArr)
            }
            msgArr.put(mObj)
        }
        root.put("messages", msgArr)

        val histArr = JSONArray()
        snapshot.history.forEach { histArr.put(protocolMessageToJson(it)) }
        root.put("history", histArr)

        val repArr = JSONArray()
        snapshot.reports.forEach { r ->
            val rObj = JSONObject()
                .put("prompt", r.prompt)
                .put("mode", r.mode)
                .put("model", r.model)
                .put("effort", r.effort)
                .put("web", r.web)
                .put("localTools", r.localTools)
                .put("deviceTools", r.deviceTools)
                .put("plan", r.plan)
                .put("toolsLog", r.toolsLog)
                .put("review", r.review)
            if (r.answer != null) {
                rObj.put("answer", protocolMessageToJson(r.answer))
            }
            val uArr = JSONArray()
            r.usages.forEach { u ->
                uArr.put(usageToJson(u) ?: JSONObject.NULL)
            }
            rObj.put("usages", uArr)
            repArr.put(rObj)
        }
        root.put("reports", repArr)
        return root.toString()
    }

    fun deserializeSession(raw: String): SessionSnapshot? {
        if (raw.isBlank()) return null
        return try {
            val root = JSONObject(raw)
            val modeStr = root.optString("mode", "chat")
            val mode = if (modeStr == "work") WorkMode.WORK else WorkMode.CHAT

            val messages = mutableListOf<UiChatMessage>()
            val msgArr = root.optJSONArray("messages")
            if (msgArr != null) {
                for (i in 0 until msgArr.length()) {
                    val mObj = msgArr.optJSONObject(i) ?: continue
                    val citations = mutableListOf<CitationPoint>()
                    val cArr = mObj.optJSONArray("citations")
                    if (cArr != null) {
                        for (j in 0 until cArr.length()) {
                            val c = cArr.optJSONObject(j) ?: continue
                            citations.add(
                                CitationPoint(
                                    startIndex = c.optInt("startIndex"),
                                    endIndex = c.optInt("endIndex"),
                                    url = c.optString("url"),
                                    title = c.optString("title"),
                                    number = c.optInt("number", j + 1)
                                )
                            )
                        }
                    }
                    val sources = mutableListOf<SourceLink>()
                    val sArr = mObj.optJSONArray("sources")
                    if (sArr != null) {
                        for (j in 0 until sArr.length()) {
                            val s = sArr.optJSONObject(j) ?: continue
                            sources.add(
                                SourceLink(
                                    url = s.optString("url"),
                                    title = s.optString("title")
                                )
                            )
                        }
                    }
                    val localTools = mutableListOf<ToolCallRecord>()
                    val tArr = mObj.optJSONArray("localToolCalls")
                    if (tArr != null) {
                        for (j in 0 until tArr.length()) {
                            tArr.optJSONObject(j)?.let { localTools.add(jsonToToolCall(it)) }
                        }
                    }
                    messages.add(
                        UiChatMessage(
                            id = mObj.optLong("id", (i + 1).toLong()),
                            role = mObj.optString("role", "user"),
                            content = mObj.optString("content", ""),
                            reasoningContent = mObj.optString("reasoningContent", ""),
                            progressText = null,
                            citations = citations,
                            sources = sources,
                            localToolCalls = localTools,
                            usageText = if (mObj.has("usageText") && !mObj.isNull("usageText")) {
                                mObj.optString("usageText")
                            } else null,
                            incompleteNote = if (mObj.has("incompleteNote") && !mObj.isNull("incompleteNote")) {
                                mObj.optString("incompleteNote")
                            } else null
                        )
                    )
                }
            }

            val history = mutableListOf<ProtocolMessage>()
            val histArr = root.optJSONArray("history")
            if (histArr != null) {
                for (i in 0 until histArr.length()) {
                    histArr.optJSONObject(i)?.let { history.add(jsonToProtocolMessage(it)) }
                }
            }

            val reports = mutableListOf<WorkReport>()
            val repArr = root.optJSONArray("reports")
            if (repArr != null) {
                for (i in 0 until repArr.length()) {
                    val rObj = repArr.optJSONObject(i) ?: continue
                    val usages = mutableListOf<TokenUsage?>()
                    val uArr = rObj.optJSONArray("usages")
                    if (uArr != null) {
                        for (j in 0 until uArr.length()) {
                            if (uArr.isNull(j)) {
                                usages.add(null)
                            } else {
                                usages.add(jsonToUsage(uArr.optJSONObject(j)))
                            }
                        }
                    }
                    reports.add(
                        WorkReport(
                            prompt = rObj.optString("prompt", ""),
                            mode = rObj.optString("mode", "chat"),
                            model = rObj.optString("model", "deepseek-flash"),
                            effort = rObj.optString("effort", "high"),
                            web = rObj.optBoolean("web", true),
                            localTools = rObj.optBoolean("localTools", true),
                            deviceTools = rObj.optBoolean("deviceTools", true),
                            plan = rObj.optString("plan", ""),
                            toolsLog = rObj.optString("toolsLog", ""),
                            review = rObj.optString("review", ""),
                            answer = rObj.optJSONObject("answer")?.let { jsonToProtocolMessage(it) },
                            usages = usages
                        )
                    )
                }
            }

            SessionSnapshot(
                convTitle = root.optString("convTitle", "Fluorine"),
                mode = mode,
                messages = messages,
                history = history,
                reports = reports,
                showTaskPanel = root.optBoolean("showTaskPanel", false),
                activeWorkStep = root.optInt("activeWorkStep", -1),
                taskStatusText = root.optString("taskStatusText", "准备开始"),
                planText = root.optString("planText", ""),
                toolsLogText = root.optString("toolsLogText", ""),
                reviewText = root.optString("reviewText", "")
            )
        } catch (_: Exception) {
            null
        }
    }
}

