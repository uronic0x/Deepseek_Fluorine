package com.uronic0x.fluorine.bridge

import android.content.Context
import com.uronic0x.fluorine.protocol.ChatAttachment
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.LocalToolCallRecord
import com.uronic0x.fluorine.protocol.SearchSource
import com.uronic0x.fluorine.protocol.StreamUiEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Rust-only JNI bridge between Android UI/ViewModel and `libfluorine.so` (`src/lib.rs`).
 *
 * There is intentionally NO Kotlin HTTP/tool fallback path: all chat streaming, model discovery,
 * file uploads, local tool execution, and upstream payload building route through Rust JNI.
 * An internal [NativeBackend] seam is provided solely for JVM unit tests where the Android
 * `arm64-v8a` `.so` cannot be loaded on the host.
 */
object FluorineBridge {

    internal interface NativeBackend {
        val isLoaded: Boolean
        fun getMeta(workspacePath: String, allowSdcardReadonly: Boolean): String
        fun fetchModels(
            apiKey: String,
            baseUrl: String,
            connectTimeoutSec: Int,
            readTimeoutSec: Int
        ): String

        fun uploadFile(
            apiKey: String,
            baseUrl: String,
            filePath: String,
            filename: String,
            mimeType: String,
            purpose: String,
            allowedRootsNewline: String,
            connectTimeoutSec: Int,
            readTimeoutSec: Int
        ): String

        fun streamChat(
            requestId: Long,
            apiKey: String,
            requestJson: String,
            workspacePath: String,
            allowSdcardReadonly: Boolean,
            callback: JniStreamCallback
        )

        fun cancelStream(requestId: Long): Boolean
        fun executeLocalTool(
            name: String,
            argumentsJson: String,
            workspacePath: String,
            allowSdcardReadonly: Boolean
        ): String

        fun buildUpstreamPayload(requestJson: String): String
    }

    private const val MAX_STREAM_ACCUMULATED_CHARS = 2 * 1024 * 1024 // 2 MiB guard
    private const val MISSING_SO_ERROR =
        "Rust 原生引擎 libfluorine.so 未加载，当前版本不提供 Kotlin 回退，请重新构建包含 arm64-v8a 原生库的安装包"

    private val rustLibraryLoaded: Boolean = try {
        System.loadLibrary("fluorine")
        true
    } catch (_: Throwable) {
        false
    }

    private val jniBackend: NativeBackend = object : NativeBackend {
        override val isLoaded: Boolean get() = rustLibraryLoaded

        override fun getMeta(workspacePath: String, allowSdcardReadonly: Boolean): String =
            nativeGetMeta(workspacePath, allowSdcardReadonly)

        override fun fetchModels(
            apiKey: String,
            baseUrl: String,
            connectTimeoutSec: Int,
            readTimeoutSec: Int
        ): String = nativeFetchModels(apiKey, baseUrl, connectTimeoutSec, readTimeoutSec)

        override fun uploadFile(
            apiKey: String,
            baseUrl: String,
            filePath: String,
            filename: String,
            mimeType: String,
            purpose: String,
            allowedRootsNewline: String,
            connectTimeoutSec: Int,
            readTimeoutSec: Int
        ): String = nativeUploadFile(
            apiKey,
            baseUrl,
            filePath,
            filename,
            mimeType,
            purpose,
            allowedRootsNewline,
            connectTimeoutSec,
            readTimeoutSec
        )

        override fun streamChat(
            requestId: Long,
            apiKey: String,
            requestJson: String,
            workspacePath: String,
            allowSdcardReadonly: Boolean,
            callback: JniStreamCallback
        ) = nativeStreamChat(
            requestId,
            apiKey,
            requestJson,
            workspacePath,
            allowSdcardReadonly,
            callback
        )

        override fun cancelStream(requestId: Long): Boolean = nativeCancelStream(requestId)

        override fun executeLocalTool(
            name: String,
            argumentsJson: String,
            workspacePath: String,
            allowSdcardReadonly: Boolean
        ): String = nativeExecuteLocalTool(name, argumentsJson, workspacePath, allowSdcardReadonly)

        override fun buildUpstreamPayload(requestJson: String): String =
            nativeBuildUpstreamPayload(requestJson)
    }

    @Volatile
    private var backend: NativeBackend = jniBackend

    private var defaultWorkspaceDir: File =
        File(System.getProperty("java.io.tmpdir", "/tmp"), "fluorine_workspace")
    private var managedAttachmentRoots: List<File> = emptyList()
    private var allowSdcardReadonly: Boolean = false
    private val nextReqId = AtomicLong(1L)
    private val activeCancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    internal fun setBackendForTesting(customBackend: NativeBackend?) {
        backend = customBackend ?: jniBackend
    }

    internal fun setManagedAttachmentRootsForTesting(roots: List<File>) {
        managedAttachmentRoots = roots
    }

    fun initWorkspace(context: Context): File {
        val dir = File(context.filesDir, "workspace")
        dir.mkdirs()
        defaultWorkspaceDir = dir
        initManagedDirs(context)
        return dir
    }

    fun initManagedDirs(context: Context) {
        val attDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val camDir = File(context.filesDir, "camera_captures").apply { mkdirs() }
        managedAttachmentRoots = listOf(attDir, camDir)
    }

    /** 是否成功加载了 Rust 原生引擎（`libfluorine.so`）。 */
    val isRustJniLoaded: Boolean get() = backend.isLoaded

    fun getWorkspaceDir(): File = defaultWorkspaceDir

    fun getManagedAttachmentRoots(): List<File> = managedAttachmentRoots

    fun setAllowSdcardReadonly(allow: Boolean) {
        allowSdcardReadonly = allow
    }

    fun nextRequestId(): Long = nextReqId.getAndIncrement()

    fun engineInfoJson(): String {
        if (!backend.isLoaded) {
            return JSONObject()
                .put("ok", false)
                .put("app_name", "Fluorine")
                .put("display_name", "Fluorine")
                .put("engine", "rust-jni-missing")
                .put("error", MISSING_SO_ERROR)
                .toString()
        }
        return runCatching {
            backend.getMeta(defaultWorkspaceDir.absolutePath, allowSdcardReadonly)
        }.getOrElse { err ->
            errorJson("读取 Rust 引擎元信息失败: ${err.message}")
        }
    }

    fun discoverCapabilitiesJson(
        apiKey: String,
        baseUrl: String = CoreProtocol.DEFAULT_API_BASE_URL,
        connectTimeoutSec: Int = 15,
        readTimeoutSec: Int = 30
    ): String {
        if (!backend.isLoaded) return errorJson(MISSING_SO_ERROR)
        val rawBase = baseUrl.trim().ifEmpty { CoreProtocol.DEFAULT_API_BASE_URL }
        val validBase = CoreProtocol.validateBaseUrl(rawBase).getOrElse { err ->
            return errorJson(err.message ?: "Base URL 格式不合法")
        }
        val rawResp = runCatching {
            backend.fetchModels(
                apiKey = apiKey,
                baseUrl = validBase,
                connectTimeoutSec = connectTimeoutSec.coerceIn(5, 120),
                readTimeoutSec = readTimeoutSec.coerceIn(30, 600)
            )
        }.getOrElse { err ->
            return errorJson("调用 Rust 模型探测失败: ${err.message}")
        }
        return normalizeSimpleResponseError(rawResp)
    }

    fun uploadAttachmentFileJson(
        apiKey: String,
        baseUrl: String,
        attachment: ChatAttachment,
        connectTimeoutSec: Int = 20,
        readTimeoutSec: Int = 150
    ): String {
        if (!backend.isLoaded) return errorJson(MISSING_SO_ERROR)
        val rawBase = baseUrl.trim().ifEmpty { CoreProtocol.DEFAULT_API_BASE_URL }
        val validBase = CoreProtocol.validateBaseUrl(rawBase).getOrElse { err ->
            return errorJson(err.message ?: "Base URL 格式不合法")
        }
        val clean = CoreProtocol.sanitizeAttachment(attachment)
        if (clean.localPath.isBlank()) {
            return errorJson("附件本地文件不可用，请重新选择附件后再发送")
        }
        val allowedRootsStr = managedAttachmentRoots
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            .distinct()
            .joinToString("\n")
        if (allowedRootsStr.isBlank()) {
            return errorJson("附件托管目录未初始化，拒绝上传本地文件")
        }
        val rawResp = runCatching {
            backend.uploadFile(
                apiKey = apiKey,
                baseUrl = validBase,
                filePath = clean.localPath,
                filename = clean.name,
                mimeType = clean.mimeType,
                purpose = clean.parsePurpose,
                allowedRootsNewline = allowedRootsStr,
                connectTimeoutSec = connectTimeoutSec.coerceIn(5, 120),
                readTimeoutSec = readTimeoutSec.coerceIn(30, 600)
            )
        }.getOrElse { err ->
            return errorJson("调用 Rust 文件上传失败: ${err.message}")
        }
        return normalizeSimpleResponseError(rawResp)
    }

    fun chatCompleteJson(requestJson: String): String {
        val reqId = nextRequestId()
        return chatCompleteStreamingJson(requestId = reqId, requestJson = requestJson, onEvent = {})
    }

    fun chatCompleteStreamingJson(
        requestId: Long,
        requestJson: String,
        onEvent: (StreamUiEvent) -> Unit
    ): String {
        if (!backend.isLoaded) return errorJson(MISSING_SO_ERROR)

        val reqObj = runCatching { JSONObject(requestJson) }.getOrElse { err ->
            return errorJson("请求 JSON 格式错误: ${err.message}")
        }
        val apiKey = reqObj.optString("api_key", "").trim()
        val model = reqObj.optString("model", "deepseek-flash").trim().ifEmpty { "deepseek-flash" }
        val stage = reqObj.optString("stage", "chat").trim().ifEmpty { "chat" }
        val workspacePath = reqObj.optString("workspace_dir", "")
            .trim()
            .ifEmpty { defaultWorkspaceDir.absolutePath }
        val allowStorage = reqObj.optBoolean("allow_sdcard_readonly", allowSdcardReadonly)

        val rawBaseUrl = reqObj.optString("base_url", "").trim()
        if (rawBaseUrl.isNotEmpty()) {
            val validBase = CoreProtocol.validateBaseUrl(rawBaseUrl).getOrElse { err ->
                return errorJson(err.message ?: "Base URL 格式不合法")
            }
            reqObj.put("base_url", validBase)
        }
        reqObj.remove("api_key")

        val cancelFlag = AtomicBoolean(false)
        activeCancelFlags[requestId] = cancelFlag

        val thinkingSb = StringBuilder()
        val contentSb = StringBuilder()
        var currentRound = 0
        var maxObservedRound = 1
        var sawDone = false
        var finishReason = ""
        var errorCode = ""
        var errorMessage = ""
        var usageObj: JSONObject? = null
        var latestWorkSteps: JSONArray? = null
        var latestToolCalls: JSONArray? = null
        var latestSubturnMessages: JSONArray? = null
        val emittedStepRounds = mutableSetOf<Int>()
        var emittedToolCount = 0
        val sourcesByUrl = linkedMapOf<String, SearchSource>()
        val citationsList = mutableListOf<JSONObject>()
        var overflowed = false

        fun ensureRoundStarted(round: Int) {
            if (round > currentRound) {
                currentRound = round
                if (round > maxObservedRound) maxObservedRound = round
                onEvent(StreamUiEvent.RoundStarted(round))
            }
        }

        fun mergeSourcesFromArray(arr: JSONArray?) {
            if (arr == null || arr.length() == 0) return
            var changed = false
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optString("url", "").trim()
                val title = s.optString("title", "").ifBlank { url.ifBlank { "来源 #${sourcesByUrl.size + 1}" } }
                val key = url.ifBlank { "$title#$i" }
                val next = SearchSource(
                    title = title,
                    url = url,
                    snippet = s.optString("snippet", ""),
                    siteName = s.optString("site_name", ""),
                    publishedAt = s.optString("published_at", ""),
                    citeIndex = if (s.has("cite_index") && !s.isNull("cite_index")) s.optInt("cite_index") else null
                )
                if (sourcesByUrl[key] != next) {
                    sourcesByUrl[key] = next
                    changed = true
                }
            }
            if (changed) {
                onEvent(StreamUiEvent.SourcesUpdated(sourcesByUrl.values.toList()))
            }
        }

        fun mergeAnnotationsFromArray(arr: JSONArray?) {
            if (arr == null || arr.length() == 0) return
            var sourcesChanged = false
            for (i in 0 until arr.length()) {
                val raw = arr.optJSONObject(i) ?: continue
                val cit = raw.optJSONObject("url_citation") ?: raw
                val url = cit.optString("url", "").trim()
                val title = cit.optString("title", "").ifBlank { url }
                val startIdx = cit.optInt("start_index", -1)
                val endIdx = cit.optInt("end_index", cit.optInt("offset", -1))
                val offset = cit.optInt("offset", endIdx)
                if (url.isNotEmpty() && !sourcesByUrl.containsKey(url)) {
                    val idx = sourcesByUrl.size + 1
                    sourcesByUrl[url] = SearchSource(title = title, url = url, citeIndex = idx)
                    sourcesChanged = true
                }
                val idxArr = cit.optJSONArray("indexes") ?: JSONArray().apply {
                    val existingIdx = sourcesByUrl.values.indexOfFirst { it.url == url }
                    if (existingIdx >= 0) put(existingIdx + 1)
                }
                if (offset >= 0 && (idxArr.length() > 0 || url.isNotEmpty())) {
                    citationsList.add(
                        JSONObject()
                            .put("offset", offset)
                            .put("indexes", idxArr)
                            .put("start_index", startIdx)
                            .put("end_index", endIdx)
                            .put("url", url)
                            .put("title", title)
                    )
                }
            }
            if (sourcesChanged) {
                onEvent(StreamUiEvent.SourcesUpdated(sourcesByUrl.values.toList()))
            }
        }

        fun appendBounded(sb: StringBuilder, text: String): Boolean {
            if (text.isEmpty()) return true
            if (thinkingSb.length + contentSb.length + text.length > MAX_STREAM_ACCUMULATED_CHARS) {
                overflowed = true
                errorCode = "size_limit"
                errorMessage = "单轮输出累计超过 2 MiB 安全上限，已中止接收"
                cancelFlag.set(true)
                runCatching { backend.cancelStream(requestId) }
                return false
            }
            sb.append(text)
            return true
        }

        // org.json `optString` 在字段存在但值为 JSON null 时会返回字面量 "null"，
        // 上游 SSE 在推理阶段常发 `{"content": null}`，必须显式判空再取值。
        fun JSONObject.strOrEmpty(key: String): String =
            if (isNull(key)) "" else optString(key, "")

        val callback = object : JniStreamCallback {
            override fun onEvent(eventJson: String): Boolean {
                if (cancelFlag.get()) return false
                val trimmed = eventJson.trim()
                if (trimmed.isEmpty()) return true
                if (trimmed == "[DONE]") {
                    sawDone = true
                    return true
                }

                val ev = runCatching { JSONObject(trimmed) }.getOrNull() ?: return true

                ev.optJSONObject("usage")?.let { usageObj = it }
                ev.optJSONArray("work_steps")?.let { latestWorkSteps = it }
                ev.optJSONArray("subturn_messages")?.let { latestSubturnMessages = it }

                if (ev.has("error") && !ev.isNull("error")) {
                    val errObj = ev.optJSONObject("error")
                    if (errObj != null) {
                        errorCode = errObj.optString("code", errorCode)
                        errorMessage = errObj.optString("message", "").ifBlank { errObj.toString() }
                    } else {
                        errorMessage = ev.optString("error", "未知流式错误")
                    }
                    return false
                }

                // Local tool lifecycle events emitted by Rust `run_local_tool_loop_sink`
                val localToolEvent = ev.optJSONObject("local_tool_event")
                if (localToolEvent != null) {
                    when (localToolEvent.optString("type", "")) {
                        "round_started" -> {
                            val r = localToolEvent.optInt("round", currentRound + 1).coerceAtLeast(1)
                            ensureRoundStarted(r)
                            onEvent(StreamUiEvent.Status("第 $r 轮自主推理中..."))
                        }
                        "call_started" -> {
                            val toolName = localToolEvent.optString("name", "tool")
                            onEvent(StreamUiEvent.Status("正在执行本地工具：$toolName"))
                        }
                        "call_completed" -> {
                            val toolName = localToolEvent.optString("name", "tool")
                            onEvent(StreamUiEvent.Status("本地工具完成：$toolName"))
                        }
                    }
                }

                val choices = ev.optJSONArray("choices")
                val choice0 = choices?.optJSONObject(0)
                if (choice0 != null) {
                    val fr = choice0.optString("finish_reason", "")
                    if (fr.isNotBlank() && fr != "null") {
                        finishReason = fr
                    }

                    val serverToolEv = choice0.optJSONObject("server_tool_event")
                    if (serverToolEv != null) {
                        val toolName = serverToolEv.optString("name", "").ifBlank { "联网检索" }
                        onEvent(StreamUiEvent.Status("服务端联网处理：$toolName"))
                    }

                    val delta = choice0.optJSONObject("delta")
                    if (delta != null) {
                        val deltaReasoning = delta.strOrEmpty("reasoning_content")
                        val deltaContent = delta.strOrEmpty("content")
                        val deltaWorkSteps = delta.optJSONArray("work_steps")
                        val deltaTools = delta.optJSONArray("local_tool_calls")
                        val deltaSubturns = delta.optJSONArray("subturn_messages")
                        val deltaSources = delta.optJSONArray("sources")
                        val deltaAnnotations = delta.optJSONArray("annotations")

                        if (deltaWorkSteps != null) {
                            latestWorkSteps = deltaWorkSteps
                            val lastStep = deltaWorkSteps.optJSONObject(deltaWorkSteps.length() - 1)
                            if (lastStep != null) {
                                val stepRound = lastStep.optInt("round", currentRound.coerceAtLeast(1))
                                ensureRoundStarted(stepRound)
                                if (emittedStepRounds.add(stepRound)) {
                                    val stepThink = lastStep.optString("thinking", "")
                                    val stepContent = lastStep.optString("content", "")
                                    if (stepThink.isNotEmpty()) {
                                        if (!appendBounded(thinkingSb, stepThink)) return false
                                        onEvent(StreamUiEvent.ThinkingDelta(stepThink))
                                    }
                                    if (stepContent.isNotEmpty()) {
                                        if (!appendBounded(contentSb, stepContent)) return false
                                        onEvent(StreamUiEvent.ContentDelta(stepContent))
                                    }
                                    mergeAnnotationsFromArray(lastStep.optJSONArray("annotations"))
                                }
                            }
                        } else {
                            // Pure SSE streaming delta (chat mode / non-tool stream)
                            if (deltaReasoning.isNotEmpty() || deltaContent.isNotEmpty()) {
                                if (currentRound == 0) ensureRoundStarted(1)
                            }
                            if (deltaReasoning.isNotEmpty()) {
                                if (!appendBounded(thinkingSb, deltaReasoning)) return false
                                onEvent(StreamUiEvent.ThinkingDelta(deltaReasoning))
                            }
                            if (deltaContent.isNotEmpty()) {
                                if (!appendBounded(contentSb, deltaContent)) return false
                                onEvent(StreamUiEvent.ContentDelta(deltaContent))
                            }
                        }

                        if (deltaTools != null) {
                            latestToolCalls = deltaTools
                            while (emittedToolCount < deltaTools.length()) {
                                val tc = deltaTools.optJSONObject(emittedToolCount)
                                emittedToolCount++
                                if (tc != null) {
                                    val tcRound = tc.optInt("round", currentRound.coerceAtLeast(1))
                                    ensureRoundStarted(tcRound)
                                    val output = tc.optString("output", "").ifBlank {
                                        tc.optString("result", "")
                                    }
                                    onEvent(
                                        StreamUiEvent.ToolRecord(
                                            LocalToolCallRecord(
                                                round = tcRound,
                                                id = tc.optString("id", ""),
                                                name = tc.optString("name", ""),
                                                arguments = tc.optString("arguments", "{}"),
                                                output = output
                                            )
                                        )
                                    )
                                }
                            }
                        }

                        if (deltaSubturns != null) {
                            latestSubturnMessages = deltaSubturns
                        }
                        mergeSourcesFromArray(deltaSources)
                        mergeAnnotationsFromArray(deltaAnnotations)
                    }
                }
                return !cancelFlag.get()
            }
        }

        try {
            backend.streamChat(
                requestId = requestId,
                apiKey = apiKey,
                requestJson = reqObj.toString(),
                workspacePath = workspacePath,
                allowSdcardReadonly = allowStorage,
                callback = callback
            )
        } catch (err: Throwable) {
            errorMessage = "Rust 流式调用异常: ${err.message}"
        } finally {
            activeCancelFlags.remove(requestId)
        }

        val wasCancelled = cancelFlag.get() || errorCode == "cancelled"
        if (wasCancelled && errorMessage.isBlank()) {
            errorMessage = "用户已手动停止生成"
        }

        val finalWorkSteps = normalizeWorkStepsForAndroid(latestWorkSteps)
        val finalContent = if (finalWorkSteps.length() > 0) {
            finalWorkSteps.optJSONObject(finalWorkSteps.length() - 1)
                ?.optString("content", "")
                .orEmpty()
                .ifBlank { contentSb.toString() }
        } else {
            contentSb.toString()
        }
        val finalThinking = thinkingSb.toString()

        val ok = errorMessage.isBlank() && !wasCancelled && !overflowed &&
            (sawDone || finalContent.isNotBlank() || finalThinking.isNotBlank() ||
                (latestToolCalls != null && latestToolCalls!!.length() > 0))
        if (!ok && errorMessage.isBlank()) {
            errorMessage = if (finishReason == "content_filter") {
                "输出触发上游内容安全审核，已中止"
            } else {
                "流式连接异常中断，未收到完整结束标记"
            }
        }

        val sourcesJson = JSONArray().apply {
            for (s in sourcesByUrl.values) {
                put(
                    JSONObject()
                        .put("title", s.title)
                        .put("url", s.url)
                        .put("snippet", s.snippet)
                        .put("site_name", s.siteName)
                        .put("published_at", s.publishedAt)
                        .put("cite_index", s.citeIndex)
                )
            }
        }
        val citationsJson = JSONArray().apply {
            for (c in citationsList) put(c)
        }
        val toolCallsJson = normalizeToolCallsForAndroid(latestToolCalls)

        return JSONObject()
            .put("ok", ok)
            .put("model", model)
            .put("stage", stage)
            .put("content", finalContent)
            .put("thinking", finalThinking)
            .put("sources", sourcesJson)
            .put("citations", citationsJson)
            .put("local_tool_calls", toolCallsJson)
            .put("work_steps", finalWorkSteps)
            .put("subturn_messages", latestSubturnMessages ?: JSONArray())
            .put("usage", usageObj ?: JSONObject())
            .put("finish_reason", finishReason)
            .put("rounds", maxObservedRound.coerceAtLeast(finalWorkSteps.length().coerceAtLeast(1)))
            .put("incomplete_notice", if (!ok) errorMessage else "")
            .put("error", if (!ok) errorMessage else "")
            .toString()
    }

    fun cancelStream(requestId: Long): Boolean {
        activeCancelFlags[requestId]?.set(true)
        if (!backend.isLoaded) return false
        return runCatching { backend.cancelStream(requestId) }.getOrDefault(false)
    }

    fun executeLocalToolJson(
        name: String,
        argumentsJson: String,
        allowSdcardReadonly: Boolean = false
    ): String {
        if (!backend.isLoaded) return errorJson(MISSING_SO_ERROR)
        return runCatching {
            backend.executeLocalTool(
                name = name,
                argumentsJson = argumentsJson,
                workspacePath = defaultWorkspaceDir.absolutePath,
                allowSdcardReadonly = allowSdcardReadonly
            )
        }.getOrElse { err ->
            errorJson("Rust 本地工具执行失败: ${err.message}")
        }
    }

    fun buildUpstreamPayloadJson(requestJson: String): String {
        if (!backend.isLoaded) return errorJson(MISSING_SO_ERROR)
        return runCatching {
            backend.buildUpstreamPayload(requestJson)
        }.getOrElse { err ->
            errorJson("Rust 构建上游请求失败: ${err.message}")
        }
    }

    private fun normalizeToolCallsForAndroid(raw: JSONArray?): JSONArray {
        val out = JSONArray()
        if (raw == null) return out
        for (i in 0 until raw.length()) {
            val tc = raw.optJSONObject(i) ?: continue
            val output = tc.optString("output", "").ifBlank { tc.optString("result", "") }
            out.put(
                JSONObject()
                    .put("round", tc.optInt("round", 1))
                    .put("id", tc.optString("id", ""))
                    .put("name", tc.optString("name", ""))
                    .put("arguments", tc.optString("arguments", "{}"))
                    .put("output", output)
            )
        }
        return out
    }

    private fun normalizeWorkStepsForAndroid(raw: JSONArray?): JSONArray {
        val out = JSONArray()
        if (raw == null) return out
        for (i in 0 until raw.length()) {
            val step = raw.optJSONObject(i) ?: continue
            val cits = JSONArray()
            val rawCits = step.optJSONArray("citations") ?: step.optJSONArray("annotations")
            if (rawCits != null) {
                for (j in 0 until rawCits.length()) {
                    val item = rawCits.optJSONObject(j) ?: continue
                    val cit = item.optJSONObject("url_citation") ?: item
                    val offset = cit.optInt("offset", cit.optInt("end_index", -1))
                    if (offset >= 0) {
                        cits.put(
                            JSONObject()
                                .put("offset", offset)
                                .put("indexes", cit.optJSONArray("indexes") ?: JSONArray())
                                .put("start_index", cit.optInt("start_index", -1))
                                .put("end_index", cit.optInt("end_index", offset))
                                .put("url", cit.optString("url", ""))
                                .put("title", cit.optString("title", ""))
                        )
                    }
                }
            }
            out.put(
                JSONObject()
                    .put("round", step.optInt("round", i + 1))
                    .put("content", step.optString("content", ""))
                    .put("thinking", step.optString("thinking", ""))
                    .put("local_tool_calls", normalizeToolCallsForAndroid(step.optJSONArray("local_tool_calls")))
                    .put("citations", cits)
            )
        }
        return out
    }

    private fun normalizeSimpleResponseError(rawResp: String): String {
        val parsed = runCatching { JSONObject(rawResp) }.getOrNull() ?: return errorJson(rawResp)
        if (!parsed.optBoolean("ok", false)) {
            val errObj = parsed.optJSONObject("error")
            if (errObj != null) {
                val msg = errObj.optString("message", "").ifBlank { errObj.toString() }
                val code = errObj.optString("code", "")
                parsed.put("error", msg)
                if (code.isNotBlank()) parsed.put("error_code", code)
                return parsed.toString()
            }
        }
        return rawResp
    }

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    interface JniStreamCallback {
        fun onEvent(eventJson: String): Boolean
    }

    @JvmStatic
    private external fun nativeGetMeta(workspacePath: String, allowSdcardReadonly: Boolean): String

    @JvmStatic
    private external fun nativeFetchModels(
        apiKey: String,
        baseUrl: String,
        connectTimeoutSec: Int,
        readTimeoutSec: Int
    ): String

    @JvmStatic
    private external fun nativeUploadFile(
        apiKey: String,
        baseUrl: String,
        filePath: String,
        filename: String,
        mimeType: String,
        purpose: String,
        allowedRootsNewline: String,
        connectTimeoutSec: Int,
        readTimeoutSec: Int
    ): String

    @JvmStatic
    private external fun nativeStreamChat(
        requestId: Long,
        apiKey: String,
        requestJson: String,
        workspacePath: String,
        allowSdcardReadonly: Boolean,
        callback: JniStreamCallback
    )

    @JvmStatic
    private external fun nativeCancelStream(requestId: Long): Boolean

    @JvmStatic
    private external fun nativeExecuteLocalTool(
        name: String,
        argumentsJson: String,
        workspacePath: String,
        allowSdcardReadonly: Boolean
    ): String

    @JvmStatic
    private external fun nativeBuildUpstreamPayload(requestJson: String): String
}
