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
    val indexes: List<Int>
)

data class LocalToolCallRecord(
    val round: Int,
    val id: String,
    val name: String,
    val arguments: String,
    val output: String
)

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
    val usageText: String = "",
    val isError: Boolean = false
)

/**
 * Represents a turn in the conversation context history.
 * For DeepSeek Prefix Cache compliance in Work mode, `subturnMessages` stores the intermediate
 * `assistant` (with `tool_calls`, without `reasoning_content`) and `tool` messages produced
 * during that turn so subsequent turns hit the server KV cache.
 */
data class ContextTurn(
    val role: String,
    val content: String,
    val subturnMessages: List<String> = emptyList()
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
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val finishReason: String = "",
    val rounds: Int = 1,
    val error: String = ""
)

object CoreProtocol {

    const val DEFAULT_API_BASE_URL = "https://api.deepseek.com"

    const val DEFAULT_CHAT_SYSTEM_PROMPT =
        "你是 Fluorine（DeepSeek 本地工作台），严谨、直接、重事实的中文技术与研究助手。优先给出可核验结论、关键依据、清晰推导与边界条件。数学公式请使用标准 LaTeX 语法（行内 \$...\$，独立公式块 \$\$...\$\$）。若证据不足或未联网核实，请明确说明不确定性，绝不编造来源、命令输出或实验数据。"

    const val DEFAULT_WORK_SYSTEM_PROMPT =
        "你是 Fluorine（DeepSeek 本地工作台），具备自主规划、取证与本地执行能力的中文技术 Agent。没有固定死板的工作流步骤限制：请根据用户的具体目标，自行判断是否需要拆解计划、是否需要调用联网搜索、本地确定性计算工具（calc/stats/unit_convert/date_calc/text_metrics/json_query/csv_summary/text_diff/encode_hash）或 Android/Termux 工作区工具（fs_list/fs_read/fs_write/fs_edit/fs_search/shell_exec/git_cmd/termux_api/self_adb）。简单问题直接回答或一步执行；复杂任务自主多轮调用工具验证后再汇总输出。数学公式请使用标准 LaTeX（\$...\$ 或 \$\$...\$\$）。生成或修改文件请使用 fs_write / fs_edit 写入工作区，方便用户直接导出产物。"

    val MODEL_OPTIONS = listOf(
        ModelOption("deepseek-flash", "deepseek-flash · DeepSeek Flash (V4.1-Flash · 官方联网 + 本地工具)"),
        ModelOption("deepseek-v4-pro", "deepseek-v4-pro · DeepSeek V4 Pro (V4-Pro · 深度推理 + 本地工具)")
    )

    val THINKING_MODE_OPTIONS = listOf(
        "auto" to "跟随底部开关",
        "enabled" to "强制开启 (enabled)",
        "disabled" to "强制关闭 (disabled)"
    )

    val EFFORT_OPTIONS = listOf(
        EffortOption("none", "none · 极速无深度思考"),
        EffortOption("low", "low · 轻量推理"),
        EffortOption("medium", "medium · 标准推理"),
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
            if (!ok) {
                val err = obj.optString("error", "未知引擎错误")
                AppLogger.error("Engine", "Turn failed: $err")
                return EngineTurnResult(ok = false, error = err)
            }

            val sources = mutableListOf<SearchSource>()
            val srcArr = obj.optJSONArray("sources") ?: JSONArray()
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

            val citations = mutableListOf<CitationPoint>()
            val citArr = obj.optJSONArray("citations") ?: JSONArray()
            for (i in 0 until citArr.length()) {
                val c = citArr.optJSONObject(i) ?: continue
                val offset = c.optInt("offset", -1)
                val idxArr = c.optJSONArray("indexes") ?: JSONArray()
                val idxList = mutableListOf<Int>()
                for (j in 0 until idxArr.length()) {
                    idxList.add(idxArr.optInt(j))
                }
                if (offset >= 0 && idxList.isNotEmpty()) {
                    citations.add(CitationPoint(offset = offset, indexes = idxList))
                }
            }

            val toolCalls = mutableListOf<LocalToolCallRecord>()
            val tcArr = obj.optJSONArray("local_tool_calls") ?: JSONArray()
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

            val subturnMessages = mutableListOf<String>()
            val subArr = obj.optJSONArray("subturn_messages") ?: JSONArray()
            for (i in 0 until subArr.length()) {
                val m = subArr.optJSONObject(i) ?: continue
                subturnMessages.add(m.toString())
            }

            val usage = obj.optJSONObject("usage")
            val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
            val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
            val cacheHitTokens = usage?.optInt("prompt_cache_hit_tokens", 0) ?: 0
            val cacheMissTokens = usage?.optInt("prompt_cache_miss_tokens", 0) ?: 0
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
                }
                if (rounds > 1) {
                    if (isNotEmpty()) append(" · ")
                    append("$rounds 轮工具交互")
                }
            }

            AppLogger.info(
                "Engine",
                "Turn ok: model=${obj.optString("model")} rounds=$rounds tools=${toolCalls.size} $usageText"
            )

            EngineTurnResult(
                ok = true,
                model = obj.optString("model", ""),
                stage = obj.optString("stage", "chat"),
                content = obj.optString("content", ""),
                thinking = obj.optString("thinking", ""),
                sources = sources,
                citations = citations,
                localToolCalls = toolCalls,
                subturnMessages = subturnMessages,
                usageText = usageText,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cacheHitTokens = cacheHitTokens,
                cacheMissTokens = cacheMissTokens,
                finishReason = obj.optString("finish_reason", ""),
                rounds = rounds
            )
        } catch (e: Exception) {
            AppLogger.error("Engine", "Parse turn result error: ${e.message}")
            EngineTurnResult(ok = false, error = "解析响应失败: ${e.message}")
        }
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
     * Lists all user-generated files currently inside the app's workspace directory.
     */
    fun listWorkspaceFiles(workspaceRoot: File): List<WorkspaceFileArtifact> {
        if (!workspaceRoot.exists() || !workspaceRoot.isDirectory) return emptyList()
        val rootCanonical = runCatching { workspaceRoot.canonicalFile }.getOrDefault(workspaceRoot)
        val rootPrefix = rootCanonical.path
        val results = mutableListOf<WorkspaceFileArtifact>()

        workspaceRoot.walkTopDown()
            .onEnter { dir ->
                dir.name != ".git" && dir.name != "target" && dir.name != "build"
            }
            .forEach { f ->
                if (f.isFile && results.size < 200) {
                    val cPath = runCatching { f.canonicalFile.path }.getOrDefault(f.path)
                    val rel = if (cPath.startsWith(rootPrefix + File.separator)) {
                        cPath.substring(rootPrefix.length + 1).replace('\\', '/')
                    } else {
                        f.name
                    }
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

    fun serializeSessionsStore(
        sessions: List<ConversationSession>,
        activeSessionId: String
    ): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("active_session_id", activeSessionId)
        val sessionsArr = JSONArray()
        for (s in sessions) {
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
            sessions to activeId
        } catch (_: Exception) {
            emptyList<ConversationSession>() to null
        }
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
                .put("isError", m.isError)
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
            msgArr.put(mObj)
        }
        obj.put("chatMessages", msgArr)

        val ctxArr = JSONArray()
        for (c in session.contextHistory) {
            val cObj = JSONObject()
                .put("role", c.role)
                .put("content", c.content)
            if (c.subturnMessages.isNotEmpty()) {
                cObj.put("subturn_messages", JSONArray(c.subturnMessages))
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

            val sources = mutableListOf<SearchSource>()
            val sArr = m.optJSONArray("sources") ?: JSONArray()
            for (j in 0 until sArr.length()) {
                val s = sArr.optJSONObject(j) ?: continue
                sources.add(
                    SearchSource(
                        title = s.optString("title", ""),
                        url = s.optString("url", ""),
                        snippet = s.optString("snippet", ""),
                        siteName = s.optString("site_name", ""),
                        publishedAt = s.optString("published_at", ""),
                        citeIndex = if (s.has("cite_index") && !s.isNull("cite_index")) s.optInt("cite_index") else null
                    )
                )
            }

            val toolCalls = mutableListOf<LocalToolCallRecord>()
            val tcArr = m.optJSONArray("localToolCalls") ?: JSONArray()
            for (j in 0 until tcArr.length()) {
                val tc = tcArr.optJSONObject(j) ?: continue
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

            msgList.add(
                ChatMessage(
                    id = m.optString("id", "msg_$i"),
                    role = m.optString("role", "assistant"),
                    text = m.optString("text", ""),
                    tags = tags,
                    thinking = m.optString("thinking", ""),
                    sources = sources,
                    localToolCalls = toolCalls,
                    usageText = m.optString("usageText", ""),
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
                    subturnMessages = subturns
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
