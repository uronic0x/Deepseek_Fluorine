package com.uronic0x.fluorine.bridge

import android.content.Context
import com.uronic0x.fluorine.engine.KotlinNativeEngine

/**
 * Callback invoked for each SSE frame (`data:` payload without prefix) or `[DONE]`.
 * Returning `false` aborts the active stream immediately.
 */
fun interface JniStreamCallback {
    fun onEvent(jsonLine: String): Boolean
}

/**
 * Direct JNI bridge to `libfluorine.so` (eliminating `127.0.0.1` HTTP loopback).
 *
 * When `libfluorine.so` is compiled and packaged via `cargo ndk`, all calls execute natively
 * in Rust over JNI. When running in environments where `cargo-ndk` was not run at build time,
 * transparently delegates to [KotlinNativeEngine] with 1:1 protocol and tool parity.
 */
object FluorineBridge {

    val isRustJniLoaded: Boolean = try {
        System.loadLibrary("fluorine")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    @JvmStatic
    private external fun nativeGetMeta(workspacePath: String, allowStorage: Byte): String

    @JvmStatic
    private external fun nativeFetchModels(apiKey: String): String

    @JvmStatic
    private external fun nativeStreamChat(
        requestId: Long,
        apiKey: String,
        requestJson: String,
        workspacePath: String,
        allowStorage: Byte,
        callback: JniStreamCallback
    )

    @JvmStatic
    private external fun nativeCancelStream(requestId: Long)

    fun getMetaJson(
        context: Context,
        workspacePath: String,
        allowStorage: Boolean
    ): String {
        return if (isRustJniLoaded) {
            try {
                nativeGetMeta(workspacePath, if (allowStorage) 1 else 0)
            } catch (_: Throwable) {
                KotlinNativeEngine.getMetaJson(context, workspacePath, allowStorage)
            }
        } else {
            KotlinNativeEngine.getMetaJson(context, workspacePath, allowStorage)
        }
    }

    fun fetchModelsJson(apiKey: String): String {
        return if (isRustJniLoaded) {
            try {
                nativeFetchModels(apiKey)
            } catch (_: Throwable) {
                KotlinNativeEngine.fetchModelsJson(apiKey)
            }
        } else {
            KotlinNativeEngine.fetchModelsJson(apiKey)
        }
    }

    fun streamChat(
        context: Context,
        requestId: Long,
        apiKey: String,
        requestJson: String,
        workspacePath: String,
        allowStorage: Boolean,
        callback: JniStreamCallback
    ) {
        if (isRustJniLoaded) {
            try {
                nativeStreamChat(
                    requestId = requestId,
                    apiKey = apiKey,
                    requestJson = requestJson,
                    workspacePath = workspacePath,
                    allowStorage = if (allowStorage) 1 else 0,
                    callback = callback
                )
                return
            } catch (_: Throwable) {
                // Fallback to Kotlin native implementation if JNI symbol fails
            }
        }
        KotlinNativeEngine.executeChatStream(
            context = context,
            requestId = requestId,
            apiKey = apiKey,
            requestJson = requestJson,
            workspacePath = workspacePath,
            allowStorage = allowStorage,
            callback = callback
        )
    }

    fun cancelStream(requestId: Long) {
        if (isRustJniLoaded) {
            try {
                nativeCancelStream(requestId)
            } catch (_: Throwable) {
            }
        }
        KotlinNativeEngine.cancelStream(requestId)
    }
}
