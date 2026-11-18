package com.uronic0x.fluorine.bridge

import android.content.Context
import com.uronic0x.fluorine.engine.KotlinNativeEngine
import com.uronic0x.fluorine.protocol.ChatAttachment
import com.uronic0x.fluorine.protocol.StreamUiEvent
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridge between Android UI/ViewModel and the Fluorine Engine.
 * Aligns JNI symbols with `src/lib.rs` (`nativeGetMeta`, `nativeFetchModels`,
 * `nativeStreamChat`, `nativeCancelStream`) and delegates streaming + multimodal
 * execution through `KotlinNativeEngine` with instant cancellation.
 */
object FluorineBridge {

    private var rustJniAvailable: Boolean = false
    private var defaultWorkspaceDir: File = File(System.getProperty("java.io.tmpdir", "/tmp"), "fluorine_workspace")
    private var allowSdcardReadonly: Boolean = false
    private val nextReqId = AtomicLong(1L)

    init {
        rustJniAvailable = try {
            System.loadLibrary("fluorine")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun initWorkspace(context: Context): File {
        val dir = File(context.filesDir, "workspace")
        dir.mkdirs()
        defaultWorkspaceDir = dir
        return dir
    }

    /** 是否成功加载了 Rust 引擎（`libfluorine.so`）。 */
    val isRustJniLoaded: Boolean get() = rustJniAvailable

    fun getWorkspaceDir(): File = defaultWorkspaceDir

    fun setAllowSdcardReadonly(allow: Boolean) {
        allowSdcardReadonly = allow
    }

    fun nextRequestId(): Long = nextReqId.getAndIncrement()

    fun engineInfoJson(): String {
        if (rustJniAvailable) {
            val res = runCatching { nativeGetMeta(defaultWorkspaceDir.absolutePath, allowSdcardReadonly) }.getOrNull()
            if (!res.isNullOrBlank()) return res
        }
        return KotlinNativeEngine.engineInfoJson(rustJniAvailable)
    }

    fun discoverCapabilitiesJson(
        apiKey: String,
        baseUrl: String = "https://api.deepseek.com",
        connectTimeoutSec: Int = 15,
        readTimeoutSec: Int = 30
    ): String {
        val normalizedBase = KotlinNativeEngine.normalizeBaseUrl(baseUrl)
        if (rustJniAvailable && normalizedBase == "https://api.deepseek.com") {
            val res = runCatching { nativeFetchModels(apiKey) }.getOrNull()
            if (!res.isNullOrBlank()) return res
        }
        return KotlinNativeEngine.discoverCapabilities(
            apiKey = apiKey,
            baseUrl = normalizedBase,
            connectTimeoutSec = connectTimeoutSec,
            readTimeoutSec = readTimeoutSec
        )
    }

    fun uploadAttachmentFileJson(
        apiKey: String,
        baseUrl: String,
        attachment: ChatAttachment,
        connectTimeoutSec: Int = 20,
        readTimeoutSec: Int = 150
    ): String {
        return KotlinNativeEngine.uploadFile(
            apiKey = apiKey,
            baseUrl = baseUrl,
            attachment = attachment,
            connectTimeoutSec = connectTimeoutSec,
            readTimeoutSec = readTimeoutSec
        ).toString()
    }

    fun chatCompleteJson(requestJson: String): String {
        val reqId = nextRequestId()
        return KotlinNativeEngine.chatCompleteStreaming(
            requestId = reqId,
            requestJson = requestJson,
            defaultWorkspaceDir = defaultWorkspaceDir,
            onEvent = null
        )
    }

    fun chatCompleteStreamingJson(
        requestId: Long,
        requestJson: String,
        onEvent: (StreamUiEvent) -> Unit
    ): String {
        return KotlinNativeEngine.chatCompleteStreaming(
            requestId = requestId,
            requestJson = requestJson,
            defaultWorkspaceDir = defaultWorkspaceDir,
            onEvent = onEvent
        )
    }

    fun cancelStream(requestId: Long): Boolean {
        val ktCancelled = KotlinNativeEngine.cancelStream(requestId)
        val rustCancelled = if (rustJniAvailable) {
            runCatching { nativeCancelStream(requestId) }.getOrDefault(false)
        } else {
            false
        }
        return ktCancelled || rustCancelled
    }

    fun executeLocalToolJson(
        name: String,
        argumentsJson: String,
        allowSdcardReadonly: Boolean = false,
        shellTimeoutSecs: Long = 20L
    ): String {
        return KotlinNativeEngine.executeLocalTool(
            name = name,
            rawArguments = argumentsJson,
            workspaceRoot = defaultWorkspaceDir,
            allowSdcardReadonly = allowSdcardReadonly,
            shellTimeoutSecs = shellTimeoutSecs
        ).toString()
    }

    fun buildUpstreamPayloadJson(requestJson: String): String {
        return KotlinNativeEngine.buildUpstreamPayload(JSONObject(requestJson)).toString()
    }

    interface JniStreamCallback {
        fun onEvent(eventJson: String): Boolean
    }

    @JvmStatic
    private external fun nativeGetMeta(workspacePath: String, allowSdcardReadonly: Boolean): String

    @JvmStatic
    private external fun nativeFetchModels(apiKey: String): String

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
}
