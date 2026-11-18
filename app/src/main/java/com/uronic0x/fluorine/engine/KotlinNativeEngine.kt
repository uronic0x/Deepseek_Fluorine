package com.uronic0x.fluorine.engine

import android.util.Base64
import com.uronic0x.fluorine.protocol.ChatAttachment
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.LocalToolCallRecord
import com.uronic0x.fluorine.protocol.SearchSource
import com.uronic0x.fluorine.protocol.StreamUiEvent
import com.uronic0x.fluorine.protocol.WorkToolsConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-Kotlin native engine aligned with DeepSeek 2026-11 Chat Completions,
 * Files API, Search/Fetch API, Vision/Document multimodal blocks, streaming events,
 * cancellation, and customizable Work tool allowlists.
 */
object KotlinNativeEngine {

    private const val DEFAULT_BASE_URL = "https://api.deepseek.com"

    val KNOWN_MODELS = setOf("deepseek-flash", "deepseek-v4-pro")
    val KNOWN_EFFORTS = setOf("none", "low", "medium", "high", "max")

    private class ActiveRequestHandle(
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        @Volatile var activeConn: HttpURLConnection? = null
    )

    private val activeRequests = ConcurrentHashMap<Long, ActiveRequestHandle>()

    fun cancelStream(requestId: Long): Boolean {
        val handle = activeRequests[requestId] ?: return false
        handle.cancelled.set(true)
        runCatching { handle.activeConn?.disconnect() }
        return true
    }

    fun engineInfoJson(rustLoaded: Boolean): String {
        return JSONObject()
            .put("ok", true)
            .put("engine", if (rustLoaded) "rust-jni+kotlin" else "kotlin-native")
            .put("version", "0.5.0")
            .put("deterministic_tools", DeterministicTools.TOOL_NAMES.size)
            .put("harness_tools", AndroidHarnessTools.TOOL_NAMES.size)
            .toString()
    }

    fun normalizeBaseUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim()?.trimEnd('/').orEmpty()
        return if (trimmed.isEmpty()) DEFAULT_BASE_URL else trimmed
    }

    fun sanitizeMaxTokens(v: Int): Int = v.coerceIn(256, 393216)

    fun mapEffortToApi(rawEffort: String, effectiveThinking: Boolean, thinkingMode: String): String {
        if (!effectiveThinking && thinkingMode == "disabled") return "none"
        return when (rawEffort.trim().lowercase()) {
            "none" -> "none"
            "low" -> "low"
            "medium", "high" -> "high"
            "max" -> "max"
            else -> "high"
        }
    }

    fun resolveEnabledTools(req: JSONObject, useLocalTools: Boolean): Set<String> {
        if (!useLocalTools) return emptySet()
        val explicitArr = req.optJSONArray("enabled_tools")
        if (explicitArr != null) {
            val out = linkedSetOf<String>()
            for (i in 0 until explicitArr.length()) {
                val n = explicitArr.optString(i, "").trim()
                if (n.isNotEmpty()) out.add(n)
            }
            return out
        }
        val toolConfigObj = req.optJSONObject("tool_config")
        return WorkToolsConfig.fromJson(toolConfigObj).enabledToolNames()
    }

    private fun buildUserContentValue(msgObj: JSONObject): Any? {
        val rawContent = msgObj.opt("content")
        val attachmentsArr = msgObj.optJSONArray("attachments")
        if (rawContent is JSONArray) {
            return rawContent
        }
        val text = msgObj.optString("content", "")
        if (attachmentsArr == null || attachmentsArr.length() == 0) {
            return if (text.trim().isEmpty()) null else text
        }
        val blocks = JSONArray()
        for (i in 0 until attachmentsArr.length()) {
            val att = attachmentsArr.optJSONObject(i) ?: continue
            val kind = att.optString("kind", "document")
            val fileId = att.optString("file_id", "").trim()
            val localPath = att.optString("local_path", "").trim()
            val mimeType = att.optString("mime_type", "application/octet-stream").trim()
            if (fileId.isNotEmpty()) {
                blocks.put(JSONObject().put("type", "file").put("file_id", fileId))
            } else if (kind == "image" && localPath.isNotEmpty()) {
                val f = File(localPath)
                if (f.exists() && f.isFile && f.length() <= 32L * 1024L * 1024L) {
                    val b64 = runCatching {
                        Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    }.getOrNull()
                    if (!b64.isNullOrBlank()) {
                        val dataUrl = "data:${mimeType.ifBlank { "image/jpeg" }};base64,$b64"
                        blocks.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", dataUrl))
                        )
                    }
                }
            }
        }
        if (text.isNotBlank()) {
            blocks.put(JSONObject().put("type", "text").put("text", text))
        }
        return if (blocks.length() > 0) blocks else null
    }

    /**
     * Builds the upstream DeepSeek `/chat/completions` JSON payload per DeepSeek 2026-11 spec:
     * - Top-level `reasoning_effort` (`none` | `low` | `high` | `max`) + `thinking` object.
     * - Never mixes `deepseek_web_search`/`deepseek_read_url` with custom `function` tools:
     *   if local tools are active and `use_web` is true, injects client-side `web_search` and
     *   `web_fetch` function tools backed by DeepSeek `/v1/tools/search` & `/v1/tools/fetch`.
     * - When `tools` is present in request, retains `reasoning_content` on historical assistant
     *   messages to satisfy DeepSeek reasoning + tool_calls context rules.
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
        val enabledTools = resolveEnabledTools(req, useLocalTools)

        val customSystemPrompt = req.optString("system_prompt", "").trim()
        val systemText = if (customSystemPrompt.isNotEmpty()) {
            customSystemPrompt
        } else if (useLocalTools || stage == "work" || stage == "execute") {
            CoreProtocol.DEFAULT_WORK_SYSTEM_PROMPT
        } else {
            CoreProtocol.DEFAULT_CHAT_SYSTEM_PROMPT
        }

        // Build tools list first so message serialization knows whether `tools` is present.
        val tools = JSONArray()
        val localFnDecls = mutableListOf<JSONObject>()
        if (useLocalTools && enabledTools.isNotEmpty()) {
            val detDecls = DeterministicTools.declarations(enabledTools)
            for (i in 0 until detDecls.length()) {
                localFnDecls.add(detDecls.getJSONObject(i))
            }
            val harnessDecls = AndroidHarnessTools.declarations(enabledTools)
            for (i in 0 until harnessDecls.length()) {
                localFnDecls.add(harnessDecls.getJSONObject(i))
            }
        }

        val allowWebForModel = unlockWeb || model == "deepseek-flash" || !KNOWN_MODELS.contains(model)
        val hasLocalFunctions = localFnDecls.isNotEmpty()
        if (useWeb && allowWebForModel) {
            if (hasLocalFunctions) {
                // Avoid 400 error from mixing `deepseek_web_search` with custom `function` tools;
                // instead declare Search/Fetch API-backed function tools.
                tools.put(clientWebSearchDecl())
                tools.put(clientWebFetchDecl())
            } else {
                tools.put(JSONObject().put("type", "deepseek_web_search"))
                tools.put(JSONObject().put("type", "deepseek_read_url"))
            }
        }
        for (decl in localFnDecls) {
            tools.put(decl)
        }
        val hasTools = tools.length() > 0

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
                val userContent = buildUserContentValue(m) ?: continue
                cleanedMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userContent)
                )
            } else if (role == "assistant") {
                val toolCalls = m.optJSONArray("tool_calls")
                val serverToolCalls = if (!hasLocalFunctions) m.optJSONArray("server_tool_calls") else null
                val hasCalls = useLocalTools && toolCalls != null && toolCalls.length() > 0
                if (hasCalls) {
                    val msgObj = JSONObject()
                        .put("role", "assistant")
                        .put("content", content)
                        .put("tool_calls", toolCalls)
                    if (hasTools) {
                        msgObj.put("reasoning_content", m.optString("reasoning_content", ""))
                    }
                    cleanedMessages.put(msgObj)
                } else if (content.trim().isNotEmpty() || (serverToolCalls != null && serverToolCalls.length() > 0)) {
                    val msgObj = JSONObject()
                        .put("role", "assistant")
                        .put("content", content)
                    if (hasTools && m.has("reasoning_content")) {
                        msgObj.put("reasoning_content", m.optString("reasoning_content", ""))
                    }
                    if (serverToolCalls != null && serverToolCalls.length() > 0) {
                        msgObj.put("server_tool_calls", serverToolCalls)
                    }
                    cleanedMessages.put(msgObj)
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
        val effectiveEffort = mapEffortToApi(effort, effectiveThinking, thinkingMode)

        val payload = JSONObject()
            .put("model", model)
            .put("messages", cleanedMessages)
            .put("thinking", JSONObject().put("type", if (effectiveThinking) "enabled" else "disabled"))
            .put("reasoning_effort", effectiveEffort)
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))

        if (req.optBoolean("enable_temperature", false) && !effectiveThinking) {
            val temp = req.optDouble("temperature", 1.0).coerceIn(0.0, 2.0)
            payload.put("temperature", temp)
        }

        if (hasTools) {
            payload.put("tools", tools)
        }

        return payload
    }

    fun executeLocalTool(
        name: String,
        rawArguments: String,
        workspaceRoot: File,
        allowSdcardReadonly: Boolean,
        shellTimeoutSecs: Long = 20L,
        enabledTools: Set<String>? = null,
        apiKey: String = "",
        baseUrl: String = DEFAULT_BASE_URL,
        connectTimeoutSec: Int = 20,
        readTimeoutSec: Int = 60,
        allowWeb: Boolean = true,
        collectedSources: MutableList<JSONObject>? = null
    ): JSONObject {
        val args = try {
            if (rawArguments.isBlank()) JSONObject() else JSONObject(rawArguments)
        } catch (e: Exception) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Invalid tool JSON arguments: ${e.message}")
        }

        if (name == "web_search" || name == "web_fetch") {
            if (!allowWeb) {
                return JSONObject().put("ok", false).put("error", "Web search/fetch is disabled for this request.")
            }
            return if (name == "web_search") {
                executeDeepSeekSearchApi(apiKey, baseUrl, args, connectTimeoutSec, readTimeoutSec, collectedSources)
            } else {
                executeDeepSeekFetchApi(apiKey, baseUrl, args, connectTimeoutSec, readTimeoutSec, collectedSources)
            }
        }

        val canonical = when (name) {
            "calc_eval" -> "calc"
            "stats_summary" -> "stats"
            else -> name
        }
        if (enabledTools != null && canonical !in enabledTools) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Tool '$name' is disabled in current Work tool settings.")
        }

        return when {
            name in DeterministicTools.TOOL_NAMES -> DeterministicTools.execute(name, args)
            name in AndroidHarnessTools.TOOL_NAMES ->
                AndroidHarnessTools.execute(name, args, workspaceRoot, allowSdcardReadonly, shellTimeoutSecs, enabledTools)
            else -> JSONObject().put("ok", false).put("error", "Unknown local tool: $name")
        }
    }

    private fun executeDeepSeekSearchApi(
        apiKey: String,
        baseUrl: String,
        args: JSONObject,
        connectTimeoutSec: Int,
        readTimeoutSec: Int,
        collectedSources: MutableList<JSONObject>?
    ): JSONObject {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return JSONObject().put("ok", false).put("error", "query is required")
        if (apiKey.isBlank()) return JSONObject().put("ok", false).put("error", "API key required for /v1/tools/search")

        val bodyObj = JSONObject()
            .put("query", query.take(128))
            .put("count", args.optInt("count", 8).coerceIn(1, 20))
            .put("need_content", args.optBoolean("need_content", false))
        val url = URL("${normalizeBaseUrl(baseUrl)}/v1/tools/search")
        return postJsonApi(url, apiKey, bodyObj, connectTimeoutSec, readTimeoutSec) { parsed ->
            val results = parsed.optJSONArray("results") ?: JSONArray()
            if (collectedSources != null) {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title", "").trim()
                    val link = item.optString("url", "").trim()
                    if (link.isNotEmpty() || title.isNotEmpty()) {
                        collectedSources.add(
                            JSONObject()
                                .put("title", title.ifEmpty { link })
                                .put("url", link)
                                .put("snippet", item.optString("snippet", item.optString("summary", "")))
                                .put("site_name", item.optString("site_name", ""))
                                .put("published_at", item.optString("date", ""))
                                .put("cite_index", collectedSources.size + 1)
                        )
                    }
                }
            }
            JSONObject().put("ok", true).put("results", results)
        }
    }

    private fun executeDeepSeekFetchApi(
        apiKey: String,
        baseUrl: String,
        args: JSONObject,
        connectTimeoutSec: Int,
        readTimeoutSec: Int,
        collectedSources: MutableList<JSONObject>?
    ): JSONObject {
        val targetUrl = args.optString("url", "").trim()
        if (targetUrl.isEmpty()) return JSONObject().put("ok", false).put("error", "url is required")
        if (apiKey.isBlank()) return JSONObject().put("ok", false).put("error", "API key required for /v1/tools/fetch")

        val bodyObj = JSONObject().put("url", targetUrl)
        val url = URL("${normalizeBaseUrl(baseUrl)}/v1/tools/fetch")
        return postJsonApi(url, apiKey, bodyObj, connectTimeoutSec, readTimeoutSec) { parsed ->
            val title = parsed.optString("title", targetUrl)
            val content = parsed.optString("content", "")
            val truncatedContent = if (content.length > 24000) content.take(24000) + "\n...[truncated]" else content
            if (collectedSources != null) {
                collectedSources.add(
                    JSONObject()
                        .put("title", title)
                        .put("url", targetUrl)
                        .put("snippet", content.take(180))
                        .put("published_at", parsed.optString("date", ""))
                        .put("cite_index", collectedSources.size + 1)
                )
            }
            JSONObject()
                .put("ok", true)
                .put("url", targetUrl)
                .put("title", title)
                .put("content", truncatedContent)
        }
    }

    private inline fun postJsonApi(
        url: URL,
        apiKey: String,
        payload: JSONObject,
        connectTimeoutSec: Int,
        readTimeoutSec: Int,
        onSuccess: (JSONObject) -> JSONObject
    ): JSONObject {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutSec.coerceIn(5, 60) * 1000
            readTimeout = readTimeoutSec.coerceIn(10, 120) * 1000
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                return JSONObject().put("ok", false).put("error", extractUpstreamError(body, status))
            }
            val parsed = runCatching { JSONObject(body) }.getOrNull()
                ?: return JSONObject().put("ok", false).put("error", "Invalid JSON from ${url.path}")
            onSuccess(parsed)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "API call failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Uploads a file to DeepSeek Files API (`POST {baseUrl}/files`).
     * Per official spec, `purpose` field MUST precede `file` field in multipart body.
     */
    fun uploadFile(
        apiKey: String,
        baseUrl: String,
        attachment: ChatAttachment,
        connectTimeoutSec: Int = 20,
        readTimeoutSec: Int = 150
    ): JSONObject {
        if (apiKey.isBlank()) return JSONObject().put("ok", false).put("error", "API Key 不能为空")
        val file = File(attachment.localPath)
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("ok", false).put("error", "附件文件不存在: ${attachment.name}")
        }

        val boundary = "----FluorineBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val normalizedUrl = normalizeBaseUrl(baseUrl)
        val conn = (URL("$normalizedUrl/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            setChunkedStreamingMode(64 * 1024)
            connectTimeout = connectTimeoutSec.coerceIn(10, 120) * 1000
            readTimeout = readTimeoutSec.coerceIn(60, 300) * 1000
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val purpose = attachment.parsePurpose.ifBlank { "user_data" }
            val safeFileName = attachment.name.replace("\"", "_").replace("\r", "").replace("\n", "")
            val mime = attachment.mimeType.ifBlank { "application/octet-stream" }

            DataOutputStream(conn.outputStream).use { out ->
                // 1. `purpose` field MUST come BEFORE `file` field per DeepSeek API spec
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n")
                out.writeBytes("$purpose\r\n")

                // 2. `file` binary field
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$safeFileName\"\r\n")
                out.writeBytes("Content-Type: $mime\r\n\r\n")
                file.inputStream().use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val errMsg = extractUpstreamError(body, status)
                CoreProtocol.AppLogger.error("FilesAPI", "Upload failed ($safeFileName): $errMsg")
                return JSONObject().put("ok", false).put("status", status).put("error", errMsg)
            }
            val parsed = runCatching { JSONObject(body) }.getOrNull()
                ?: return JSONObject().put("ok", false).put("error", "Files API 响应 JSON 无效")
            val fileId = parsed.optString("id", "")
            if (fileId.isBlank()) {
                return JSONObject().put("ok", false).put("error", "Files API 未返回 file_id")
            }
            CoreProtocol.AppLogger.info("FilesAPI", "Uploaded $safeFileName ($purpose) -> $fileId")
            JSONObject()
                .put("ok", true)
                .put("id", fileId)
                .put("filename", parsed.optString("filename", safeFileName))
                .put("bytes", parsed.optLong("bytes", file.length()))
                .put("purpose", parsed.optString("purpose", purpose))
        } catch (e: Exception) {
            CoreProtocol.AppLogger.error("FilesAPI", "Upload exception: ${e.message}")
            JSONObject().put("ok", false).put("error", "上传文件失败: ${e.message}")
        } finally {
            conn.disconnect()
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
            val modelsDetail = JSONArray()
            for (i in 0 until modelsArr.length()) {
                val m = modelsArr.optJSONObject(i) ?: continue
                val id = m.optString("id", "")
                if (id.isBlank()) continue
                modelIds.put(id)
                val fallback = CoreProtocol.DEFAULT_MODEL_CAPABILITIES[id]
                val modalitiesArr = m.optJSONArray("input_modalities") ?: JSONArray().apply {
                    fallback?.inputModalities?.forEach { put(it) }
                }
                modelsDetail.put(
                    JSONObject()
                        .put("id", id)
                        .put("name", m.optString("name", fallback?.name ?: id))
                        .put("context_window", m.optInt("context_window", fallback?.contextWindow ?: 0))
                        .put("max_output_tokens", m.optInt("max_output_tokens", fallback?.maxOutputTokens ?: 0))
                        .put("input_modalities", modalitiesArr)
                )
            }
            CoreProtocol.AppLogger.info("Engine", "discoverCapabilities OK: ${modelIds.length()} models from $normalizedUrl")
            JSONObject()
                .put("ok", true)
                .put("base_url", normalizedUrl)
                .put("models", modelIds)
                .put("models_detail", modelsDetail)
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
        return chatCompleteStreaming(
            requestId = System.nanoTime(),
            requestJson = requestJson,
            defaultWorkspaceDir = defaultWorkspaceDir,
            onEvent = null
        )
    }

    fun chatCompleteStreaming(
        requestId: Long,
        requestJson: String,
        defaultWorkspaceDir: File,
        onEvent: ((StreamUiEvent) -> Unit)? = null
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
        val maxToolRounds = req.optInt("max_rounds", CoreProtocol.DEFAULT_MAX_TOOL_ROUNDS)
            .coerceIn(1, CoreProtocol.MAX_TOOL_ROUNDS)

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
        val enabledTools = resolveEnabledTools(req, useLocalTools)
        val model = payload.optString("model")
        val allowWebForModel = req.optBoolean("unlock_web", false) ||
            model == "deepseek-flash" ||
            !KNOWN_MODELS.contains(model)
        val allowWeb = req.optBoolean("use_web", false) && allowWebForModel

        val handle = ActiveRequestHandle()
        activeRequests[requestId] = handle

        val aggregatedThinking = StringBuilder()
        val aggregatedContent = StringBuilder()
        var lastRoundContent = ""
        val aggregatedSources = JSONArray()
        val seenSourceKeys = mutableSetOf<String>()
        val aggregatedCitations = JSONArray()
        val localToolRecords = JSONArray()
        val subturnMessages = JSONArray()
        val usageAccumulator = UsageAccumulator()
        var finishReason = ""
        var rounds = 0
        val timeline = com.uronic0x.fluorine.protocol.WorkTimeline()

        fun emit(event: StreamUiEvent) {
            if (useLocalTools) timeline.accept(event)
            onEvent?.invoke(event)
        }

        CoreProtocol.AppLogger.info(
            "Engine",
            "Start chatComplete model=$model stage=${req.optString("stage")} localTools=$useLocalTools enabled=${enabledTools.size} msgs=${messages.length()}"
        )

        fun buildFinalResponse(
            ok: Boolean,
            errorMsg: String = "",
            incompleteNotice: String = ""
        ): String {
            val contentOut = if (lastRoundContent.isNotEmpty()) lastRoundContent else aggregatedContent.toString()
            return JSONObject()
                .put("ok", ok)
                .put("model", model)
                .put("stage", req.optString("stage", "chat"))
                .put("content", contentOut)
                .put("thinking", aggregatedThinking.toString())
                .put("sources", aggregatedSources)
                .put("citations", aggregatedCitations)
                .put("local_tool_calls", localToolRecords)
                .put("work_steps", CoreProtocol.serializeWorkSteps(timeline.steps))
                .put("subturn_messages", subturnMessages)
                .put("usage", usageAccumulator.toJson())
                .put("finish_reason", finishReason)
                .put("rounds", rounds.coerceAtLeast(1))
                .put("incomplete_notice", incompleteNotice)
                .put("error", errorMsg)
                .toString()
        }

        try {
            // The budget counts tool batches; allow one final model response after the last batch.
            while (rounds <= maxToolRounds) {
                if (handle.cancelled.get()) {
                    return buildFinalResponse(
                        ok = false,
                        errorMsg = "用户已手动停止生成",
                        incompleteNotice = "用户已手动停止生成，已保留当前流式内容。"
                    )
                }

                rounds++
                if (rounds > maxToolRounds) payload.put("tool_choice", "none")
                if (useLocalTools) emit(StreamUiEvent.RoundStarted(rounds))
                if (rounds > 1) {
                    emit(StreamUiEvent.Status("第 $rounds 轮推理中..."))
                }

                val roundRes = executeSingleStreamRound(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    connectTimeoutSec = connectTimeoutSec,
                    readTimeoutSec = readTimeoutSec,
                    payload = payload,
                    handle = handle,
                    onThinkingDelta = { delta ->
                        if (aggregatedThinking.isEmpty() || rounds == 1) {
                            aggregatedThinking.append(delta)
                        } else {
                            aggregatedThinking.append(delta)
                        }
                        emit(StreamUiEvent.ThinkingDelta(delta))
                    },
                    onContentDelta = { delta ->
                        aggregatedContent.append(delta)
                        emit(StreamUiEvent.ContentDelta(delta))
                    },
                    onStatus = { statusText ->
                        emit(StreamUiEvent.Status(statusText))
                    }
                )

                val roundSources = roundRes.optJSONArray("sources") ?: JSONArray()
                var sourcesAdded = false
                for (i in 0 until roundSources.length()) {
                    val s = roundSources.optJSONObject(i) ?: continue
                    val key = "${s.optString("url")}|${s.optString("title")}|${s.optInt("cite_index", -1)}"
                    if (seenSourceKeys.add(key)) {
                        aggregatedSources.put(s)
                        sourcesAdded = true
                    }
                }
                if (sourcesAdded) {
                    onEvent?.invoke(StreamUiEvent.SourcesUpdated(CoreProtocol.parseSourcesArray(aggregatedSources)))
                }

                roundRes.optJSONObject("usage")?.let { u ->
                    usageAccumulator.add(u)
                    CoreProtocol.AppLogger.info(
                        "Engine",
                        "Round #$rounds usage: prompt=${u.optInt("prompt_tokens")} completion=${u.optInt("completion_tokens")} cache_hit=${u.optInt("prompt_cache_hit_tokens", 0)}"
                    )
                }

                val roundFinish = roundRes.optString("finish_reason", "")
                if (roundFinish.isNotEmpty()) finishReason = roundFinish
                val roundContent = roundRes.optString("content", "")
                if (roundContent.isNotEmpty()) lastRoundContent = roundContent
                val roundThinking = roundRes.optString("thinking", "")

                val roundCitations = roundRes.optJSONArray("citations") ?: JSONArray()
                timeline.setCitations(CoreProtocol.parseCitationsArray(roundCitations))
                if (roundCitations.length() > 0) {
                    while (aggregatedCitations.length() > 0) aggregatedCitations.remove(0)
                    for (i in 0 until roundCitations.length()) {
                        aggregatedCitations.put(roundCitations.getJSONObject(i))
                    }
                }

                if (!roundRes.optBoolean("ok", false)) {
                    val errText = roundRes.optString("error", "请求或流式读取失败")
                    val notice = roundRes.optString("incomplete_notice", errText)
                    return buildFinalResponse(
                        ok = false,
                        errorMsg = errText,
                        incompleteNotice = notice
                    )
                }

                // Check terminal finish_reason policies (preserving already streamed text!)
                when (finishReason) {
                    "content_filter" -> {
                        return buildFinalResponse(
                            ok = false,
                            errorMsg = "内容安全审核已触发 (finish_reason=content_filter)",
                            incompleteNotice = "触发内容安全审核（content_filter），已保留已流出的全部内容。"
                        )
                    }
                    "length" -> {
                        return buildFinalResponse(
                            ok = false,
                            errorMsg = "输出达到 max_tokens 上限被截断 (finish_reason=length)",
                            incompleteNotice = "输出达到最大 Token 上限（finish_reason=length），已保留已流出的内容；可在设置中调大 max_tokens。"
                        )
                    }
                    "insufficient_system_resource" -> {
                        return buildFinalResponse(
                            ok = false,
                            errorMsg = "上游算力资源不足 (insufficient_system_resource)",
                            incompleteNotice = "上游服务资源不足中断，已保留已流出的内容。"
                        )
                    }
                }

                val toolCalls = roundRes.optJSONArray("tool_calls") ?: JSONArray()
                val wantsToolCall = finishReason == "tool_calls" && toolCalls.length() > 0
                if (!useLocalTools || !wantsToolCall) {
                    if (toolCalls.length() > 0 && finishReason != "tool_calls") {
                        return buildFinalResponse(
                            ok = false,
                            errorMsg = "收到未完成/异常的工具调用请求 (finish_reason=$finishReason)，已中止执行工具。",
                            incompleteNotice = "工具调用流未正常结束 (finish_reason=$finishReason)，已保留已流出的内容。"
                        )
                    }
                    if (lastRoundContent.isBlank() && aggregatedThinking.isBlank() && localToolRecords.length() == 0) {
                        return buildFinalResponse(
                            ok = false,
                            errorMsg = "模型返回空响应（finish_reason=$finishReason）",
                            incompleteNotice = "API 返回空内容，请检查模型或提示词。"
                        )
                    }
                    if (lastRoundContent.isBlank() && aggregatedThinking.isNotBlank()) {
                        return buildFinalResponse(
                            ok = false,
                            errorMsg = "模型仅输出了思考过程，未返回正文回答",
                            incompleteNotice = "模型未输出正文，已保留全部思考过程。"
                        )
                    }
                    return buildFinalResponse(ok = true)
                }

                if (rounds > maxToolRounds) {
                    return buildFinalResponse(
                        ok = false,
                        errorMsg = "达到最大工具交互轮次上限 ($maxToolRounds 轮)",
                        incompleteNotice = "已达到工具轮次上限，已保留当前输出；可在设置中调大上限。"
                    )
                }

                // Validate JSON arguments for every requested tool call before executing any
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(i) ?: continue
                    val rawArgs = tc.optJSONObject("function")?.optString("arguments", "{}").orEmpty()
                    val parsedArgs = runCatching {
                        if (rawArgs.isBlank()) JSONObject() else JSONObject(rawArgs)
                    }.getOrNull()
                    if (parsedArgs == null) {
                        return buildFinalResponse(
                            ok = false,
                            errorMsg = "工具参数 JSON 不完整或损坏，已中止执行: ${rawArgs.take(120)}",
                            incompleteNotice = "工具参数 JSON 损坏，已保留当前输出。"
                        )
                    }
                }

                // Reset per-round separator on thinking when moving into next round
                if (roundThinking.isNotEmpty()) {
                    aggregatedThinking.append("\n\n")
                }

                val assistantMsgObj = JSONObject()
                    .put("role", "assistant")
                    .put("content", roundContent)
                    .put("reasoning_content", roundThinking)
                    .put("tool_calls", toolCalls)
                messages.put(assistantMsgObj)
                subturnMessages.put(assistantMsgObj)

                for (i in 0 until toolCalls.length()) {
                    if (handle.cancelled.get()) {
                        return buildFinalResponse(
                            ok = false,
                            errorMsg = "用户已手动停止生成",
                            incompleteNotice = "用户已停止执行工具，已保留当前结果。"
                        )
                    }
                    val tc = toolCalls.optJSONObject(i) ?: continue
                    val callId = tc.optString("id", "call_${rounds}_$i")
                    val fn = tc.optJSONObject("function") ?: JSONObject()
                    val fnName = fn.optString("name", "")
                    val fnArgs = fn.optString("arguments", "{}")

                    emit(StreamUiEvent.Status("正在执行工具：$fnName"))
                    val roundSourcesCollector = mutableListOf<JSONObject>()
                    val toolOutputObj = executeLocalTool(
                        name = fnName,
                        rawArguments = fnArgs,
                        workspaceRoot = workspaceRoot,
                        allowSdcardReadonly = allowSdcard,
                        shellTimeoutSecs = shellTimeoutSecs,
                        enabledTools = enabledTools,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        connectTimeoutSec = connectTimeoutSec,
                        readTimeoutSec = readTimeoutSec,
                        allowWeb = allowWeb,
                        collectedSources = roundSourcesCollector
                    )
                    if (roundSourcesCollector.isNotEmpty()) {
                        var added = false
                        for (s in roundSourcesCollector) {
                            val key = "${s.optString("url")}|${s.optString("title")}"
                            if (seenSourceKeys.add(key)) {
                                aggregatedSources.put(s)
                                added = true
                            }
                        }
                        if (added) {
                            onEvent?.invoke(StreamUiEvent.SourcesUpdated(CoreProtocol.parseSourcesArray(aggregatedSources)))
                        }
                    }

                    val toolOutputStr = toolOutputObj.toString()
                    CoreProtocol.AppLogger.info(
                        "Tool",
                        "[#$rounds] $fnName($fnArgs) -> ok=${toolOutputObj.optBoolean("ok", true)}"
                    )

                    val recordObj = JSONObject()
                        .put("round", rounds)
                        .put("id", callId)
                        .put("name", fnName)
                        .put("arguments", fnArgs)
                        .put("output", toolOutputStr)
                    localToolRecords.put(recordObj)

                    emit(
                        StreamUiEvent.ToolRecord(
                            LocalToolCallRecord(
                                round = rounds,
                                id = callId,
                                name = fnName,
                                arguments = fnArgs,
                                output = toolOutputStr
                            )
                        )
                    )

                    val toolReplyMsg = JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", toolOutputStr)
                    messages.put(toolReplyMsg)
                    subturnMessages.put(toolReplyMsg)
                }
                lastRoundContent = ""
            }

            return buildFinalResponse(
                ok = false,
                errorMsg = "达到最大工具轮次 ($maxToolRounds)",
                incompleteNotice = "已达到最大工具轮次，已保留当前输出。"
            )
        } finally {
            activeRequests.remove(requestId)
        }
    }

    private fun executeSingleStreamRound(
        apiKey: String,
        baseUrl: String,
        connectTimeoutSec: Int,
        readTimeoutSec: Int,
        payload: JSONObject,
        handle: ActiveRequestHandle,
        onThinkingDelta: (String) -> Unit,
        onContentDelta: (String) -> Unit,
        onStatus: (String) -> Unit
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
        handle.activeConn = conn

        val thinkingSb = StringBuilder()
        val contentSb = StringBuilder()
        val sources = JSONArray()
        val citations = JSONArray()
        val toolCallSlotMap = linkedMapOf<Int, ToolCallAccumulator>()
        var usageObj: JSONObject? = null
        var finishReason = ""
        var sawDone = false

        fun partialResult(ok: Boolean, err: String = "", notice: String = ""): JSONObject {
            val assembledToolCalls = JSONArray()
            for ((slot, acc) in toolCallSlotMap) {
                val fnName = acc.name.toString().trim()
                if (fnName.isEmpty()) continue
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
            return JSONObject()
                .put("ok", ok)
                .put("thinking", thinkingSb.toString())
                .put("content", contentSb.toString())
                .put("sources", sources)
                .put("citations", citations)
                .put("tool_calls", assembledToolCalls)
                .put("usage", usageObj ?: JSONObject())
                .put("finish_reason", finishReason)
                .put("error", err)
                .put("incomplete_notice", notice)
        }

        return try {
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bodyBytes) }

            val status = conn.responseCode
            if (status !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val msg = extractUpstreamError(errBody, status)
                CoreProtocol.AppLogger.error("Engine", "HTTP $status: $msg")
                return partialResult(ok = false, err = msg, notice = msg)
            }

            val contentType = (conn.contentType ?: "").lowercase()
            if (!contentType.contains("text/event-stream")) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return parseNonStreamingResponse(text).also { result ->
                    result.optString("thinking", "").takeIf { it.isNotEmpty() }?.let(onThinkingDelta)
                    result.optString("content", "").takeIf { it.isNotEmpty() }?.let(onContentDelta)
                }
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (handle.cancelled.get()) {
                        return partialResult(
                            ok = false,
                            err = "用户已手动停止生成",
                            notice = "用户已停止生成，已保留已流出的内容。"
                        )
                    }
                    val trimmed = line?.trim() ?: continue
                    if (!trimmed.startsWith("data:")) continue
                    val dataStr = trimmed.removePrefix("data:").trim()
                    if (dataStr.isEmpty()) continue
                    if (dataStr == "[DONE]") {
                        sawDone = true
                        break
                    }

                    val chunk = runCatching { JSONObject(dataStr) }.getOrElse {
                        return partialResult(
                            ok = false,
                            err = "流式 JSON 分片损坏: ${it.message}",
                            notice = "流式数据解析中断，已保留已流出的内容。"
                        )
                    }

                    // HTTP 200 mid-stream error chunk per DeepSeek docs
                    val streamErr = chunk.opt("error")
                    if (streamErr != null && streamErr != JSONObject.NULL) {
                        val msg = when (streamErr) {
                            is JSONObject -> streamErr.optString("message", streamErr.toString())
                            else -> streamErr.toString()
                        }
                        chunk.optJSONObject("usage")?.let { usageObj = it }
                        return partialResult(
                            ok = false,
                            err = "上游生成中断: $msg",
                            notice = "上游生成中断（$msg），已保留已流出的内容。"
                        )
                    }

                    chunk.optJSONObject("usage")?.let { usageObj = it }
                    collectSourcesFromObject(chunk, sources)

                    val choices = chunk.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val choice = choices.optJSONObject(i) ?: continue
                        val fr = choice.optString("finish_reason", "")
                        if (fr.isNotEmpty() && fr != "null") finishReason = fr

                        collectSourcesFromObject(choice, sources)
                        choice.optJSONObject("server_tool_event")?.let { ev ->
                            val evType = ev.optString("type", "")
                            if (evType == "call_started") {
                                val call = ev.optJSONObject("call")
                                val actType = call?.optJSONObject("action")?.optString("type", "").orEmpty()
                                onStatus("服务端联网：$actType")
                            } else if (evType == "call_completed") {
                                val call = ev.optJSONObject("call")
                                if (call != null) collectSourcesFromServerCall(call, sources)
                            }
                        }

                        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: continue
                        collectSourcesFromObject(delta, sources)
                        collectServerToolCallsSources(delta.optJSONArray("server_tool_calls"), sources)
                        collectCitationsFromDelta(delta, citations)

                        val rPiece = delta.optString("reasoning_content", "")
                        if (rPiece.isNotEmpty() && rPiece != "null") {
                            thinkingSb.append(rPiece)
                            onThinkingDelta(rPiece)
                        }
                        val cPiece = delta.optString("content", "")
                        if (cPiece.isNotEmpty() && cPiece != "null") {
                            contentSb.append(cPiece)
                            onContentDelta(cPiece)
                        }
                        val refusalPiece = delta.optString("refusal", "")
                        if (refusalPiece.isNotEmpty() && refusalPiece != "null") {
                            contentSb.append(refusalPiece)
                            onContentDelta(refusalPiece)
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

            if (handle.cancelled.get()) {
                return partialResult(
                    ok = false,
                    err = "用户已手动停止生成",
                    notice = "用户已停止生成，已保留已流出的内容。"
                )
            }
            if (!sawDone) {
                return partialResult(
                    ok = false,
                    err = "流式响应未收到 [DONE] 结束标志（连接提前断开）",
                    notice = "连接在流式完成前断开（缺少 [DONE]），已保留已流出的内容。"
                )
            }
            if (finishReason.isEmpty()) {
                return partialResult(
                    ok = false,
                    err = "流式响应缺少 finish_reason",
                    notice = "响应未包含正常的结束原因（finish_reason），已保留已流出的内容。"
                )
            }

            partialResult(ok = true)
        } catch (e: Exception) {
            if (handle.cancelled.get()) {
                partialResult(
                    ok = false,
                    err = "用户已手动停止生成",
                    notice = "用户已停止生成，已保留已流出的内容。"
                )
            } else {
                CoreProtocol.AppLogger.error("Engine", "Stream error: ${e.message}")
                partialResult(
                    ok = false,
                    err = "连接或流解析异常: ${e.message}",
                    notice = "流式连接异常（${e.message}），已保留已流出的内容。"
                )
            }
        } finally {
            handle.activeConn = null
            conn.disconnect()
        }
    }

    private fun parseNonStreamingResponse(raw: String): JSONObject {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "Invalid JSON response")
        val errField = obj.opt("error")
        if (errField != null && errField != JSONObject.NULL) {
            val detail = (errField as? JSONObject)?.optString("message", errField.toString()) ?: errField.toString()
            return JSONObject().put("ok", false).put("error", detail)
        }

        val sources = JSONArray()
        val citations = JSONArray()
        collectSourcesFromObject(obj, sources)

        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        collectSourcesFromObject(choice, sources)
        val msg = choice.optJSONObject("message") ?: JSONObject()
        collectSourcesFromObject(msg, sources)
        collectServerToolCallsSources(msg.optJSONArray("server_tool_calls"), sources)
        collectCitationsFromDelta(msg, citations)

        val rawToolCalls = msg.optJSONArray("tool_calls") ?: JSONArray()
        val filteredToolCalls = JSONArray()
        for (i in 0 until rawToolCalls.length()) {
            val tc = rawToolCalls.optJSONObject(i) ?: continue
            val fnName = tc.optJSONObject("function")?.optString("name", "").orEmpty()
            if (fnName.isNotEmpty()) {
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

    private fun collectSourcesFromServerCall(call: JSONObject, out: JSONArray) {
        val srcArr = call.optJSONArray("sources")
        if (srcArr != null) {
            for (i in 0 until srcArr.length()) {
                val item = srcArr.optJSONObject(i) ?: continue
                val link = item.optString("url", "").trim()
                val title = item.optString("title", "").trim()
                if (link.isEmpty() && title.isEmpty()) continue
                out.put(
                    JSONObject()
                        .put("title", title.ifEmpty { link })
                        .put("url", link)
                        .put("snippet", item.optString("snippet", ""))
                        .put("site_name", item.optString("site_name", ""))
                        .put("published_at", item.optString("published_at", ""))
                )
            }
        }
        val page = call.optJSONObject("page")
        if (page != null) {
            val link = page.optString("url", "").trim()
            val title = page.optString("title", "").trim()
            if (link.isNotEmpty() || title.isNotEmpty()) {
                out.put(
                    JSONObject()
                        .put("title", title.ifEmpty { link })
                        .put("url", link)
                        .put("published_at", page.optString("published_at", ""))
                )
            }
        }
    }

    private fun collectServerToolCallsSources(serverCalls: JSONArray?, out: JSONArray) {
        if (serverCalls == null) return
        for (i in 0 until serverCalls.length()) {
            val step = serverCalls.optJSONObject(i) ?: continue
            val calls = step.optJSONArray("calls") ?: continue
            for (j in 0 until calls.length()) {
                val call = calls.optJSONObject(j) ?: continue
                collectSourcesFromServerCall(call, out)
            }
        }
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
            val uc = item.optJSONObject("url_citation")
            if (uc != null) {
                val endIndex = uc.optInt("end_index", -1)
                val startIndex = uc.optInt("start_index", -1)
                val url = uc.optString("url", "")
                val title = uc.optString("title", "")
                if (endIndex >= 0 && url.isNotBlank()) {
                    out.put(
                        JSONObject()
                            .put("offset", endIndex)
                            .put("start_index", startIndex)
                            .put("end_index", endIndex)
                            .put("url", url)
                            .put("title", title)
                            .put("indexes", JSONArray().put(out.length() + 1))
                    )
                }
                continue
            }
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

    private fun clientWebSearchDecl(): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "Web search query (1-128 chars)"))
                    .put("count", JSONObject().put("type", "integer").put("description", "Max results (1-20, default 8)"))
                    .put("need_content", JSONObject().put("type", "boolean").put("description", "Include full page text (default false)"))
            )
            .put("required", JSONArray().put("query"))
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "web_search")
                    .put("description", "Search the live web using DeepSeek Search API (/v1/tools/search) alongside local Work tools.")
                    .put("parameters", params)
            )
    }

    private fun clientWebFetchDecl(): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("url", JSONObject().put("type", "string").put("description", "HTTP/HTTPS URL to fetch as Markdown"))
            )
            .put("required", JSONArray().put("url"))
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "web_fetch")
                    .put("description", "Fetch and extract Markdown content of a webpage via DeepSeek Fetch API (/v1/tools/fetch).")
                    .put("parameters", params)
            )
    }

    private class ToolCallAccumulator {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private class UsageAccumulator {
        var promptTokens: Int = 0
        var completionTokens: Int = 0
        var totalTokens: Int = 0
        var cacheHitTokens: Int = 0
        var cacheMissTokens: Int = 0
        var webSearchReqs: Int = 0
        var readUrlReqs: Int = 0

        fun add(u: JSONObject) {
            promptTokens += u.optInt("prompt_tokens", 0)
            completionTokens += u.optInt("completion_tokens", 0)
            totalTokens += u.optInt("total_tokens", 0)
            cacheHitTokens += u.optInt("prompt_cache_hit_tokens", 0)
            cacheMissTokens += u.optInt("prompt_cache_miss_tokens", 0)
            u.optJSONObject("server_tool_use")?.let { stu ->
                webSearchReqs += stu.optInt("web_search_requests", 0) + stu.optInt("tool_search", 0)
                readUrlReqs += stu.optInt("read_url_requests", 0) + stu.optInt("tool_fetch", 0)
            }
        }

        fun toJson(): JSONObject {
            val obj = JSONObject()
                .put("prompt_tokens", promptTokens)
                .put("completion_tokens", completionTokens)
                .put("total_tokens", totalTokens)
                .put("prompt_cache_hit_tokens", cacheHitTokens)
                .put("prompt_cache_miss_tokens", cacheMissTokens)
            if (webSearchReqs > 0 || readUrlReqs > 0) {
                obj.put(
                    "server_tool_use",
                    JSONObject()
                        .put("web_search_requests", webSearchReqs)
                        .put("read_url_requests", readUrlReqs)
                )
            }
            return obj
        }
    }
}
