package com.uronic0x.fluorine.engine

import com.uronic0x.fluorine.protocol.CoreProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pure-Kotlin native implementation of the Fluorine engine.
 * - Strictly adheres to DeepSeek 2026-11 API & Official Prefix Cache Hit rules:
 *   1. Static `system` prompt at messages[0] + deterministic `tools` declaration order.
 *   2. Preserves `reasoning_content` inside active `tool_calls` sub-turns within a single user turn.
 *   3. Strips `reasoning_content` from historical turns while preserving historical `assistant(tool_calls)`
 *      and `role: "tool"` messages in Work mode so Turn 2+ hits the KV prefix cache from Turn 1.
 * - Supports autonomous Agent Work mode (no rigid 4-step pipeline) and full user customization.
 */
object KotlinNativeEngine {

    private const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    private const val DEFAULT_MAX_TOOL_ROUNDS = 10

    val KNOWN_MODELS = setOf("deepseek-flash", "deepseek-v4-pro")
    val KNOWN_EFFORTS = setOf("none", "low", "medium", "high", "max")

    fun engineInfoJson(rustLoaded: Boolean): String {
        return JSONObject()
            .put("ok", true)
            .put("engine", if (rustLoaded) "rust-jni" else "kotlin-native")
            .put("version", "0.4.0")
            .put("deterministic_tools", DeterministicTools.TOOL_NAMES.size)
            .put("harness_tools", AndroidHarnessTools.TOOL_NAMES.size)
            .toString()
    }

    fun normalizeBaseUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim()?.trimEnd('/').orEmpty()
        return if (trimmed.isEmpty()) DEFAULT_BASE_URL else trimmed
    }

    fun sanitizeMaxTokens(v: Int): Int = v.coerceIn(256, 393216)

    /**
     * Builds the upstream DeepSeek `/chat/completions` JSON payload while guaranteeing
     * Prefix Cache friendliness (static `system` prompt + deterministic `tools` array +
     * optional preservation of `assistant(tool_calls)` and `role: "tool"` history).
     */
    fun buildUpstreamPayload(req: JSONObject): JSONObject {
        val rawModel = req.optString("model", "deepseek-flash").trim()
        val model = rawModel.ifEmpty { "deepseek-flash" }

        val stage = req.optString("stage", "chat").trim().ifEmpty { "chat" }
        val useWeb = req.optBoolean("use_web", false)
        val useThinking = req.optBoolean("use_thinking", true)
        val thinkingMode = req.optString("thinking_mode", "auto").trim().lowercase()
        val unlockWeb = req.optBoolean("unlock_web", false)

        val rawEffort = req.optString("effort", "medium").trim().lowercase()
        val effort = if (rawEffort in KNOWN_EFFORTS) rawEffort else "medium"
        val maxTokens = sanitizeMaxTokens(req.optInt("max_tokens", 16384))
        val useLocalTools = req.optBoolean(
            "use_local_tools",
            stage == "work" || stage == "execute"
        )

        val customSystemPrompt = req.optString("system_prompt", "").trim()
        val systemText = if (customSystemPrompt.isNotEmpty()) {
            customSystemPrompt
        } else if (useLocalTools || stage == "work" || stage == "execute") {
            CoreProtocol.DEFAULT_WORK_SYSTEM_PROMPT
        } else {
            CoreProtocol.DEFAULT_CHAT_SYSTEM_PROMPT
        }

        val cleanedMessages = JSONArray()
        cleanedMessages.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemText)
        )

        val rawMessages = req.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until rawMessages.length()) {
            val m = rawMessages.optJSONObject(i) ?: continue
            val role = m.optString("role", "").trim()
            val content = m.optString("content", "")

            if (role == "latest_reminder") {
                val rem = content.trim()
                if (rem.isNotEmpty()) {
                    cleanedMessages.put(
                        JSONObject()
                            .put("role", "latest_reminder")
                            .put("content", rem)
                    )
                }
            } else if (role == "user") {
                if (content.trim().isEmpty()) continue
                cleanedMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content)
                )
            } else if (role == "assistant") {
                val toolCalls = m.optJSONArray("tool_calls")
                if (useLocalTools && toolCalls != null && toolCalls.length() > 0) {
                    val msgObj = JSONObject()
                        .put("role", "assistant")
                        .put("content", content)
                        .put("tool_calls", toolCalls)
                    // Completed historical turns MUST NOT send reasoning_content per DeepSeek spec;
                    // active sub-turns during tool loops attach reasoning_content directly in the loop.
                    cleanedMessages.put(msgObj)
                } else if (content.trim().isNotEmpty()) {
                    cleanedMessages.put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", content)
                    )
                }
            } else if (role == "tool" && useLocalTools) {
                val toolCallId = m.optString("tool_call_id", "").trim()
                if (toolCallId.isNotEmpty()) {
                    cleanedMessages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", toolCallId)
                            .put("content", content)
                    )
                }
            }
        }

        if (cleanedMessages.length() <= 1) {
            throw IllegalArgumentException("messages 不能为空（至少需要一条 user 消息）")
        }

        val effectiveThinking = when (thinkingMode) {
            "enabled" -> true
            "disabled" -> false
            else -> useThinking
        }
        val effectiveEffort = if (!effectiveThinking && thinkingMode == "disabled") {
            "none"
        } else {
            effort
        }

        val payload = JSONObject()
            .put("model", model)
            .put("messages", cleanedMessages)
            .put("thinking", JSONObject().put("type", if (effectiveThinking) "enabled" else "disabled"))
            .put("reasoning", JSONObject().put("effort", effectiveEffort))
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))

        if (req.optBoolean("enable_temperature", false) && !effectiveThinking) {
            val temp = req.optDouble("temperature", 1.0).coerceIn(0.0, 2.0)
            payload.put("temperature", temp)
        }

        val tools = JSONArray()
        val allowWebForModel = unlockWeb || model == "deepseek-flash" || !KNOWN_MODELS.contains(model)
        if (useWeb && allowWebForModel) {
            tools.put(JSONObject().put("type", "web_search"))
        }
        if (useLocalTools) {
            val detDecls = DeterministicTools.declarations()
            for (i in 0 until detDecls.length()) {
                tools.put(detDecls.getJSONObject(i))
            }
            val harnessDecls = AndroidHarnessTools.declarations()
            for (i in 0 until harnessDecls.length()) {
                tools.put(harnessDecls.getJSONObject(i))
            }
        }
        if (tools.length() > 0) {
            payload.put("tools", tools)
        }

        return payload
    }

    fun executeLocalTool(
        name: String,
        rawArguments: String,
        workspaceRoot: File,
        allowSdcardReadonly: Boolean,
        shellTimeoutSecs: Long = 20L
    ): JSONObject {
        val args = try {
            if (rawArguments.isBlank()) JSONObject() else JSONObject(rawArguments)
        } catch (e: Exception) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Invalid tool JSON arguments: ${e.message}")
        }

        return when {
            name in DeterministicTools.TOOL_NAMES -> DeterministicTools.execute(name, args)
            name in AndroidHarnessTools.TOOL_NAMES ->
                AndroidHarnessTools.execute(name, args, workspaceRoot, allowSdcardReadonly, shellTimeoutSecs)
            else -> JSONObject().put("ok", false).put("error", "Unknown local tool: $name")
        }
    }

    fun discoverCapabilities(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        connectTimeoutSec: Int = 15,
        readTimeoutSec: Int = 30
    ): String {
        if (apiKey.isBlank()) {
            return JSONObject().put("ok", false).put("error", "API Key 不能为空").toString()
        }
        val normalizedUrl = normalizeBaseUrl(baseUrl)
        return try {
            val url = URL("$normalizedUrl/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = (connectTimeoutSec.coerceIn(5, 120)) * 1000
                readTimeout = (readTimeoutSec.coerceIn(10, 300)) * 1000
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                setRequestProperty("Accept", "application/json")
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                CoreProtocol.AppLogger.warn("Engine", "discoverCapabilities HTTP $status ($normalizedUrl)")
                return JSONObject()
                    .put("ok", false)
                    .put("status", status)
                    .put("error", extractUpstreamError(body, status))
                    .toString()
            }
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            val modelsArr = parsed?.optJSONArray("data") ?: JSONArray()
            val modelIds = JSONArray()
            for (i in 0 until modelsArr.length()) {
                val id = modelsArr.optJSONObject(i)?.optString("id", "").orEmpty()
                if (id.isNotBlank()) modelIds.put(id)
            }
            CoreProtocol.AppLogger.info("Engine", "discoverCapabilities OK: ${modelIds.length()} models from $normalizedUrl")
            JSONObject()
                .put("ok", true)
                .put("base_url", normalizedUrl)
                .put("models", modelIds)
                .put(
                    "local_tools",
                    JSONArray(DeterministicTools.TOOL_NAMES + AndroidHarnessTools.TOOL_NAMES)
                )
                .toString()
        } catch (e: Exception) {
            CoreProtocol.AppLogger.error("Engine", "discoverCapabilities error: ${e.message}")
            JSONObject()
                .put("ok", false)
                .put("error", "网络请求失败: ${e.message}")
                .toString()
        }
    }

    fun chatComplete(
        requestJson: String,
        defaultWorkspaceDir: File
    ): String {
        val req = try {
            JSONObject(requestJson)
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", "Invalid request JSON: ${e.message}").toString()
        }

        val apiKey = req.optString("api_key", "").trim()
        if (apiKey.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "请先在设置中填写 API Key").toString()
        }

        val baseUrl = normalizeBaseUrl(req.optString("base_url", DEFAULT_BASE_URL))
        val connectTimeoutSec = req.optInt("connect_timeout_sec", 20).coerceIn(5, 120)
        val readTimeoutSec = req.optInt("read_timeout_sec", 180).coerceIn(30, 600)
        val shellTimeoutSecs = req.optLong("shell_timeout_secs", 20L).coerceIn(5L, 120L)
        val maxToolRounds = req.optInt("max_rounds", DEFAULT_MAX_TOOL_ROUNDS).coerceIn(1, 50)

        val wsDirPath = req.optString("workspace_dir", "").trim()
        val workspaceRoot = if (wsDirPath.isNotEmpty()) File(wsDirPath) else defaultWorkspaceDir
        workspaceRoot.mkdirs()
        val allowSdcard = req.optBoolean("allow_sdcard_readonly", false)

        val payload = try {
            buildUpstreamPayload(req)
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: "请求参数错误").toString()
        }

        val messages = payload.getJSONArray("messages")
        val useLocalTools = req.optBoolean(
            "use_local_tools",
            req.optString("stage", "chat") in setOf("work", "execute")
        )

        val aggregatedThinking = StringBuilder()
        var finalContent = ""
        val aggregatedSources = JSONArray()
        val seenSourceKeys = mutableSetOf<String>()
        val aggregatedCitations = JSONArray()
        val localToolRecords = JSONArray()
        val subturnMessages = JSONArray()
        var lastUsage: JSONObject? = null
        var finishReason = ""
        var rounds = 0

        CoreProtocol.AppLogger.info(
            "Engine",
            "Start chatComplete model=${payload.optString("model")} stage=${req.optString("stage")} localTools=$useLocalTools msgs=${messages.length()}"
        )

        while (rounds < maxToolRounds) {
            rounds++
            val roundRes = executeSingleStreamRound(
                apiKey = apiKey,
                baseUrl = baseUrl,
                connectTimeoutSec = connectTimeoutSec,
                readTimeoutSec = readTimeoutSec,
                payload = payload
            )
            if (!roundRes.optBoolean("ok", false)) {
                return roundRes.toString()
            }

            val roundThinking = roundRes.optString("thinking", "")
            if (roundThinking.isNotEmpty()) {
                if (aggregatedThinking.isNotEmpty()) aggregatedThinking.append("\n\n")
                aggregatedThinking.append(roundThinking)
            }

            val roundContent = roundRes.optString("content", "")
            val roundSources = roundRes.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until roundSources.length()) {
                val s = roundSources.optJSONObject(i) ?: continue
                val key = "${s.optString("url")}|${s.optString("title")}|${s.optInt("cite_index", -1)}"
                if (seenSourceKeys.add(key)) {
                    aggregatedSources.put(s)
                }
            }

            val roundCitations = roundRes.optJSONArray("citations") ?: JSONArray()
            if (roundCitations.length() > 0) {
                while (aggregatedCitations.length() > 0) aggregatedCitations.remove(0)
                for (i in 0 until roundCitations.length()) {
                    aggregatedCitations.put(roundCitations.getJSONObject(i))
                }
            }

            roundRes.optJSONObject("usage")?.let { u ->
                lastUsage = u
                val hit = u.optInt("prompt_cache_hit_tokens", 0)
                val miss = u.optInt("prompt_cache_miss_tokens", 0)
                CoreProtocol.AppLogger.info(
                    "Engine",
                    "Round #$rounds usage: prompt=${u.optInt("prompt_tokens")} completion=${u.optInt("completion_tokens")} cache_hit=$hit cache_miss=$miss"
                )
            }
            finishReason = roundRes.optString("finish_reason", "")

            val toolCalls = roundRes.optJSONArray("tool_calls") ?: JSONArray()
            if (!useLocalTools || toolCalls.length() == 0) {
                finalContent = roundContent
                break
            }

            // Active sub-turn message sent back to DeepSeek in Round N+1 MUST include reasoning_content
            val assistantMsgForActiveLoop = JSONObject()
                .put("role", "assistant")
                .put("content", roundContent)
                .put("reasoning_content", roundThinking)
                .put("tool_calls", toolCalls)
            messages.put(assistantMsgForActiveLoop)

            // Cross-turn stored sub-turn message strips reasoning_content per DeepSeek official spec
            val assistantMsgForHistory = JSONObject()
                .put("role", "assistant")
                .put("content", roundContent)
                .put("tool_calls", toolCalls)
            subturnMessages.put(assistantMsgForHistory)

            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                val callId = tc.optString("id", "call_${rounds}_$i")
                val fn = tc.optJSONObject("function") ?: JSONObject()
                val fnName = fn.optString("name", "")
                val fnArgs = fn.optString("arguments", "{}")

                val toolOutputObj = executeLocalTool(
                    name = fnName,
                    rawArguments = fnArgs,
                    workspaceRoot = workspaceRoot,
                    allowSdcardReadonly = allowSdcard,
                    shellTimeoutSecs = shellTimeoutSecs
                )
                val toolOutputStr = toolOutputObj.toString()

                CoreProtocol.AppLogger.info(
                    "Tool",
                    "[#$rounds] $fnName($fnArgs) -> ok=${toolOutputObj.optBoolean("ok", true)}"
                )

                localToolRecords.put(
                    JSONObject()
                        .put("round", rounds)
                        .put("id", callId)
                        .put("name", fnName)
                        .put("arguments", fnArgs)
                        .put("output", toolOutputStr)
                )

                val toolReplyMsg = JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", callId)
                    .put("content", toolOutputStr)
                messages.put(toolReplyMsg)
                subturnMessages.put(toolReplyMsg)
            }
            finalContent = roundContent
        }

        return JSONObject()
            .put("ok", true)
            .put("model", payload.optString("model"))
            .put("stage", req.optString("stage", "chat"))
            .put("content", finalContent)
            .put("thinking", aggregatedThinking.toString())
            .put("sources", aggregatedSources)
            .put("citations", aggregatedCitations)
            .put("local_tool_calls", localToolRecords)
            .put("subturn_messages", subturnMessages)
            .put("usage", lastUsage ?: JSONObject())
            .put("finish_reason", finishReason)
            .put("rounds", rounds)
            .toString()
    }

    private fun executeSingleStreamRound(
        apiKey: String,
        baseUrl: String,
        connectTimeoutSec: Int,
        readTimeoutSec: Int,
        payload: JSONObject
    ): JSONObject {
        val url = URL("$baseUrl/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutSec * 1000
            readTimeout = readTimeoutSec * 1000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream, application/json")
        }

        return try {
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bodyBytes) }

            val status = conn.responseCode
            if (status !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val msg = extractUpstreamError(errBody, status)
                CoreProtocol.AppLogger.error("Engine", "HTTP $status: $msg")
                return JSONObject()
                    .put("ok", false)
                    .put("status", status)
                    .put("error", msg)
            }

            val contentType = (conn.contentType ?: "").lowercase()
            if (!contentType.contains("text/event-stream")) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return parseNonStreamingResponse(text)
            }

            val thinkingSb = StringBuilder()
            val contentSb = StringBuilder()
            val sources = JSONArray()
            val citations = JSONArray()
            val toolCallSlotMap = linkedMapOf<Int, ToolCallAccumulator>()
            var usageObj: JSONObject? = null
            var finishReason = ""

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: continue
                    if (!trimmed.startsWith("data:")) continue
                    val dataStr = trimmed.removePrefix("data:").trim()
                    if (dataStr.isEmpty() || dataStr == "[DONE]") continue

                    val chunk = runCatching { JSONObject(dataStr) }.getOrNull() ?: continue
                    chunk.optJSONObject("usage")?.let { usageObj = it }
                    collectSourcesFromObject(chunk, sources)

                    val choices = chunk.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val choice = choices.optJSONObject(i) ?: continue
                        val fr = choice.optString("finish_reason", "")
                        if (fr.isNotEmpty() && fr != "null") finishReason = fr

                        collectSourcesFromObject(choice, sources)
                        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: continue
                        collectSourcesFromObject(delta, sources)
                        collectCitationsFromDelta(delta, citations)

                        val rPiece = delta.optString("reasoning_content", "")
                        if (rPiece.isNotEmpty() && rPiece != "null") {
                            thinkingSb.append(rPiece)
                        }
                        val cPiece = delta.optString("content", "")
                        if (cPiece.isNotEmpty() && cPiece != "null") {
                            contentSb.append(cPiece)
                        }

                        val tcArr = delta.optJSONArray("tool_calls")
                        if (tcArr != null) {
                            for (tIdx in 0 until tcArr.length()) {
                                val tcDelta = tcArr.optJSONObject(tIdx) ?: continue
                                val slot = tcDelta.optInt("index", tIdx)
                                val acc = toolCallSlotMap.getOrPut(slot) { ToolCallAccumulator() }
                                val id = tcDelta.optString("id", "")
                                if (id.isNotEmpty() && id != "null") acc.id = id
                                val fn = tcDelta.optJSONObject("function")
                                if (fn != null) {
                                    val n = fn.optString("name", "")
                                    if (n.isNotEmpty() && n != "null") acc.name.append(n)
                                    val a = fn.optString("arguments", "")
                                    if (a.isNotEmpty() && a != "null") acc.arguments.append(a)
                                }
                            }
                        }
                    }
                }
            }

            val assembledToolCalls = JSONArray()
            for ((slot, acc) in toolCallSlotMap) {
                val fnName = acc.name.toString().trim()
                if (fnName.isEmpty() || fnName == "web_search") continue
                assembledToolCalls.put(
                    JSONObject()
                        .put("id", acc.id.ifEmpty { "call_$slot" })
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", fnName)
                                .put("arguments", acc.arguments.toString().ifEmpty { "{}" })
                        )
                )
            }

            JSONObject()
                .put("ok", true)
                .put("thinking", thinkingSb.toString())
                .put("content", contentSb.toString())
                .put("sources", sources)
                .put("citations", citations)
                .put("tool_calls", assembledToolCalls)
                .put("usage", usageObj ?: JSONObject())
                .put("finish_reason", finishReason)
        } catch (e: Exception) {
            CoreProtocol.AppLogger.error("Engine", "Stream error: ${e.message}")
            JSONObject()
                .put("ok", false)
                .put("error", "连接或流解析异常: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseNonStreamingResponse(raw: String): JSONObject {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "Invalid JSON response")
        val sources = JSONArray()
        val citations = JSONArray()
        collectSourcesFromObject(obj, sources)

        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        collectSourcesFromObject(choice, sources)
        val msg = choice.optJSONObject("message") ?: JSONObject()
        collectSourcesFromObject(msg, sources)
        collectCitationsFromDelta(msg, citations)

        val rawToolCalls = msg.optJSONArray("tool_calls") ?: JSONArray()
        val filteredToolCalls = JSONArray()
        for (i in 0 until rawToolCalls.length()) {
            val tc = rawToolCalls.optJSONObject(i) ?: continue
            val fnName = tc.optJSONObject("function")?.optString("name", "").orEmpty()
            if (fnName.isNotEmpty() && fnName != "web_search") {
                filteredToolCalls.put(tc)
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("thinking", msg.optString("reasoning_content", ""))
            .put("content", msg.optString("content", ""))
            .put("sources", sources)
            .put("citations", citations)
            .put("tool_calls", filteredToolCalls)
            .put("usage", obj.optJSONObject("usage") ?: JSONObject())
            .put("finish_reason", choice.optString("finish_reason", ""))
    }

    private fun collectSourcesFromObject(container: JSONObject, out: JSONArray) {
        val candidateKeys = listOf("search_results", "web_search_results", "sources", "references")
        for (key in candidateKeys) {
            val arr = container.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title", item.optString("name", "")).trim()
                val url = item.optString("url", item.optString("link", "")).trim()
                if (title.isEmpty() && url.isEmpty()) continue
                val src = JSONObject()
                    .put("title", title)
                    .put("url", url)
                    .put("snippet", item.optString("snippet", item.optString("summary", "")))
                    .put("site_name", item.optString("site_name", item.optString("source", "")))
                    .put("published_at", item.optString("published_at", item.optString("date", "")))
                if (item.has("cite_index") || item.has("index")) {
                    src.put("cite_index", item.optInt("cite_index", item.optInt("index", i + 1)))
                }
                out.put(src)
            }
        }
    }

    private fun collectCitationsFromDelta(delta: JSONObject, out: JSONArray) {
        val annotations = delta.optJSONArray("annotations") ?: delta.optJSONArray("citations") ?: return
        for (i in 0 until annotations.length()) {
            val item = annotations.optJSONObject(i) ?: continue
            val offset = item.optInt("offset", item.optInt("end_index", -1))
            val indexes = item.optJSONArray("indexes") ?: item.optJSONArray("cite_indexes")
            if (offset >= 0 && indexes != null && indexes.length() > 0) {
                out.put(JSONObject().put("offset", offset).put("indexes", indexes))
            }
        }
    }

    private fun extractUpstreamError(rawBody: String, status: Int): String {
        val parsed = runCatching { JSONObject(rawBody) }.getOrNull()
        val errField = parsed?.opt("error")
        val detail = when (errField) {
            is JSONObject -> errField.optString("message", errField.toString())
            is String -> errField
            else -> rawBody.take(240)
        }
        return if (detail.isNotBlank()) "HTTP $status: $detail" else "HTTP $status 请求失败"
    }

    private class ToolCallAccumulator {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}
