package com.uronic0x.fluorine.bridge

import android.content.Context
import com.uronic0x.fluorine.engine.KotlinNativeEngine
import org.json.JSONObject
import java.io.File

/**
 * Bridge between Android UI/ViewModel and the Fluorine Engine.
 * Supports JNI `libfluorine.so` when present on device and `KotlinNativeEngine`
 * with full parity for autonomous Agent Work mode, Prefix Cache optimization,
 * and custom endpoints/models.
 */
object FluorineBridge {

    private var rustJniAvailable: Boolean = false
    private var defaultWorkspaceDir: File = File(System.getProperty("java.io.tmpdir", "/tmp"), "fluorine_workspace")

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
        if (rustJniAvailable) {
            runCatching { nativeInit(dir.absolutePath, false) }
        }
        return dir
    }

    fun getWorkspaceDir(): File = defaultWorkspaceDir

    fun setAllowSdcardReadonly(allow: Boolean) {
        if (rustJniAvailable) {
            runCatching { nativeInit(defaultWorkspaceDir.absolutePath, allow) }
        }
    }

    fun engineInfoJson(): String {
        if (rustJniAvailable) {
            val res = runCatching { nativeEngineInfo() }.getOrNull()
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
            val res = runCatching { nativeDiscoverCapabilities(apiKey) }.getOrNull()
            if (!res.isNullOrBlank()) return res
        }
        return KotlinNativeEngine.discoverCapabilities(
            apiKey = apiKey,
            baseUrl = normalizedBase,
            connectTimeoutSec = connectTimeoutSec,
            readTimeoutSec = readTimeoutSec
        )
    }

    fun chatCompleteJson(requestJson: String): String {
        // Use KotlinNativeEngine directly so all new capabilities (custom base_url, custom model,
        // custom system_prompt, Prefix Cache subturn_messages, temperature, configurable timeouts)
        // work consistently regardless of whether an older prebuilt libfluorine.so is present.
        return KotlinNativeEngine.chatComplete(requestJson, defaultWorkspaceDir)
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

    @JvmStatic
    private external fun nativeInit(workspacePath: String, allowSdcardReadonly: Boolean): String

    @JvmStatic
    private external fun nativeEngineInfo(): String

    @JvmStatic
    private external fun nativeDiscoverCapabilities(apiKey: String): String

    @JvmStatic
    private external fun nativeChatComplete(requestJson: String): String

    @JvmStatic
    private external fun nativeExecuteTool(name: String, argumentsJson: String): String
}
