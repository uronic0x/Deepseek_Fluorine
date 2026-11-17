package com.uronic0x.fluorine.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uronic0x.fluorine.bridge.FluorineBridge
import com.uronic0x.fluorine.protocol.ChatMessage
import com.uronic0x.fluorine.protocol.ContextTurn
import com.uronic0x.fluorine.protocol.ConversationSession
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.LocalToolCallRecord
import com.uronic0x.fluorine.protocol.WorkspaceFileArtifact
import com.uronic0x.fluorine.ui.components.FluorineSettingsDraft
import com.uronic0x.fluorine.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
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
    val defaultExpandSources: Boolean = false, // Search links collapsed by default (#9)

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

    // Agent & Prompt settings
    val maxToolRounds: Int = 10,
    val maxHistoryTurns: Int = 24,
    val enableLatestReminder: Boolean = true,
    val autoGenerateTitle: Boolean = true,
    val customChatSystemPrompt: String = "",
    val customWorkSystemPrompt: String = "",

    // Workspace & Harness settings
    val allowSdcardReadonly: Boolean = false,
    val shellTimeoutSec: Int = 20,
    val workspacePath: String = "",
    val workspaceFiles: List<WorkspaceFileArtifact> = emptyList(),

    // Composer & Runtime state
    val promptInput: String = "",
    val isBusy: Boolean = false,
    val transientNotice: String? = null,
    val taskStatusText: String = "就绪",
    val toolsText: String = "",

    // Modals & Dialogs
    val showSettingsDialog: Boolean = false,
    val showWorkspaceFilesDialog: Boolean = false,
    val previewArtifact: WorkspaceFileArtifact? = null,
    val previewArtifactContent: String = "",
    val renamingSession: ConversationSession? = null,
    val isDiscovering: Boolean = false,
    val discoverySummary: String = "",
    val engineInfo: String = ""
)

class FluorineViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("fluorine_prefs", Context.MODE_PRIVATE)
    private val sessionsStoreFile = File(application.filesDir, "sessions_store.json")
    private val legacySessionFile = File(application.filesDir, "session_state.json")
    private val workspaceDir: File = FluorineBridge.initWorkspace(application)

    private val _uiState = MutableStateFlow(FluorineUiState())
    val uiState: StateFlow<FluorineUiState> = _uiState.asStateFlow()

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
        val customChatSystemPrompt = prefs.getString("custom_chat_system_prompt", "") ?: ""
        val customWorkSystemPrompt = prefs.getString("custom_work_system_prompt", "") ?: ""

        val savedSdcard = prefs.getBoolean("allow_sdcard", false)
        val shellTimeoutSec = prefs.getInt("shell_timeout_sec", 20).coerceIn(5, 120)

        FluorineBridge.setAllowSdcardReadonly(savedSdcard)
        val infoRaw = FluorineBridge.engineInfoJson()

        val (loadedSessions, activeId) = loadSessionsFromDisk()
        val activeSession = loadedSessions.firstOrNull { it.id == activeId } ?: loadedSessions.first()

        val effectiveModel = customModel.ifBlank { savedModel }
        val effectiveWeb = if (effectiveModel == "deepseek-v4-pro" && !unlockWebSearch) false else savedWeb
        val wsFiles = CoreProtocol.listWorkspaceFiles(workspaceDir)

        _uiState.update {
            it.copy(
                sessions = loadedSessions,
                activeSessionId = activeSession.id,
                conversationTitle = activeSession.title,
                mode = activeSession.mode,
                chatMessages = activeSession.chatMessages,
                contextHistory = activeSession.contextHistory,
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
                allowSdcardReadonly = savedSdcard,
                shellTimeoutSec = shellTimeoutSec,
                workspacePath = workspaceDir.absolutePath,
                workspaceFiles = wsFiles,
                engineInfo = infoRaw
            )
        }
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
        // Migrate from legacy single-session file if present
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

    private fun persistSessionsToDisk(sessions: List<ConversationSession>, activeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = CoreProtocol.serializeSessionsStore(sessions, activeId)
                sessionsStoreFile.writeText(json, Charsets.UTF_8)
            }
        }
    }

    private fun syncActiveSessionState(
        title: String = _uiState.value.conversationTitle,
        mode: String = _uiState.value.mode,
        chatMessages: List<ChatMessage> = _uiState.value.chatMessages,
        contextHistory: List<ContextTurn> = _uiState.value.contextHistory,
        touchUpdatedAt: Boolean = true
    ) {
        val s = _uiState.value
        val now = System.currentTimeMillis()
        val updatedList = s.sessions.map { item ->
            if (item.id == s.activeSessionId) {
                item.copy(
                    title = title,
                    mode = mode,
                    updatedAt = if (touchUpdatedAt) now else item.updatedAt,
                    chatMessages = chatMessages,
                    contextHistory = contextHistory
                )
            } else {
                item
            }
        }
        _uiState.update {
            it.copy(
                sessions = updatedList,
                conversationTitle = title,
                mode = mode,
                chatMessages = chatMessages,
                contextHistory = contextHistory
            )
        }
        persistSessionsToDisk(updatedList, s.activeSessionId)
    }

    // ========================================================================
    // Multi-Session Management (Requirement #2)
    // ========================================================================

    fun newConversation() {
        if (_uiState.value.isBusy) return
        val current = _uiState.value
        // If current active session is already empty, just reset its state cleanly
        val active = current.sessions.firstOrNull { it.id == current.activeSessionId }
        if (active != null && active.chatMessages.isEmpty()) {
            _uiState.update {
                it.copy(
                    conversationTitle = "新对话",
                    promptInput = "",
                    taskStatusText = "就绪",
                    toolsText = ""
                )
            }
            return
        }

        val newSession = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            mode = current.mode,
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
                chatMessages = emptyList(),
                contextHistory = emptyList(),
                promptInput = "",
                taskStatusText = "就绪",
                toolsText = ""
            )
        }
        persistSessionsToDisk(updatedSessions, newSession.id)
    }

    fun selectSession(sessionId: String) {
        if (_uiState.value.isBusy) return
        val target = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        _uiState.update {
            it.copy(
                activeSessionId = target.id,
                conversationTitle = target.title,
                mode = target.mode,
                chatMessages = target.chatMessages,
                contextHistory = target.contextHistory,
                taskStatusText = "就绪",
                toolsText = ""
            )
        }
        persistSessionsToDisk(_uiState.value.sessions, target.id)
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
                    contextHistory = nextActive.contextHistory
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
                taskStatusText = "就绪",
                toolsText = "",
                transientNotice = "已清空全部历史会话"
            )
        }
        persistSessionsToDisk(list, fresh.id)
    }

    // ========================================================================
    // Mode, Toggles & Settings
    // ========================================================================

    fun clearTransientNotice() {
        if (_uiState.value.transientNotice != null) {
            _uiState.update { it.copy(transientNotice = null) }
        }
    }

    fun setMode(newMode: String) {
        if (newMode != "chat" && newMode != "work") return
        _uiState.update { it.copy(mode = newMode) }
        prefs.edit().putString("mode", newMode).apply()
        syncActiveSessionState(mode = newMode, touchUpdatedAt = false)
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
        _uiState.update { it.copy(showSettingsDialog = open) }
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
                customChatSystemPrompt = draft.customChatSystemPrompt.trim(),
                customWorkSystemPrompt = draft.customWorkSystemPrompt.trim(),
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
            putString("custom_chat_system_prompt", draft.customChatSystemPrompt.trim())
            putString("custom_work_system_prompt", draft.customWorkSystemPrompt.trim())
            putBoolean("allow_sdcard", draft.allowSdcardReadonly)
            putInt("shell_timeout_sec", draft.shellTimeoutSec)
            apply()
        }
        CoreProtocol.AppLogger.info("Settings", "Saved settings: model=$effectiveModel baseUrl=$normalizedBaseUrl maxRounds=${draft.maxToolRounds}")
    }

    fun discoverCapabilities(overrideKey: String? = null, overrideBaseUrl: String? = null) {
        val s = _uiState.value
        val key = (overrideKey ?: s.apiKey).trim()
        val baseUrl = (overrideBaseUrl ?: s.baseUrl).trim().ifEmpty { CoreProtocol.DEFAULT_API_BASE_URL }
        if (key.isEmpty()) {
            _uiState.update { it.copy(discoverySummary = "请先填写 API Key") }
            return
        }
        _uiState.update { it.copy(isDiscovering = true, discoverySummary = "正在探测上游接口与本地工具链...") }
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
            val summary = if (ok) {
                val models = parsed?.optJSONArray("models")
                val modelList = buildList {
                    if (models != null) {
                        for (i in 0 until models.length()) add(models.optString(i))
                    }
                }
                val toolsCount = parsed?.optJSONArray("local_tools")?.length() ?: 15
                "连接成功 ($baseUrl) · 可用模型: ${modelList.joinToString(", ").ifEmpty { "默认" }} · 本地工具: $toolsCount 个"
            } else {
                "探测失败: ${parsed?.optString("error", raw)}"
            }
            _uiState.update { it.copy(isDiscovering = false, discoverySummary = summary) }
        }
    }

    // ========================================================================
    // Workspace File Export / Preview / Management (Requirement #1)
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
        return File(workspaceDir, rel)
    }

    fun previewWorkspaceFile(artifact: WorkspaceFileArtifact) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = resolveWorkspaceFile(artifact)
            val content = if (!file.exists() || !file.isFile) {
                "[文件不存在: ${artifact.relativePath}]"
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
                    previewArtifact = artifact.copy(bytes = if (file.exists()) file.length() else artifact.bytes),
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
            val file = resolveWorkspaceFile(artifact)
            if (file.exists() && file.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
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
    // Chat History & App Log Export / Import (Requirement #13)
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
                        val existingIds = s.sessions.map { it.id }.toSet()
                        val deduped = importedList.map { item ->
                            if (item.id in existingIds) item.copy(id = UUID.randomUUID().toString()) else item
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
    // Prompt Execution: Chat & Autonomous Agent Work Mode (Requirements #8, #12)
    // ========================================================================

    fun submitPrompt(overridePrompt: String? = null) {
        val state = _uiState.value
        val prompt = (overridePrompt ?: state.promptInput).trim()
        if (prompt.isEmpty() || state.isBusy) return

        if (state.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    showSettingsDialog = true,
                    transientNotice = "请先在连接设置中填写 DeepSeek API Key"
                )
            }
            return
        }

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = prompt,
            tags = listOf(if (state.mode == "work") "Work Agent" else "Chat")
        )
        val newTitle = if (state.autoGenerateTitle &&
            state.chatMessages.isEmpty() &&
            (state.conversationTitle == "新对话" || state.conversationTitle.isBlank())
        ) {
            prompt.replace('\n', ' ').take(24)
        } else {
            state.conversationTitle
        }

        val updatedMessages = state.chatMessages + userMsg
        _uiState.update {
            it.copy(
                conversationTitle = newTitle,
                promptInput = "",
                isBusy = true,
                chatMessages = updatedMessages
            )
        }
        syncActiveSessionState(title = newTitle, chatMessages = updatedMessages)

        viewModelScope.launch {
            try {
                if (state.mode == "work") {
                    executeAutonomousWorkTurn(prompt)
                } else {
                    executeChatTurn(prompt)
                }
            } finally {
                val files = withContext(Dispatchers.IO) {
                    CoreProtocol.listWorkspaceFiles(workspaceDir)
                }
                _uiState.update { it.copy(isBusy = false, workspaceFiles = files) }
            }
        }
    }

    private suspend fun executeChatTurn(prompt: String) {
        val state = _uiState.value
        val messagesArr = buildHistoryMessagesJson(
            history = state.contextHistory,
            maxHistoryTurns = state.maxHistoryTurns,
            preserveToolSubturns = false
        )
        if (state.enableLatestReminder) {
            messagesArr.put(
                JSONObject()
                    .put("role", "latest_reminder")
                    .put("content", CoreProtocol.buildLatestReminder(Date(), "App"))
            )
        }
        messagesArr.put(JSONObject().put("role", "user").put("content", prompt))

        val reqObj = buildEngineRequestJson(
            state = state,
            stage = "chat",
            useLocalTools = false,
            systemPrompt = state.customChatSystemPrompt,
            messagesArr = messagesArr
        )

        val rawResp = withContext(Dispatchers.IO) {
            FluorineBridge.chatCompleteJson(reqObj.toString())
        }
        val parsed = CoreProtocol.parseEngineTurnResult(rawResp)
        if (!parsed.ok) {
            appendErrorAssistantMessage(parsed.error.ifBlank { "请求失败" })
            return
        }

        val tags = buildList {
            add(state.model)
            if (state.useThinking && state.thinkingMode != "disabled") add("Thinking · ${state.effort}")
            if (state.useWeb) add("Web")
        }
        val assistantMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            text = parsed.content.ifBlank { "（无正文输出）" },
            tags = tags,
            thinking = parsed.thinking,
            sources = parsed.sources,
            citations = parsed.citations,
            localToolCalls = parsed.localToolCalls,
            usageText = parsed.usageText
        )
        val newMessages = _uiState.value.chatMessages + assistantMsg
        val newHistory = _uiState.value.contextHistory +
            ContextTurn("user", prompt) +
            ContextTurn("assistant", parsed.content)

        syncActiveSessionState(
            chatMessages = newMessages,
            contextHistory = newHistory
        )
    }

    /**
     * Autonomous Agent Work Mode (Requirements #8 & #12):
     * - No rigid 4-step workflow. Provides all deterministic + Android/Termux harness tools
     *   (plus optional web_search) in a single unified agent loop and lets the model decide
     *   how to analyze, plan, and call tools.
     * - Strict DeepSeek Official Prefix Cache Hit compliance:
     *   1. Static `system` prompt + static `tools` array across turns.
     *   2. Historical `ContextTurn`s preserve `subturnMessages` (`assistant(tool_calls)` + `role: "tool"`,
     *      with `reasoning_content` stripped) so Turn 2+ sends the exact token prefix from Turn 1's
     *      tool rounds and hits the server KV cache (`prompt_cache_hit_tokens`).
     */
    private suspend fun executeAutonomousWorkTurn(prompt: String) {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                taskStatusText = "Agent 正在自主推理与调度本地工具...",
                toolsText = ""
            )
        }

        val messagesArr = buildHistoryMessagesJson(
            history = state.contextHistory,
            maxHistoryTurns = state.maxHistoryTurns,
            preserveToolSubturns = true
        )
        if (state.enableLatestReminder) {
            messagesArr.put(
                JSONObject()
                    .put("role", "latest_reminder")
                    .put("content", CoreProtocol.buildLatestReminder(Date(), "App"))
            )
        }
        messagesArr.put(JSONObject().put("role", "user").put("content", prompt))

        val reqObj = buildEngineRequestJson(
            state = state,
            stage = "work",
            useLocalTools = true,
            systemPrompt = state.customWorkSystemPrompt,
            messagesArr = messagesArr
        )

        val rawResp = withContext(Dispatchers.IO) {
            FluorineBridge.chatCompleteJson(reqObj.toString())
        }
        val parsed = CoreProtocol.parseEngineTurnResult(rawResp)
        if (!parsed.ok) {
            _uiState.update { it.copy(taskStatusText = "执行中断") }
            appendErrorAssistantMessage(parsed.error.ifBlank { "Work Agent 执行失败" })
            return
        }

        val toolSummaryLog = formatToolCallsLog(parsed.localToolCalls)
        val statusSummary = if (parsed.localToolCalls.isEmpty()) {
            "完成（直接推理，未调用本地工具）"
        } else {
            "完成 · 共执行 ${parsed.localToolCalls.size} 次本地工具调用（${parsed.rounds} 轮）"
        }

        _uiState.update {
            it.copy(
                taskStatusText = statusSummary,
                toolsText = toolSummaryLog
            )
        }

        val tags = buildList {
            add("Work Agent")
            add(state.model)
            if (parsed.localToolCalls.isNotEmpty()) add("Tools ×${parsed.localToolCalls.size}")
            if (state.useWeb) add("Web")
        }

        val assistantMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            text = parsed.content.ifBlank { "（无正文输出）" },
            tags = tags,
            thinking = parsed.thinking,
            sources = parsed.sources,
            citations = parsed.citations,
            localToolCalls = parsed.localToolCalls,
            usageText = parsed.usageText
        )

        val newMessages = _uiState.value.chatMessages + assistantMsg
        val newHistory = _uiState.value.contextHistory +
            ContextTurn("user", prompt) +
            ContextTurn(
                role = "assistant",
                content = parsed.content,
                subturnMessages = parsed.subturnMessages
            )

        syncActiveSessionState(
            chatMessages = newMessages,
            contextHistory = newHistory
        )
    }

    private fun buildHistoryMessagesJson(
        history: List<ContextTurn>,
        maxHistoryTurns: Int,
        preserveToolSubturns: Boolean
    ): JSONArray {
        val arr = JSONArray()
        val sliced = history.takeLast(maxHistoryTurns.coerceIn(2, 100))
        for (turn in sliced) {
            if (turn.role == "user") {
                arr.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", turn.content)
                )
            } else if (turn.role == "assistant") {
                if (preserveToolSubturns && turn.subturnMessages.isNotEmpty()) {
                    for (rawSub in turn.subturnMessages) {
                        val subObj = runCatching { JSONObject(rawSub) }.getOrNull() ?: continue
                        subObj.remove("reasoning_content")
                        arr.put(subObj)
                    }
                }
                if (turn.content.isNotBlank()) {
                    arr.put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", turn.content)
                    )
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

    private fun appendErrorAssistantMessage(errorText: String) {
        val errMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            text = "错误：$errorText",
            tags = listOf("Error"),
            isError = true
        )
        val newMessages = _uiState.value.chatMessages + errMsg
        syncActiveSessionState(chatMessages = newMessages, touchUpdatedAt = false)
    }
}
