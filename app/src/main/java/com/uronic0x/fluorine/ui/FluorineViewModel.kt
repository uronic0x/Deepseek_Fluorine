package com.uronic0x.fluorine.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uronic0x.fluorine.bridge.FluorineBridge
import com.uronic0x.fluorine.protocol.ChatAttachment
import com.uronic0x.fluorine.protocol.ChatMessage
import com.uronic0x.fluorine.protocol.ContextTurn
import com.uronic0x.fluorine.protocol.ConversationSession
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.LocalToolCallRecord
import com.uronic0x.fluorine.protocol.ModelCapabilityInfo
import com.uronic0x.fluorine.protocol.StreamUiEvent
import com.uronic0x.fluorine.protocol.WorkToolsConfig
import com.uronic0x.fluorine.protocol.WorkspaceFileArtifact
import com.uronic0x.fluorine.ui.components.FluorineSettingsDraft
import com.uronic0x.fluorine.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class FluorineUiState(
    // Multi-session state
    val sessions: List<ConversationSession> = emptyList(),
    val activeSessionId: String = "",
    val conversationTitle: String = "新对话",
    val mode: String = "chat", // "chat" | "work"
    val chatMessages: List<ChatMessage> = emptyList(),
    val contextHistory: List<ContextTurn> = emptyList(),

    // Appearance & Display settings
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val enableMarkdown: Boolean = true,
    val enableLatex: Boolean = true,
    val defaultExpandThinking: Boolean = false,
    val defaultExpandTools: Boolean = false,
    val defaultExpandSources: Boolean = false,

    // API & Network settings
    val apiKey: String = "",
    val rememberKey: Boolean = true,
    val baseUrl: String = CoreProtocol.DEFAULT_API_BASE_URL,
    val connectTimeoutSec: Int = 20,
    val readTimeoutSec: Int = 180,

    // Model & Inference settings
    val model: String = "deepseek-flash",
    val customModel: String = "",
    val useWeb: Boolean = false,
    val unlockWebSearch: Boolean = false,
    val useThinking: Boolean = true,
    val thinkingMode: String = "auto", // "auto" | "enabled" | "disabled"
    val effort: String = "medium",
    val maxTokens: Int = 16384,
    val temperature: Float = 1.0f,
    val enableTemperature: Boolean = false,
    val discoveredCapabilities: Map<String, ModelCapabilityInfo> = CoreProtocol.DEFAULT_MODEL_CAPABILITIES,

    // Agent & Prompt settings
    val maxToolRounds: Int = 10,
    val maxHistoryTurns: Int = 24,
    val enableLatestReminder: Boolean = true,
    val autoGenerateTitle: Boolean = true,
    val customChatSystemPrompt: String = CoreProtocol.DEFAULT_CHAT_SYSTEM_PROMPT,
    val customWorkSystemPrompt: String = CoreProtocol.DEFAULT_WORK_SYSTEM_PROMPT,

    // Work Mode Tool Injection & Workspace settings
    val workToolsConfig: WorkToolsConfig = WorkToolsConfig(),
    val allowSdcardReadonly: Boolean = false,
    val shellTimeoutSec: Int = 20,
    val workspacePath: String = "",
    val workspaceFiles: List<WorkspaceFileArtifact> = emptyList(),

    // Composer, Multimodal Attachments & Runtime state
    val promptInput: String = "",
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val pdfParsePurpose: String = "text_recognition", // "text_recognition" | "rich_layout_recognition"
    val isBusy: Boolean = false,
    val activeRequestId: Long = 0L,
    val transientNotice: String? = null,
    val taskStatusText: String = "就绪",
    val toolsText: String = "",

    // Modals & Dialogs
    val showSettingsDialog: Boolean = false,
    val settingsOpenToken: Int = 0,
    val showWorkspaceFilesDialog: Boolean = false,
    val previewArtifact: WorkspaceFileArtifact? = null,
    val previewArtifactContent: String = "",
    val renamingSession: ConversationSession? = null,
    val isDiscovering: Boolean = false,
    val discoverySummary: String = "",
    val engineInfo: String = "",
    val engineLabel: String = ""
)

class FluorineViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("fluorine_prefs", Context.MODE_PRIVATE)
    private val sessionsStoreFile = File(application.filesDir, "sessions_store.json")
    private val legacySessionFile = File(application.filesDir, "session_state.json")
    private val attachmentsDir = File(application.filesDir, "attachments").apply { mkdirs() }
    private val cameraCapturesDir = File(application.filesDir, "camera_captures").apply { mkdirs() }
    private val workspaceDir: File = FluorineBridge.initWorkspace(application)

    private val _uiState = MutableStateFlow(FluorineUiState())
    val uiState: StateFlow<FluorineUiState> = _uiState.asStateFlow()

    private var activeExecutionJob: Job? = null

    init {
        CoreProtocol.AppLogger.info("App", "FluorineViewModel initialized; workspace=${workspaceDir.absolutePath}")
        loadPersistedState()
    }

    private fun loadPersistedState() {
        val remember = prefs.getBoolean("remember_key", true)
        val savedKey = if (remember) prefs.getString("api_key", "") ?: "" else ""
        val savedTheme = AppThemeMode.fromId(prefs.getString("theme_mode", AppThemeMode.DARK.id))
        val enableMarkdown = prefs.getBoolean("enable_markdown", true)
        val enableLatex = prefs.getBoolean("enable_latex", true)
        val defaultExpandThinking = prefs.getBoolean("default_expand_thinking", false)
        val defaultExpandTools = prefs.getBoolean("default_expand_tools", false)
        val defaultExpandSources = prefs.getBoolean("default_expand_sources", false)

        val baseUrl = prefs.getString("base_url", CoreProtocol.DEFAULT_API_BASE_URL)
            ?.ifBlank { CoreProtocol.DEFAULT_API_BASE_URL }
            ?: CoreProtocol.DEFAULT_API_BASE_URL
        val connectTimeoutSec = prefs.getInt("connect_timeout_sec", 20).coerceIn(5, 120)
        val readTimeoutSec = prefs.getInt("read_timeout_sec", 180).coerceIn(30, 600)

        val rawSavedModel = prefs.getString("model", "deepseek-flash") ?: "deepseek-flash"
        val savedModel = when (rawSavedModel) {
            "deepseek-v4-reasoner" -> "deepseek-v4-pro"
            "deepseek-v4-expert" -> "deepseek-flash"
            else -> rawSavedModel
        }
        val customModel = prefs.getString("custom_model", "") ?: ""
        if (savedModel != rawSavedModel && customModel.isBlank()) {
            prefs.edit().putString("model", savedModel).apply()
        }
        val savedEffort = prefs.getString("effort", "medium") ?: "medium"
        val savedThinkingMode = prefs.getString("thinking_mode", "auto") ?: "auto"
        val savedMaxTokens = prefs.getInt("max_tokens", 16384).coerceIn(256, 393216)
        val temperature = prefs.getFloat("temperature", 1.0f).coerceIn(0f, 2f)
        val enableTemperature = prefs.getBoolean("enable_temperature", false)
        val unlockWebSearch = prefs.getBoolean("unlock_web_search", false)
        val savedWeb = prefs.getBoolean("use_web", false)
        val savedThinking = prefs.getBoolean("use_thinking", true)

        val maxToolRounds = prefs.getInt("max_tool_rounds", 10).coerceIn(1, 50)
        val maxHistoryTurns = prefs.getInt("max_history_turns", 24).coerceIn(4, 80)
        val enableLatestReminder = prefs.getBoolean("enable_latest_reminder", true)
        val autoGenerateTitle = prefs.getBoolean("auto_generate_title", true)

        val customChatSystemPrompt = prefs.getString("custom_chat_system_prompt", "")
            ?.ifBlank { CoreProtocol.DEFAULT_CHAT_SYSTEM_PROMPT }
            ?: CoreProtocol.DEFAULT_CHAT_SYSTEM_PROMPT
        val customWorkSystemPrompt = prefs.getString("custom_work_system_prompt", "")
            ?.ifBlank { CoreProtocol.DEFAULT_WORK_SYSTEM_PROMPT }
            ?: CoreProtocol.DEFAULT_WORK_SYSTEM_PROMPT

        val toolConfigJsonStr = prefs.getString("work_tools_config_json", null)
        val workToolsConfig = toolConfigJsonStr
            ?.let { runCatching { WorkToolsConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: WorkToolsConfig()

        val savedSdcard = prefs.getBoolean("allow_sdcard", false)
        val shellTimeoutSec = prefs.getInt("shell_timeout_sec", 20).coerceIn(5, 120)

        FluorineBridge.setAllowSdcardReadonly(savedSdcard)
        val infoRaw = FluorineBridge.engineInfoJson()
        val engineLabel = buildEngineLabel(infoRaw)

        val (loadedSessions, activeId) = loadSessionsFromDisk()
        // 启动时总是进入一个空白新对话：
        // - 若上次激活的会话本身就是空白会话，直接复用，避免堆积空白会话；
        // - 否则在列表最前面新建一个空白会话作为启动会话（保留历史会话列表）。
        val lastActive = loadedSessions.firstOrNull { it.id == activeId } ?: loadedSessions.first()
        val startupMode = prefs.getString("mode", "chat") ?: "chat"
        val (startupSessions, startupSession) = if (lastActive.chatMessages.isEmpty()) {
            loadedSessions to lastActive
        } else {
            val blank = ConversationSession(
                id = UUID.randomUUID().toString(),
                title = if (startupMode == "work") "新 Work 任务" else "新对话",
                mode = startupMode,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chatMessages = emptyList(),
                contextHistory = emptyList()
            )
            val list = listOf(blank) + loadedSessions
            persistSessionsToDisk(list, blank.id)
            list to blank
        }

        val effectiveModel = customModel.ifBlank { savedModel }
        val effectiveWeb = if (effectiveModel == "deepseek-v4-pro" && !unlockWebSearch) false else savedWeb
        val wsFiles = CoreProtocol.listWorkspaceFiles(workspaceDir)

        _uiState.update {
            it.copy(
                sessions = startupSessions,
                activeSessionId = startupSession.id,
                conversationTitle = startupSession.title,
                mode = startupSession.mode,
                chatMessages = startupSession.chatMessages,
                contextHistory = startupSession.contextHistory,
                themeMode = savedTheme,
                enableMarkdown = enableMarkdown,
                enableLatex = enableLatex,
                defaultExpandThinking = defaultExpandThinking,
                defaultExpandTools = defaultExpandTools,
                defaultExpandSources = defaultExpandSources,
                apiKey = savedKey,
                rememberKey = remember,
                baseUrl = baseUrl,
                connectTimeoutSec = connectTimeoutSec,
                readTimeoutSec = readTimeoutSec,
                model = effectiveModel,
                customModel = customModel,
                useWeb = effectiveWeb,
                unlockWebSearch = unlockWebSearch,
                useThinking = savedThinking,
                thinkingMode = savedThinkingMode,
                effort = savedEffort,
                maxTokens = savedMaxTokens,
                temperature = temperature,
                enableTemperature = enableTemperature,
                maxToolRounds = maxToolRounds,
                maxHistoryTurns = maxHistoryTurns,
                enableLatestReminder = enableLatestReminder,
                autoGenerateTitle = autoGenerateTitle,
                customChatSystemPrompt = customChatSystemPrompt,
                customWorkSystemPrompt = customWorkSystemPrompt,
                workToolsConfig = workToolsConfig,
                allowSdcardReadonly = savedSdcard,
                shellTimeoutSec = shellTimeoutSec,
                workspacePath = workspaceDir.absolutePath,
                workspaceFiles = wsFiles,
                engineInfo = infoRaw,
                engineLabel = engineLabel
            )
        }
    }

    /**
     * 把引擎 meta JSON 折叠成一行简洁标签，用于设置页标题下的副标题展示，
     * 避免把整段 JSON（runtime/available_bins/workspace…）直接铺在界面上。
     */
    private fun buildEngineLabel(infoRaw: String): String {
        val obj = runCatching { JSONObject(infoRaw) }.getOrNull()
            ?: return "Fluorine · ${if (FluorineBridge.isRustJniLoaded) "Rust JNI" else "Kotlin 回退"}"
        val display = obj.optString("display_name", "").ifBlank { obj.optString("app_name", "Fluorine") }
        val version = obj.optString("version", "")
        val subtitle = obj.optString("subtitle", "")
        val runtime = obj.optJSONObject("runtime")
        val arch = runtime?.optString("arch", "").orEmpty()
        val engineKind = if (FluorineBridge.isRustJniLoaded) "Rust JNI" else "Kotlin 回退"

        return buildList {
            add(display + if (version.isNotBlank()) " v$version" else "")
            if (subtitle.isNotBlank()) add(subtitle)
            add(engineKind + if (arch.isNotBlank()) " · $arch" else "")
        }.joinToString(" · ")
    }

    private fun loadSessionsFromDisk(): Pair<List<ConversationSession>, String> {
        if (sessionsStoreFile.exists()) {
            val raw = runCatching { sessionsStoreFile.readText(Charsets.UTF_8) }.getOrDefault("")
            val (list, activeId) = CoreProtocol.deserializeSessionsStore(raw)
            if (list.isNotEmpty()) {
                val validActive = list.firstOrNull { it.id == activeId }?.id ?: list.first().id
                return list to validActive
            }
        }
        if (legacySessionFile.exists()) {
            val raw = runCatching { legacySessionFile.readText(Charsets.UTF_8) }.getOrDefault("")
            val (msgs, ctx) = CoreProtocol.deserializeSession(raw)
            if (msgs.isNotEmpty()) {
                val firstTitle = msgs.firstOrNull { it.role == "user" }?.text?.take(22) ?: "历史对话"
                val migrated = ConversationSession(
                    id = UUID.randomUUID().toString(),
                    title = firstTitle,
                    mode = prefs.getString("mode", "chat") ?: "chat",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    chatMessages = msgs,
                    contextHistory = ctx
                )
                val list = listOf(migrated)
                persistSessionsToDisk(list, migrated.id)
                return list to migrated.id
            }
        }
        val initial = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            mode = "chat",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            chatMessages = emptyList(),
            contextHistory = emptyList()
        )
        val list = listOf(initial)
        persistSessionsToDisk(list, initial.id)
        return list to initial.id
    }

    /**
     * Atomic write using a temp file + rename so truncated/corrupted writes never destroy `sessions_store.json`.
     */
    private fun persistSessionsToDisk(sessions: List<ConversationSession>, activeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val json = CoreProtocol.serializeSessionsStore(sessions, activeId)
                val tmp = File(sessionsStoreFile.parentFile, "sessions_store.json.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(sessionsStoreFile)) {
                    sessionsStoreFile.writeText(json, Charsets.UTF_8)
                    tmp.delete()
                }
            }
            res.onFailure { err ->
                CoreProtocol.AppLogger.error("Storage", "Failed to save sessions_store.json: ${err.message}")
            }
        }
    }

    private fun updateSessionById(
        targetSessionId: String,
        title: String? = null,
        mode: String? = null,
        chatMessages: List<ChatMessage>? = null,
        contextHistory: List<ContextTurn>? = null,
        touchUpdatedAt: Boolean = true,
        persist: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        var updatedTarget: ConversationSession? = null
        val updatedList = _uiState.value.sessions.map { item ->
            if (item.id == targetSessionId) {
                val copy = item.copy(
                    title = title ?: item.title,
                    mode = mode ?: item.mode,
                    updatedAt = if (touchUpdatedAt) now else item.updatedAt,
                    chatMessages = chatMessages ?: item.chatMessages,
                    contextHistory = contextHistory ?: item.contextHistory
                )
                updatedTarget = copy
                copy
            } else {
                item
            }
        }

        _uiState.update { state ->
            val currentActive = state.activeSessionId == targetSessionId
            val target = updatedTarget
            if (currentActive && target != null) {
                state.copy(
                    sessions = updatedList,
                    conversationTitle = target.title,
                    mode = target.mode,
                    chatMessages = target.chatMessages,
                    contextHistory = target.contextHistory
                )
            } else {
                state.copy(sessions = updatedList)
            }
        }

        if (persist) {
            persistSessionsToDisk(updatedList, _uiState.value.activeSessionId)
        }
    }

    // ========================================================================
    // Multi-Session Management (Strict Chat vs Work Isolation)
    // ========================================================================

    fun newConversation(targetMode: String? = null) {
        if (_uiState.value.isBusy) {
            _uiState.update { it.copy(transientNotice = "生成进行中，请先停止当前任务") }
            return
        }
        val current = _uiState.value
        val resolvedMode = if (targetMode == "chat" || targetMode == "work") targetMode else current.mode
        val active = current.sessions.firstOrNull { it.id == current.activeSessionId }
        if (active != null && active.chatMessages.isEmpty()) {
            val updatedList = current.sessions.map {
                if (it.id == active.id) it.copy(mode = resolvedMode, title = "新对话") else it
            }
            _uiState.update {
                it.copy(
                    sessions = updatedList,
                    conversationTitle = "新对话",
                    mode = resolvedMode,
                    promptInput = "",
                    pendingAttachments = emptyList(),
                    taskStatusText = "就绪",
                    toolsText = ""
                )
            }
            persistSessionsToDisk(updatedList, active.id)
            return
        }

        val newSession = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = if (resolvedMode == "work") "新 Work 任务" else "新对话",
            mode = resolvedMode,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            chatMessages = emptyList(),
            contextHistory = emptyList()
        )
        val updatedSessions = listOf(newSession) + current.sessions
        _uiState.update {
            it.copy(
                sessions = updatedSessions,
                activeSessionId = newSession.id,
                conversationTitle = newSession.title,
                mode = newSession.mode,
                chatMessages = emptyList(),
                contextHistory = emptyList(),
                promptInput = "",
                pendingAttachments = emptyList(),
                taskStatusText = "就绪",
                toolsText = ""
            )
        }
        prefs.edit().putString("mode", resolvedMode).apply()
        persistSessionsToDisk(updatedSessions, newSession.id)
    }

    fun selectSession(sessionId: String) {
        if (_uiState.value.isBusy) {
            _uiState.update { it.copy(transientNotice = "当前会话正在生成，请先停止后再切换") }
            return
        }
        val target = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        _uiState.update {
            it.copy(
                activeSessionId = target.id,
                conversationTitle = target.title,
                mode = target.mode,
                chatMessages = target.chatMessages,
                contextHistory = target.contextHistory,
                pendingAttachments = emptyList(),
                taskStatusText = "就绪",
                toolsText = ""
            )
        }
        prefs.edit().putString("mode", target.mode).apply()
        persistSessionsToDisk(_uiState.value.sessions, target.id)
    }

    /**
     * Strict Chat / Work isolation:
     * - If the active session has no messages yet, switches its empty draft mode directly.
     * - If the active session already contains messages, NEVER mutates that session's mode;
     *   instead switches to the latest existing session of `newMode` or creates a fresh `newMode` session.
     */
    fun setMode(newMode: String) {
        if (newMode != "chat" && newMode != "work") return
        val current = _uiState.value
        if (current.isBusy) {
            _uiState.update { it.copy(transientNotice = "生成进行中，请先停止后再切换模式") }
            return
        }
        if (current.mode == newMode) return

        prefs.edit().putString("mode", newMode).apply()

        if (current.chatMessages.isEmpty()) {
            updateSessionById(
                targetSessionId = current.activeSessionId,
                mode = newMode,
                title = if (newMode == "work") "新 Work 任务" else "新对话",
                touchUpdatedAt = false
            )
            return
        }

        val matchingSession = current.sessions
            .filter { it.mode == newMode }
            .maxByOrNull { it.updatedAt }

        if (matchingSession != null) {
            selectSession(matchingSession.id)
            _uiState.update {
                it.copy(transientNotice = "已保持原会话隔离，切换至 ${if (newMode == "work") "Work" else "Chat"} 会话")
            }
        } else {
            newConversation(newMode)
            _uiState.update {
                it.copy(transientNotice = "已新建独立的 ${if (newMode == "work") "Work" else "Chat"} 会话")
            }
        }
    }

    fun requestRenameSession(session: ConversationSession?) {
        _uiState.update { it.copy(renamingSession = session) }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val trimmed = newTitle.trim().ifEmpty { "新对话" }
        val s = _uiState.value
        val updated = s.sessions.map {
            if (it.id == sessionId) it.copy(title = trimmed, updatedAt = System.currentTimeMillis()) else it
        }
        val activeTitle = if (s.activeSessionId == sessionId) trimmed else s.conversationTitle
        _uiState.update {
            it.copy(
                sessions = updated,
                conversationTitle = activeTitle,
                renamingSession = null
            )
        }
        persistSessionsToDisk(updated, s.activeSessionId)
    }

    fun deleteSession(sessionId: String) {
        if (_uiState.value.isBusy) return
        val s = _uiState.value
        val remaining = s.sessions.filterNot { it.id == sessionId }
        if (remaining.isEmpty()) {
            val fresh = ConversationSession(
                id = UUID.randomUUID().toString(),
                title = "新对话",
                mode = s.mode,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                chatMessages = emptyList(),
                contextHistory = emptyList()
            )
            val list = listOf(fresh)
            _uiState.update {
                it.copy(
                    sessions = list,
                    activeSessionId = fresh.id,
                    conversationTitle = fresh.title,
                    chatMessages = emptyList(),
                    contextHistory = emptyList(),
                    pendingAttachments = emptyList(),
                    taskStatusText = "就绪",
                    toolsText = ""
                )
            }
            persistSessionsToDisk(list, fresh.id)
        } else {
            val nextActive = if (s.activeSessionId == sessionId) {
                remaining.first()
            } else {
                remaining.firstOrNull { it.id == s.activeSessionId } ?: remaining.first()
            }
            _uiState.update {
                it.copy(
                    sessions = remaining,
                    activeSessionId = nextActive.id,
                    conversationTitle = nextActive.title,
                    mode = nextActive.mode,
                    chatMessages = nextActive.chatMessages,
                    contextHistory = nextActive.contextHistory,
                    pendingAttachments = emptyList()
                )
            }
            persistSessionsToDisk(remaining, nextActive.id)
        }
    }

    fun clearAllSessions() {
        if (_uiState.value.isBusy) return
        val fresh = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            mode = _uiState.value.mode,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            chatMessages = emptyList(),
            contextHistory = emptyList()
        )
        val list = listOf(fresh)
        _uiState.update {
            it.copy(
                sessions = list,
                activeSessionId = fresh.id,
                conversationTitle = fresh.title,
                chatMessages = emptyList(),
                contextHistory = emptyList(),
                pendingAttachments = emptyList(),
                taskStatusText = "就绪",
                toolsText = "",
                transientNotice = "已清空全部历史会话"
            )
        }
        persistSessionsToDisk(list, fresh.id)
    }

    // ========================================================================
    // Toggles, Settings & Multimodal Attachment Management
    // ========================================================================

    fun clearTransientNotice() {
        if (_uiState.value.transientNotice != null) {
            _uiState.update { it.copy(transientNotice = null) }
        }
    }

    fun isMultimodalSupportedForCurrentModel(): Boolean {
        val s = _uiState.value
        return CoreProtocol.supportsMultimodal(s.model, s.discoveredCapabilities)
    }

    fun notifyMultimodalUnavailable() {
        val m = _uiState.value.model
        _uiState.update {
            it.copy(
                transientNotice = "当前模型（$m）不支持图片或文件上传（仅支持纯文本）；请切换至 deepseek-flash 或其他多模态模型"
            )
        }
    }

    fun setPdfParsePurpose(purpose: String) {
        if (purpose == "text_recognition" || purpose == "rich_layout_recognition") {
            _uiState.update { it.copy(pdfParsePurpose = purpose) }
        }
    }

    fun createCameraCaptureFile(): File {
        val name = "cam_${System.currentTimeMillis()}.jpg"
        return File(cameraCapturesDir, name)
    }

    fun addCameraCapture(file: File) {
        if (!isMultimodalSupportedForCurrentModel()) {
            notifyMultimodalUnavailable()
            runCatching { file.delete() }
            return
        }
        if (!file.exists() || file.length() <= 0L) {
            _uiState.update { it.copy(transientNotice = "未获取到拍摄照片") }
            return
        }
        val att = ChatAttachment(
            id = UUID.randomUUID().toString(),
            name = file.name,
            mimeType = "image/jpeg",
            kind = "image",
            localPath = file.absolutePath,
            sizeBytes = file.length(),
            parsePurpose = "user_data"
        )
        _uiState.update {
            it.copy(
                pendingAttachments = it.pendingAttachments + att,
                transientNotice = "已添加照片 (${CoreProtocol.formatFileSize(att.sizeBytes)})"
            )
        }
    }

    fun addUriAttachment(context: Context, uri: Uri, requestedKind: String) {
        if (!isMultimodalSupportedForCurrentModel()) {
            notifyMultimodalUnavailable()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val cr = context.contentResolver
                val mime = cr.getType(uri) ?: if (requestedKind == "image") "image/jpeg" else "application/octet-stream"
                var displayName = if (requestedKind == "image") "image_${System.currentTimeMillis()}.jpg" else "doc_${System.currentTimeMillis()}"
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val n = cursor.getString(idx)
                            if (!n.isNullOrBlank()) displayName = n
                        }
                    }
                }
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"), "_")
                val dest = File(attachmentsDir, "${UUID.randomUUID().toString().take(8)}_$safeName")
                cr.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("无法读取所选文件")

                val size = dest.length()
                val ext = safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val isImage = requestedKind == "image" ||
                    mime.startsWith("image/") ||
                    ext in setOf("jpg", "jpeg", "png", "gif", "webp")

                if (isImage) {
                    if (size > 64L * 1024L * 1024L) {
                        dest.delete()
                        throw IllegalArgumentException("图片大小超过 DeepSeek 64 MiB 上限")
                    }
                    ChatAttachment(
                        id = UUID.randomUUID().toString(),
                        name = safeName,
                        mimeType = if (mime.startsWith("image/")) mime else "image/jpeg",
                        kind = "image",
                        localPath = dest.absolutePath,
                        sizeBytes = size,
                        parsePurpose = "user_data"
                    )
                } else {
                    val isRichParseDoc = ext in setOf("pdf", "doc", "docx", "ppt", "pptx") ||
                        mime == "application/pdf" ||
                        mime.contains("word") ||
                        mime.contains("powerpoint") ||
                        mime.contains("presentation")
                    val maxBytes = if (isRichParseDoc) 100L * 1024L * 1024L else 32L * 1024L * 1024L
                    if (size > maxBytes) {
                        dest.delete()
                        throw IllegalArgumentException("文件超过官方 ${if (isRichParseDoc) "100 MiB" else "32 MiB"} 上限")
                    }
                    val purpose = if (isRichParseDoc) _uiState.value.pdfParsePurpose else "user_data"
                    ChatAttachment(
                        id = UUID.randomUUID().toString(),
                        name = safeName,
                        mimeType = mime,
                        kind = if (isRichParseDoc) "document" else "text",
                        localPath = dest.absolutePath,
                        sizeBytes = size,
                        parsePurpose = purpose
                    )
                }
            }
            withContext(Dispatchers.Main) {
                res.fold(
                    onSuccess = { att ->
                        _uiState.update {
                            it.copy(
                                pendingAttachments = it.pendingAttachments + att,
                                transientNotice = "已添加：${att.name} (${CoreProtocol.formatFileSize(att.sizeBytes)})"
                            )
                        }
                    },
                    onFailure = { err ->
                        _uiState.update { it.copy(transientNotice = "附件添加失败: ${err.message}") }
                    }
                )
            }
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        _uiState.update {
            it.copy(pendingAttachments = it.pendingAttachments.filterNot { att -> att.id == attachmentId })
        }
    }

    fun toggleWeb() {
        val current = _uiState.value
        if (current.model == "deepseek-v4-pro" && !current.unlockWebSearch) {
            _uiState.update {
                it.copy(
                    useWeb = false,
                    transientNotice = "deepseek-v4-pro 默认关闭内置联网；可切换至 deepseek-flash 或在「设置 → 模型与推理」中允许跨模型联网"
                )
            }
            return
        }
        val next = !current.useWeb
        _uiState.update { it.copy(useWeb = next) }
        prefs.edit().putBoolean("use_web", next).apply()
    }

    fun toggleThinking() {
        val next = !_uiState.value.useThinking
        _uiState.update { it.copy(useThinking = next) }
        prefs.edit().putBoolean("use_thinking", next).apply()
    }

    fun cycleThemeMode() {
        val next = when (_uiState.value.themeMode) {
            AppThemeMode.DARK -> AppThemeMode.LIGHT
            AppThemeMode.LIGHT -> AppThemeMode.SYSTEM
            AppThemeMode.SYSTEM -> AppThemeMode.DARK
        }
        _uiState.update { it.copy(themeMode = next) }
        prefs.edit().putString("theme_mode", next.id).apply()
    }

    fun updatePromptInput(value: String) {
        _uiState.update { it.copy(promptInput = value) }
    }

    fun openSettings(open: Boolean) {
        if (open) refreshWorkspaceFiles()
        _uiState.update {
            it.copy(
                showSettingsDialog = open,
                settingsOpenToken = if (open) it.settingsOpenToken + 1 else it.settingsOpenToken
            )
        }
    }

    fun saveSettings(draft: FluorineSettingsDraft) {
        val effectiveModel = draft.customModel.trim().ifEmpty { draft.model }
        val effectiveWeb = if (effectiveModel == "deepseek-v4-pro" && !draft.unlockWebSearch) {
            false
        } else {
            _uiState.value.useWeb
        }
        val normalizedBaseUrl = draft.baseUrl.trim().ifEmpty { CoreProtocol.DEFAULT_API_BASE_URL }
        val clampedTokens = draft.maxTokens.coerceIn(256, 393216)

        val chatPromptToSave = draft.customChatSystemPrompt.ifBlank { CoreProtocol.DEFAULT_CHAT_SYSTEM_PROMPT }
        val workPromptToSave = draft.customWorkSystemPrompt.ifBlank { CoreProtocol.DEFAULT_WORK_SYSTEM_PROMPT }

        FluorineBridge.setAllowSdcardReadonly(draft.allowSdcardReadonly)

        _uiState.update {
            it.copy(
                themeMode = draft.themeMode,
                enableMarkdown = draft.enableMarkdown,
                enableLatex = draft.enableLatex,
                defaultExpandThinking = draft.defaultExpandThinking,
                defaultExpandTools = draft.defaultExpandTools,
                defaultExpandSources = draft.defaultExpandSources,
                apiKey = draft.apiKey.trim(),
                rememberKey = draft.rememberKey,
                baseUrl = normalizedBaseUrl,
                connectTimeoutSec = draft.connectTimeoutSec,
                readTimeoutSec = draft.readTimeoutSec,
                model = effectiveModel,
                customModel = draft.customModel.trim(),
                useWeb = effectiveWeb,
                unlockWebSearch = draft.unlockWebSearch,
                thinkingMode = draft.thinkingMode,
                effort = draft.effort,
                maxTokens = clampedTokens,
                temperature = draft.temperature,
                enableTemperature = draft.enableTemperature,
                maxToolRounds = draft.maxToolRounds,
                maxHistoryTurns = draft.maxHistoryTurns,
                enableLatestReminder = draft.enableLatestReminder,
                autoGenerateTitle = draft.autoGenerateTitle,
                customChatSystemPrompt = chatPromptToSave,
                customWorkSystemPrompt = workPromptToSave,
                workToolsConfig = draft.workToolsConfig,
                allowSdcardReadonly = draft.allowSdcardReadonly,
                shellTimeoutSec = draft.shellTimeoutSec,
                transientNotice = "设置已保存 · $effectiveModel"
            )
        }

        prefs.edit().apply {
            putString("theme_mode", draft.themeMode.id)
            putBoolean("enable_markdown", draft.enableMarkdown)
            putBoolean("enable_latex", draft.enableLatex)
            putBoolean("default_expand_thinking", draft.defaultExpandThinking)
            putBoolean("default_expand_tools", draft.defaultExpandTools)
            putBoolean("default_expand_sources", draft.defaultExpandSources)
            putBoolean("remember_key", draft.rememberKey)
            if (draft.rememberKey) {
                putString("api_key", draft.apiKey.trim())
            } else {
                remove("api_key")
            }
            putString("base_url", normalizedBaseUrl)
            putInt("connect_timeout_sec", draft.connectTimeoutSec)
            putInt("read_timeout_sec", draft.readTimeoutSec)
            putString("model", effectiveModel)
            putString("custom_model", draft.customModel.trim())
            putBoolean("use_web", effectiveWeb)
            putBoolean("unlock_web_search", draft.unlockWebSearch)
            putString("thinking_mode", draft.thinkingMode)
            putString("effort", draft.effort)
            putInt("max_tokens", clampedTokens)
            putFloat("temperature", draft.temperature)
            putBoolean("enable_temperature", draft.enableTemperature)
            putInt("max_tool_rounds", draft.maxToolRounds)
            putInt("max_history_turns", draft.maxHistoryTurns)
            putBoolean("enable_latest_reminder", draft.enableLatestReminder)
            putBoolean("auto_generate_title", draft.autoGenerateTitle)
            putString("custom_chat_system_prompt", chatPromptToSave)
            putString("custom_work_system_prompt", workPromptToSave)
            putString("work_tools_config_json", draft.workToolsConfig.toJson().toString())
            putBoolean("allow_sdcard", draft.allowSdcardReadonly)
            putInt("shell_timeout_sec", draft.shellTimeoutSec)
            apply()
        }
        CoreProtocol.AppLogger.info(
            "Settings",
            "Saved settings: model=$effectiveModel baseUrl=$normalizedBaseUrl shell=${draft.workToolsConfig.shellExec} device=${draft.workToolsConfig.deviceControl} enabledTools=${draft.workToolsConfig.enabledToolNames().size}"
        )
    }

    fun discoverCapabilities(overrideKey: String? = null, overrideBaseUrl: String? = null) {
        val s = _uiState.value
        val key = (overrideKey ?: s.apiKey).trim()
        val baseUrl = (overrideBaseUrl ?: s.baseUrl).trim().ifEmpty { CoreProtocol.DEFAULT_API_BASE_URL }
        if (key.isEmpty()) {
            _uiState.update { it.copy(discoverySummary = "请先填写 API Key") }
            return
        }
        _uiState.update { it.copy(isDiscovering = true, discoverySummary = "正在探测上游接口与多模态能力...") }
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                FluorineBridge.discoverCapabilitiesJson(
                    apiKey = key,
                    baseUrl = baseUrl,
                    connectTimeoutSec = s.connectTimeoutSec,
                    readTimeoutSec = s.readTimeoutSec
                )
            }
            val parsed = runCatching { JSONObject(raw) }.getOrNull()
            val ok = parsed?.optBoolean("ok", false) == true
            val discoveredMap = CoreProtocol.DEFAULT_MODEL_CAPABILITIES.toMutableMap()
            val summary = if (ok) {
                val models = parsed?.optJSONArray("models")
                val modelList = buildList {
                    if (models != null) {
                        for (i in 0 until models.length()) add(models.optString(i))
                    }
                }
                val detailArr = parsed?.optJSONArray("models_detail")
                if (detailArr != null) {
                    for (i in 0 until detailArr.length()) {
                        val d = detailArr.optJSONObject(i) ?: continue
                        val id = d.optString("id", "").trim()
                        if (id.isEmpty()) continue
                        val mods = mutableListOf<String>()
                        val mArr = d.optJSONArray("input_modalities")
                        if (mArr != null) {
                            for (j in 0 until mArr.length()) mods.add(mArr.optString(j))
                        }
                        discoveredMap[id] = ModelCapabilityInfo(
                            id = id,
                            name = d.optString("name", id),
                            contextWindow = d.optInt("context_window", 0),
                            maxOutputTokens = d.optInt("max_output_tokens", 0),
                            inputModalities = mods
                        )
                    }
                }
                val enabledCount = _uiState.value.workToolsConfig.enabledToolNames().size
                "连接成功 ($baseUrl) · 可用模型: ${modelList.joinToString(", ").ifEmpty { "默认" }} · 当前已启用工具: $enabledCount 个"
            } else {
                "探测失败: ${parsed?.optString("error", raw)}"
            }
            _uiState.update {
                it.copy(
                    isDiscovering = false,
                    discoverySummary = summary,
                    discoveredCapabilities = discoveredMap
                )
            }
        }
    }

    // ========================================================================
    // Workspace File Export / Preview / Management
    // ========================================================================

    fun openWorkspaceFilesDialog(open: Boolean) {
        if (open) refreshWorkspaceFiles()
        _uiState.update { it.copy(showWorkspaceFilesDialog = open) }
    }

    fun refreshWorkspaceFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = CoreProtocol.listWorkspaceFiles(workspaceDir)
            _uiState.update { it.copy(workspaceFiles = files) }
        }
    }

    fun resolveWorkspaceFile(artifact: WorkspaceFileArtifact): File {
        val rel = artifact.relativePath.trim().removePrefix("./")
        val wsCanonical = workspaceDir.canonicalFile
        val candidate = File(wsCanonical, rel).canonicalFile
        val wsPrefix = wsCanonical.path
        if (candidate.path != wsPrefix && !candidate.path.startsWith(wsPrefix + File.separator)) {
            throw SecurityException("工作区路径非法越界: ${artifact.relativePath}")
        }
        return candidate
    }

    fun previewWorkspaceFile(artifact: WorkspaceFileArtifact) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = runCatching { resolveWorkspaceFile(artifact) }.getOrNull()
            val content = if (file == null || !file.exists() || !file.isFile) {
                "[文件不存在或路径受限: ${artifact.relativePath}]"
            } else if (file.length() > 256 * 1024) {
                runCatching {
                    file.inputStream().bufferedReader(Charsets.UTF_8).use {
                        val buf = CharArray(64 * 1024)
                        val n = it.read(buf)
                        String(buf, 0, n.coerceAtLeast(0)) + "\n\n... [文件较大，预览已截断，请导出查看完整内容] ..."
                    }
                }.getOrElse { "无法作为 UTF-8 文本预览: ${it.message}" }
            } else {
                runCatching { file.readText(Charsets.UTF_8) }
                    .getOrElse { "无法作为 UTF-8 文本预览: ${it.message}" }
            }
            _uiState.update {
                it.copy(
                    previewArtifact = artifact.copy(bytes = if (file != null && file.exists()) file.length() else artifact.bytes),
                    previewArtifactContent = content
                )
            }
        }
    }

    fun closePreviewDialog() {
        _uiState.update { it.copy(previewArtifact = null, previewArtifactContent = "") }
    }

    fun deleteWorkspaceFile(artifact: WorkspaceFileArtifact) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = runCatching { resolveWorkspaceFile(artifact) }.getOrNull()
            if (file != null && file.exists() && file.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
                file.delete()
                CoreProtocol.AppLogger.info("Workspace", "Deleted workspace file: ${artifact.relativePath}")
            }
            val updated = CoreProtocol.listWorkspaceFiles(workspaceDir)
            _uiState.update {
                it.copy(
                    workspaceFiles = updated,
                    transientNotice = "已删除工作区文件 ${artifact.fileName}"
                )
            }
        }
    }

    fun writeWorkspaceFileToUri(
        context: Context,
        artifact: WorkspaceFileArtifact,
        targetUri: Uri,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val srcFile = resolveWorkspaceFile(artifact)
                if (!srcFile.exists() || !srcFile.isFile) {
                    throw IllegalStateException("工作区文件不存在: ${artifact.relativePath}")
                }
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    srcFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                } ?: throw IllegalStateException("无法写入目标 URI")
                srcFile.length()
            }
            withContext(Dispatchers.Main) {
                res.fold(
                    onSuccess = { bytes ->
                        val msg = "已导出 ${artifact.fileName} (${CoreProtocol.formatFileSize(bytes)})"
                        CoreProtocol.AppLogger.info("Export", msg)
                        _uiState.update { it.copy(transientNotice = msg) }
                        onComplete(true, msg)
                    },
                    onFailure = { err ->
                        val msg = "导出失败: ${err.message}"
                        CoreProtocol.AppLogger.error("Export", msg)
                        _uiState.update { it.copy(transientNotice = msg) }
                        onComplete(false, msg)
                    }
                )
            }
        }
    }

    // ========================================================================
    // Chat History & App Log Export / Import
    // ========================================================================

    fun writeTextContentToUri(
        context: Context,
        targetUri: Uri,
        contentProducer: () -> String,
        successLabel: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val content = contentProducer()
                val bytes = content.toByteArray(Charsets.UTF_8)
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    out.write(bytes)
                } ?: throw IllegalStateException("无法打开目标文件输出流")
                bytes.size.toLong()
            }
            withContext(Dispatchers.Main) {
                res.fold(
                    onSuccess = { size ->
                        val msg = "$successLabel (${CoreProtocol.formatFileSize(size)})"
                        CoreProtocol.AppLogger.info("Export", msg)
                        _uiState.update { it.copy(transientNotice = msg) }
                        onComplete(true, msg)
                    },
                    onFailure = { err ->
                        val msg = "导出失败: ${err.message}"
                        CoreProtocol.AppLogger.error("Export", msg)
                        _uiState.update { it.copy(transientNotice = msg) }
                        onComplete(false, msg)
                    }
                )
            }
        }
    }

    fun buildSessionsJsonExport(): String {
        val s = _uiState.value
        return CoreProtocol.serializeSessionsStore(s.sessions, s.activeSessionId)
    }

    fun buildSessionsMarkdownExport(): String {
        val s = _uiState.value
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.append("# Fluorine 聊天历史导出\n\n")
        sb.append("- 导出时间：").append(sdf.format(Date())).append("\n")
        sb.append("- 会话总数：").append(s.sessions.size).append("\n\n")

        s.sessions.forEachIndexed { idx, session ->
            sb.append("---\n\n")
            sb.append("## ").append(idx + 1).append(". ").append(session.title)
                .append(" (").append(session.mode.uppercase()).append(")\n\n")
            sb.append("- 更新时间：").append(sdf.format(Date(session.updatedAt))).append("\n\n")
            session.chatMessages.forEach { msg ->
                val roleTitle = if (msg.role == "user") "### 用户 (User)" else "### Fluorine (Assistant)"
                sb.append(roleTitle).append("\n\n")
                if (msg.attachments.isNotEmpty()) {
                    sb.append("附件：")
                        .append(msg.attachments.joinToString(", ") { it.name })
                        .append("\n\n")
                }
                if (msg.thinking.isNotBlank()) {
                    sb.append("<details><summary>深度思考过程</summary>\n\n```text\n")
                        .append(msg.thinking)
                        .append("\n```\n</details>\n\n")
                }
                if (msg.localToolCalls.isNotEmpty()) {
                    sb.append("<details><summary>本地工具调用 (").append(msg.localToolCalls.size).append(")</summary>\n\n```json\n")
                    msg.localToolCalls.forEach { tc ->
                        sb.append("[#").append(tc.round).append("] ").append(tc.name)
                            .append("(").append(tc.arguments).append(")\n-> ")
                            .append(tc.output).append("\n")
                    }
                    sb.append("```\n</details>\n\n")
                }
                sb.append(msg.text).append("\n\n")
                if (msg.incompleteNotice.isNotBlank()) {
                    sb.append("> 注意：").append(msg.incompleteNotice).append("\n\n")
                }
                if (msg.usageText.isNotBlank()) {
                    sb.append("> ").append(msg.usageText).append("\n\n")
                }
            }
        }
        return sb.toString()
    }

    fun importSessionsFromUri(
        context: Context,
        sourceUri: Uri,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (_uiState.value.isBusy) {
            onComplete(false, "正在生成回复，请停止后再导入")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val raw = context.contentResolver.openInputStream(sourceUri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("无法读取导入文件")
                val (imported, _) = CoreProtocol.deserializeSessionsStore(raw)
                if (imported.isEmpty()) {
                    val (legacyMsgs, legacyCtx) = CoreProtocol.deserializeSession(raw)
                    if (legacyMsgs.isEmpty()) throw IllegalArgumentException("未找到有效会话数据")
                    listOf(
                        ConversationSession(
                            id = UUID.randomUUID().toString(),
                            title = legacyMsgs.firstOrNull { it.role == "user" }?.text?.take(22) ?: "导入对话",
                            mode = "chat",
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            chatMessages = legacyMsgs,
                            contextHistory = legacyCtx
                        )
                    )
                } else {
                    imported
                }
            }
            withContext(Dispatchers.Main) {
                res.fold(
                    onSuccess = { importedList ->
                        val s = _uiState.value
                        if (s.isBusy) {
                            onComplete(false, "正在生成回复，请停止后再导入")
                            return@fold
                        }
                        val seenSessionIds = s.sessions.map { it.id }.toMutableSet()
                        val seenMsgIds = s.sessions.flatMap { sess -> sess.chatMessages.map { it.id } }.toMutableSet()

                        val deduped = importedList.map { item ->
                            val newSessionId = if (!seenSessionIds.add(item.id) || item.id.isBlank()) {
                                UUID.randomUUID().toString().also { seenSessionIds.add(it) }
                            } else {
                                item.id
                            }
                            val dedupedMsgs = item.chatMessages.map { msg ->
                                val newMsgId = if (!seenMsgIds.add(msg.id) || msg.id.isBlank()) {
                                    UUID.randomUUID().toString().also { seenMsgIds.add(it) }
                                } else {
                                    msg.id
                                }
                                msg.copy(id = newMsgId)
                            }
                            item.copy(id = newSessionId, chatMessages = dedupedMsgs)
                        }

                        val merged = deduped + s.sessions
                        val active = merged.first()
                        _uiState.update {
                            it.copy(
                                sessions = merged,
                                activeSessionId = active.id,
                                conversationTitle = active.title,
                                mode = active.mode,
                                chatMessages = active.chatMessages,
                                contextHistory = active.contextHistory,
                                pendingAttachments = emptyList(),
                                transientNotice = "成功导入 ${deduped.size} 个会话"
                            )
                        }
                        persistSessionsToDisk(merged, active.id)
                        CoreProtocol.AppLogger.info("Import", "Imported ${deduped.size} sessions")
                        onComplete(true, "成功导入 ${deduped.size} 个会话")
                    },
                    onFailure = { err ->
                        val msg = "导入失败: ${err.message}"
                        CoreProtocol.AppLogger.error("Import", msg)
                        onComplete(false, msg)
                    }
                )
            }
        }
    }

    fun clearAppLogs() {
        CoreProtocol.AppLogger.clear()
        _uiState.update { it.copy(transientNotice = "应用运行日志已清空") }
    }

    // ========================================================================
    // Prompt Execution: Live Streaming + Multimodal Upload + Stop + Safe Retention
    // ========================================================================

    fun stopGeneration() {
        val reqId = _uiState.value.activeRequestId
        if (reqId != 0L) {
            FluorineBridge.cancelStream(reqId)
            _uiState.update {
                it.copy(
                    taskStatusText = "正在停止...",
                    transientNotice = "已发送停止信号"
                )
            }
        }
    }

    fun submitPrompt(overridePrompt: String? = null) {
        val snapshot = _uiState.value
        if (snapshot.isBusy) return

        val rawPrompt = (overridePrompt ?: snapshot.promptInput).trim()
        val attachmentsToUse = if (overridePrompt == null) snapshot.pendingAttachments else emptyList()
        if (rawPrompt.isEmpty() && attachmentsToUse.isEmpty()) return

        if (snapshot.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    showSettingsDialog = true,
                    transientNotice = "请先在连接设置中填写 DeepSeek API Key"
                )
            }
            return
        }

        if (attachmentsToUse.isNotEmpty() && !CoreProtocol.supportsMultimodal(snapshot.model, snapshot.discoveredCapabilities)) {
            notifyMultimodalUnavailable()
            return
        }

        val effectivePrompt = if (rawPrompt.isEmpty() && attachmentsToUse.isNotEmpty()) {
            "请结合我上传的文件/图片内容进行分析与回答。"
        } else {
            rawPrompt
        }

        val targetSessionId = snapshot.activeSessionId
        val targetMode = snapshot.mode
        val isWork = targetMode == "work"
        val requestId = FluorineBridge.nextRequestId()

        activeExecutionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    activeRequestId = requestId,
                    taskStatusText = if (attachmentsToUse.isNotEmpty()) "准备上传附件..." else "正在请求模型..."
                )
            }

            try {
                // Step 1: Upload document/text attachments (and >4 MiB images) via DeepSeek Files API
                val preparedAttachments = mutableListOf<ChatAttachment>()
                for (att in attachmentsToUse) {
                    val needsFilesApi = att.kind != "image" || att.sizeBytes > 4L * 1024L * 1024L
                    if (needsFilesApi && att.fileId.isBlank()) {
                        _uiState.update { it.copy(taskStatusText = "正在上传解析：${att.name}") }
                        val uploadRaw = withContext(Dispatchers.IO) {
                            FluorineBridge.uploadAttachmentFileJson(
                                apiKey = snapshot.apiKey,
                                baseUrl = snapshot.baseUrl,
                                attachment = att,
                                connectTimeoutSec = snapshot.connectTimeoutSec,
                                readTimeoutSec = snapshot.readTimeoutSec.coerceAtLeast(150)
                            )
                        }
                        val upObj = runCatching { JSONObject(uploadRaw) }.getOrNull()
                        if (upObj?.optBoolean("ok", false) != true) {
                            val errMsg = upObj?.optString("error", uploadRaw) ?: "文件上传失败"
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    activeRequestId = 0L,
                                    taskStatusText = "附件上传失败",
                                    transientNotice = "附件 ${att.name} 上传失败：$errMsg"
                                )
                            }
                            return@launch
                        }
                        preparedAttachments.add(att.copy(fileId = upObj.optString("id", "")))
                    } else {
                        preparedAttachments.add(att)
                    }
                }

                // Step 2: Commit user message + live streaming placeholder assistant message
                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "user",
                    text = effectivePrompt,
                    tags = buildList {
                        add(if (isWork) "Work Agent" else "Chat")
                        if (preparedAttachments.isNotEmpty()) add("附件 ×${preparedAttachments.size}")
                    },
                    attachments = preparedAttachments
                )

                val assistantMsgId = UUID.randomUUID().toString()
                val baseTags = buildList {
                    if (isWork) add("Work Agent")
                    add(snapshot.model)
                    if (snapshot.useThinking && snapshot.thinkingMode != "disabled") {
                        add("Thinking · ${snapshot.effort}")
                    }
                    if (snapshot.useWeb) add("Web")
                }
                val liveAssistantPlaceholder = ChatMessage(
                    id = assistantMsgId,
                    role = "assistant",
                    text = "",
                    tags = baseTags
                )

                val newTitle = if (snapshot.autoGenerateTitle &&
                    snapshot.chatMessages.isEmpty() &&
                    (snapshot.conversationTitle == "新对话" || snapshot.conversationTitle == "新 Work 任务" || snapshot.conversationTitle.isBlank())
                ) {
                    effectivePrompt.replace('\n', ' ').take(24)
                } else {
                    snapshot.conversationTitle
                }

                val messagesWithPlaceholder = snapshot.chatMessages + userMsg + liveAssistantPlaceholder
                if (overridePrompt == null) {
                    _uiState.update {
                        it.copy(
                            promptInput = "",
                            pendingAttachments = emptyList(),
                            taskStatusText = if (isWork) "Agent 正在自主推理与调度本地工具..." else "模型流式响应中...",
                            toolsText = ""
                        )
                    }
                }
                updateSessionById(
                    targetSessionId = targetSessionId,
                    title = newTitle,
                    chatMessages = messagesWithPlaceholder,
                    persist = true
                )

                // Step 3: Build immutable payload from launching snapshot
                val messagesArr = buildHistoryMessagesJson(
                    history = snapshot.contextHistory,
                    maxHistoryTurns = snapshot.maxHistoryTurns,
                    preserveToolSubturns = isWork
                )
                if (snapshot.enableLatestReminder) {
                    messagesArr.put(
                        JSONObject()
                            .put("role", "latest_reminder")
                            .put("content", CoreProtocol.buildLatestReminder(Date(), "App"))
                    )
                }
                val userReqMsg = JSONObject()
                    .put("role", "user")
                    .put("content", effectivePrompt)
                if (preparedAttachments.isNotEmpty()) {
                    val attArr = JSONArray()
                    for (att in preparedAttachments) {
                        attArr.put(
                            JSONObject()
                                .put("kind", att.kind)
                                .put("name", att.name)
                                .put("mime_type", att.mimeType)
                                .put("local_path", att.localPath)
                                .put("file_id", att.fileId)
                        )
                    }
                    userReqMsg.put("attachments", attArr)
                }
                messagesArr.put(userReqMsg)

                val reqObj = buildEngineRequestJson(
                    state = snapshot,
                    stage = if (isWork) "work" else "chat",
                    useLocalTools = isWork,
                    systemPrompt = if (isWork) snapshot.customWorkSystemPrompt else snapshot.customChatSystemPrompt,
                    messagesArr = messagesArr
                )

                // Step 4: Execute streaming loop and reflect incremental updates live on UI
                val liveThinkingSb = StringBuilder()
                val liveContentSb = StringBuilder()
                val liveTools = mutableListOf<LocalToolCallRecord>()
                var liveSources = emptyList<com.uronic0x.fluorine.protocol.SearchSource>()

                fun pushLiveMessageUpdate() {
                    val targetSess = _uiState.value.sessions.firstOrNull { it.id == targetSessionId } ?: return
                    val updatedMsgs = targetSess.chatMessages.map { m ->
                        if (m.id == assistantMsgId) {
                            m.copy(
                                thinking = liveThinkingSb.toString(),
                                text = liveContentSb.toString(),
                                localToolCalls = liveTools.toList(),
                                sources = liveSources
                            )
                        } else {
                            m
                        }
                    }
                    updateSessionById(
                        targetSessionId = targetSessionId,
                        chatMessages = updatedMsgs,
                        persist = false
                    )
                }

                val rawResp = withContext(Dispatchers.IO) {
                    FluorineBridge.chatCompleteStreamingJson(
                        requestId = requestId,
                        requestJson = reqObj.toString(),
                        onEvent = { ev ->
                            viewModelScope.launch(Dispatchers.Main) {
                                when (ev) {
                                    is StreamUiEvent.ThinkingDelta -> {
                                        liveThinkingSb.append(ev.delta)
                                        pushLiveMessageUpdate()
                                    }
                                    is StreamUiEvent.ContentDelta -> {
                                        liveContentSb.append(ev.delta)
                                        pushLiveMessageUpdate()
                                    }
                                    is StreamUiEvent.Status -> {
                                        _uiState.update { it.copy(taskStatusText = ev.text) }
                                    }
                                    is StreamUiEvent.ToolRecord -> {
                                        liveTools.add(ev.record)
                                        _uiState.update { it.copy(toolsText = formatToolCallsLog(liveTools)) }
                                        pushLiveMessageUpdate()
                                    }
                                    is StreamUiEvent.SourcesUpdated -> {
                                        liveSources = ev.sources
                                        pushLiveMessageUpdate()
                                    }
                                }
                            }
                        }
                    )
                }

                val parsed = CoreProtocol.parseEngineTurnResult(rawResp)

                val finalThinking = parsed.thinking.ifBlank { liveThinkingSb.toString() }
                val finalContent = parsed.content.ifBlank { liveContentSb.toString() }
                val finalTools = if (parsed.localToolCalls.isNotEmpty()) parsed.localToolCalls else liveTools.toList()
                val finalSources = if (parsed.sources.isNotEmpty()) parsed.sources else liveSources
                val noticeText = parsed.incompleteNotice.ifBlank {
                    if (!parsed.ok) parsed.error else ""
                }

                val hasPartialOutput = finalContent.isNotBlank() ||
                    finalThinking.isNotBlank() ||
                    finalTools.isNotEmpty() ||
                    finalSources.isNotEmpty()

                val displayText = when {
                    finalContent.isNotBlank() -> finalContent
                    !parsed.ok && !hasPartialOutput -> "错误：${parsed.error.ifBlank { "请求失败" }}"
                    else -> ""
                }

                val finalTags = buildList {
                    addAll(baseTags)
                    if (finalTools.isNotEmpty()) add("Tools ×${finalTools.size}")
                    if (!parsed.ok && parsed.finishReason == "content_filter") add("内容审核")
                    else if (!parsed.ok) add("未完整结束")
                }

                val targetSess = _uiState.value.sessions.firstOrNull { it.id == targetSessionId }
                val currentMsgs = targetSess?.chatMessages ?: messagesWithPlaceholder
                val finalMsgs = currentMsgs.map { m ->
                    if (m.id == assistantMsgId) {
                        m.copy(
                            text = displayText,
                            tags = finalTags,
                            thinking = finalThinking,
                            sources = finalSources,
                            citations = parsed.citations,
                            localToolCalls = finalTools,
                            usageText = parsed.usageText,
                            incompleteNotice = noticeText,
                            isError = !parsed.ok && !hasPartialOutput
                        )
                    } else {
                        m
                    }
                }

                val baseHistory = targetSess?.contextHistory ?: snapshot.contextHistory
                val updatedHistory = if (parsed.ok || finalContent.isNotBlank()) {
                    baseHistory +
                        ContextTurn("user", effectivePrompt, attachments = preparedAttachments) +
                        ContextTurn(
                            role = "assistant",
                            content = finalContent,
                            subturnMessages = parsed.subturnMessages,
                            reasoningContent = finalThinking
                        )
                } else {
                    baseHistory
                }

                updateSessionById(
                    targetSessionId = targetSessionId,
                    chatMessages = finalMsgs,
                    contextHistory = updatedHistory,
                    persist = true
                )

                val toolSummaryLog = formatToolCallsLog(finalTools)
                val statusSummary = when {
                    !parsed.ok -> noticeText.ifBlank { "执行中断" }
                    isWork && finalTools.isEmpty() -> "完成（直接推理，未调用本地工具）"
                    isWork -> "完成 · 共执行 ${finalTools.size} 次本地工具调用（${parsed.rounds} 轮）"
                    else -> "完成"
                }

                _uiState.update {
                    it.copy(
                        taskStatusText = statusSummary,
                        toolsText = toolSummaryLog,
                        transientNotice = if (!parsed.ok && noticeText.isNotBlank()) noticeText else it.transientNotice
                    )
                }
            } finally {
                val files = withContext(Dispatchers.IO) {
                    CoreProtocol.listWorkspaceFiles(workspaceDir)
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        activeRequestId = 0L,
                        workspaceFiles = files
                    )
                }
            }
        }
    }

    /**
     * Atomic turn-group slicing:
     * Preserves `ContextTurn(user) + ContextTurn(assistant)` pairs cleanly without breaking
     * tool sub-turns (`assistant(tool_calls)` -> `tool` replies) or dropping `reasoning_content`.
     */
    private fun buildHistoryMessagesJson(
        history: List<ContextTurn>,
        maxHistoryTurns: Int,
        preserveToolSubturns: Boolean
    ): JSONArray {
        val arr = JSONArray()
        val maxEntries = maxHistoryTurns.coerceIn(2, 100)
        var startIdx = (history.size - maxEntries).coerceAtLeast(0)
        // Align startIdx so the slice starts on a "user" turn boundary
        while (startIdx < history.size && history[startIdx].role != "user") {
            startIdx++
        }
        val sliced = if (startIdx < history.size) history.subList(startIdx, history.size) else emptyList()

        for (turn in sliced) {
            if (turn.role == "user") {
                val userObj = JSONObject()
                    .put("role", "user")
                    .put("content", turn.content)
                if (turn.attachments.isNotEmpty()) {
                    val attArr = JSONArray()
                    for (att in turn.attachments) {
                        attArr.put(
                            JSONObject()
                                .put("kind", att.kind)
                                .put("name", att.name)
                                .put("mime_type", att.mimeType)
                                .put("local_path", att.localPath)
                                .put("file_id", att.fileId)
                        )
                    }
                    userObj.put("attachments", attArr)
                }
                arr.put(userObj)
            } else if (turn.role == "assistant") {
                if (preserveToolSubturns && turn.subturnMessages.isNotEmpty()) {
                    for (rawSub in turn.subturnMessages) {
                        val subObj = runCatching { JSONObject(rawSub) }.getOrNull() ?: continue
                        arr.put(subObj)
                    }
                }
                if (turn.content.isNotBlank()) {
                    val aObj = JSONObject()
                        .put("role", "assistant")
                        .put("content", turn.content)
                    if (turn.reasoningContent.isNotEmpty()) {
                        aObj.put("reasoning_content", turn.reasoningContent)
                    }
                    arr.put(aObj)
                }
            }
        }
        return arr
    }

    private fun buildEngineRequestJson(
        state: FluorineUiState,
        stage: String,
        useLocalTools: Boolean,
        systemPrompt: String,
        messagesArr: JSONArray
    ): JSONObject {
        return JSONObject()
            .put("api_key", state.apiKey)
            .put("base_url", state.baseUrl)
            .put("connect_timeout_sec", state.connectTimeoutSec)
            .put("read_timeout_sec", state.readTimeoutSec)
            .put("shell_timeout_secs", state.shellTimeoutSec)
            .put("model", state.model)
            .put("stage", stage)
            .put("use_web", state.useWeb)
            .put("unlock_web", state.unlockWebSearch)
            .put("use_thinking", state.useThinking)
            .put("thinking_mode", state.thinkingMode)
            .put("use_local_tools", useLocalTools)
            .put("tool_config", state.workToolsConfig.toJson())
            .put("effort", state.effort)
            .put("max_tokens", state.maxTokens)
            .put("enable_temperature", state.enableTemperature)
            .put("temperature", state.temperature.toDouble())
            .put("max_rounds", state.maxToolRounds)
            .put("system_prompt", systemPrompt)
            .put("workspace_dir", workspaceDir.absolutePath)
            .put("allow_sdcard_readonly", state.allowSdcardReadonly)
            .put("messages", messagesArr)
    }

    private fun formatToolCallsLog(calls: List<LocalToolCallRecord>): String {
        if (calls.isEmpty()) return ""
        return calls.joinToString("\n\n") { tc ->
            "[#${tc.round}] ${tc.name}(${tc.arguments})\n→ ${tc.output}"
        }
    }
}
