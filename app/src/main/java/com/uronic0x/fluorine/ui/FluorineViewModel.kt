package com.uronic0x.fluorine.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uronic0x.fluorine.bridge.FluorineBridge
import com.uronic0x.fluorine.protocol.ContentFilterException
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.ProtocolMessage
import com.uronic0x.fluorine.protocol.SessionSnapshot
import com.uronic0x.fluorine.protocol.StreamAbortedException
import com.uronic0x.fluorine.protocol.TokenUsage
import com.uronic0x.fluorine.protocol.ToolCallRecord
import com.uronic0x.fluorine.protocol.UiChatMessage
import com.uronic0x.fluorine.protocol.WorkMode
import com.uronic0x.fluorine.protocol.WorkReport
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
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

data class FluorineUiState(
    val appName: String = "fluorine",
    val displayName: String = "Fluorine",
    val subtitle: String = "DeepSeek 本地工作台",
    val convTitle: String = "Fluorine",
    val isRustJniLoaded: Boolean = FluorineBridge.isRustJniLoaded,
    val workspacePath: String = "",
    val runtimeSummaryLine: String = "",
    val mode: WorkMode = WorkMode.CHAT,
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val effort: String = "high",
    val budget: Int = 16384,
    val webEnabled: Boolean = true,
    val localToolsEnabled: Boolean = true,
    val deviceToolsEnabled: Boolean = true,
    val allowAndroidStorage: Boolean = false,
    val promptInput: String = "",
    val busy: Boolean = false,
    val statusText: String = "",
    val messages: List<UiChatMessage> = emptyList(),
    val showTaskPanel: Boolean = false,
    val activeWorkStep: Int = -1, // 0=plan, 1=research, 2=execute, 3=review, -1=idle/done
    val taskStatusText: String = "准备开始",
    val planText: String = "",
    val toolsLogText: String = "",
    val reviewText: String = "",
    val planExpanded: Boolean = true,
    val toolsExpanded: Boolean = false,
    val reviewExpanded: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val discoverBusy: Boolean = false,
    val modelInfoText: String = "",
    val selectedToolCallDetail: ToolCallRecord? = null,
    val reportsCount: Int = 0
)

class FluorineViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "fluorine_prefs"
        private const val SESSION_FILE_NAME = "session_state.json"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_EFFORT = "effort"
        private const val KEY_BUDGET = "budget"
        private const val KEY_MODE = "mode"
        private const val KEY_WEB = "web_enabled"
        private const val KEY_LOCAL_TOOLS = "local_tools_enabled"
        private const val KEY_DEVICE_TOOLS = "device_tools_enabled"
        private const val KEY_ALLOW_STORAGE = "allow_android_storage"
    }

    private val _uiState = MutableStateFlow(FluorineUiState())
    val uiState: StateFlow<FluorineUiState> = _uiState.asStateFlow()

    private val messageIdCounter = AtomicLong(1L)
    private val requestIdCounter = AtomicLong(100L)

    private var history: MutableList<ProtocolMessage> = mutableListOf()
    private var reports: MutableList<WorkReport> = mutableListOf()
    private var activeJob: Job? = null
    private var activeRequestId: Long = 0L
    private val abortRequested = AtomicBoolean(false)

    init {
        loadPersistedSettings()
        initializeWorkspaceAndSession()
    }

    private fun appContext(): Context = getApplication<Application>().applicationContext

    private fun prefs() = appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun sessionFile(): File = File(appContext().filesDir, SESSION_FILE_NAME)

    private fun loadPersistedSettings() {
        val p = prefs()
        val savedKey = p.getString(KEY_API_KEY, "") ?: ""
        val savedModel = p.getString(KEY_MODEL, "deepseek-flash") ?: "deepseek-flash"
        val savedEffort = p.getString(KEY_EFFORT, "high") ?: "high"
        val savedBudget = p.getInt(KEY_BUDGET, 16384)
        val savedModeStr = p.getString(KEY_MODE, "chat") ?: "chat"
        val savedMode = if (savedModeStr == "work") WorkMode.WORK else WorkMode.CHAT
        val savedWeb = p.getBoolean(KEY_WEB, savedModel == "deepseek-flash")
        val savedLocalTools = p.getBoolean(KEY_LOCAL_TOOLS, true)
        val savedDeviceTools = p.getBoolean(KEY_DEVICE_TOOLS, true)
        val savedAllowStorage = p.getBoolean(KEY_ALLOW_STORAGE, false)

        _uiState.update {
            it.copy(
                apiKey = savedKey,
                model = savedModel,
                effort = savedEffort,
                budget = savedBudget,
                mode = savedMode,
                webEnabled = if (savedModel != "deepseek-flash") false else savedWeb,
                localToolsEnabled = savedLocalTools,
                deviceToolsEnabled = savedDeviceTools,
                allowAndroidStorage = savedAllowStorage
            )
        }
    }

    private fun savePersistedSettings() {
        val st = _uiState.value
        prefs().edit()
            .putString(KEY_API_KEY, st.apiKey)
            .putString(KEY_MODEL, st.model)
            .putString(KEY_EFFORT, st.effort)
            .putInt(KEY_BUDGET, st.budget)
            .putString(KEY_MODE, st.mode.id)
            .putBoolean(KEY_WEB, st.webEnabled)
            .putBoolean(KEY_LOCAL_TOOLS, st.localToolsEnabled)
            .putBoolean(KEY_DEVICE_TOOLS, st.deviceToolsEnabled)
            .putBoolean(KEY_ALLOW_STORAGE, st.allowAndroidStorage)
            .apply()
    }

    private fun persistSessionAsync() {
        val st = _uiState.value
        val snapshot = SessionSnapshot(
            convTitle = st.convTitle,
            mode = st.mode,
            messages = st.messages,
            history = history.toList(),
            reports = reports.toList(),
            showTaskPanel = st.showTaskPanel,
            activeWorkStep = st.activeWorkStep,
            taskStatusText = st.taskStatusText,
            planText = st.planText,
            toolsLogText = st.toolsLogText,
            reviewText = st.reviewText
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = CoreProtocol.serializeSession(snapshot)
                sessionFile().writeText(json, StandardCharsets.UTF_8)
            }
        }
    }

    private fun ensureWorkspaceSeeded(context: Context): File {
        val wsDir = File(context.filesDir, "workspace")
        if (!wsDir.exists()) wsDir.mkdirs()
        val readme = File(wsDir, "README_WORKSPACE.md")
        if (!readme.exists()) {
            readme.writeText(
                """
                # Fluorine Android 本地沙箱工作区
                
                当前目录位于 Android 应用私有存储 (`context.filesDir/workspace`)。
                Work 模式可在此工作区内安全调用以下 19 项本地工具：
                
                1. **确定性计算 (9 项)**：`calc`, `stats`, `unit_convert`, `date_calc`, `text_metrics`, `json_query`, `csv_summary`, `text_diff`, `encode_hash`
                2. **工作区与 Git (7 项)**：`device_probe`, `fs_list`, `fs_read`, `fs_search`, `git_inspect`, `fs_write`, `fs_edit`
                3. **Android 设备与 Shell (3 项)**：`termux_device`, `android_ui`, `shell_exec`
                """.trimIndent() + "\n",
                StandardCharsets.UTF_8
            )
        }
        val sampleCsv = File(wsDir, "metrics_sample.csv")
        if (!sampleCsv.exists()) {
            sampleCsv.writeText(
                """
                endpoint,latency_ms,tokens_per_sec,cache_hit
                chat_flash,312.5,94.2,true
                work_plan,428.0,88.6,true
                work_exec,615.4,76.1,false
                work_review,389.2,91.0,true
                """.trimIndent() + "\n",
                StandardCharsets.UTF_8
            )
        }
        return wsDir
    }

    private fun initializeWorkspaceAndSession() {
        viewModelScope.launch {
            val ctx = appContext()
            val allowStorage = _uiState.value.allowAndroidStorage
            val (wsPath, metaJson, restoredSession) = withContext(Dispatchers.IO) {
                val ws = ensureWorkspaceSeeded(ctx)
                val meta = FluorineBridge.getMetaJson(ctx, ws.absolutePath, allowStorage)
                val sf = sessionFile()
                val snap = if (sf.exists()) {
                    runCatching {
                        CoreProtocol.deserializeSession(sf.readText(StandardCharsets.UTF_8))
                    }.getOrNull()
                } else null
                Triple(ws.canonicalPath, meta, snap)
            }
            if (restoredSession != null) {
                history = restoredSession.history.toMutableList()
                reports = restoredSession.reports.toMutableList()
                val maxId = restoredSession.messages.maxOfOrNull { it.id } ?: 0L
                messageIdCounter.set(max(1L, maxId + 1L))
                _uiState.update { st ->
                    st.copy(
                        convTitle = restoredSession.convTitle,
                        mode = restoredSession.mode,
                        messages = restoredSession.messages,
                        showTaskPanel = restoredSession.showTaskPanel,
                        activeWorkStep = restoredSession.activeWorkStep,
                        taskStatusText = restoredSession.taskStatusText,
                        planText = restoredSession.planText,
                        toolsLogText = restoredSession.toolsLogText,
                        reviewText = restoredSession.reviewText,
                        reportsCount = reports.size
                    )
                }
            }
            applyMetaJson(wsPath, metaJson)
        }
    }

    fun initializeWorkspaceAndMeta() {
        viewModelScope.launch {
            val ctx = appContext()
            val allowStorage = _uiState.value.allowAndroidStorage
            val (wsPath, metaJson) = withContext(Dispatchers.IO) {
                val ws = ensureWorkspaceSeeded(ctx)
                val meta = FluorineBridge.getMetaJson(ctx, ws.absolutePath, allowStorage)
                ws.canonicalPath to meta
            }
            applyMetaJson(wsPath, metaJson)
        }
    }

    private fun applyMetaJson(wsPath: String, rawMeta: String) {
        try {
            val obj = JSONObject(rawMeta)
            val appName = obj.optString("app_name", "fluorine").ifBlank { "fluorine" }
            val displayName = obj.optString("display_name", "Fluorine").ifBlank { "Fluorine" }
            val subtitle = obj.optString("subtitle", "DeepSeek 本地工作台").ifBlank { "DeepSeek 本地工作台" }
            val rt = obj.optJSONObject("runtime")
            val osArch = "${rt?.optString("os", "android")}/${rt?.optString("arch", "arm64-v8a")}"
            val memTotal = rt?.optJSONObject("memory")?.optLong("mem_total_mb", 0L) ?: 0L
            val batPct = rt?.optJSONObject("battery")?.optInt("percentage", -1) ?: -1
            val summaryParts = mutableListOf("环境: $osArch")
            if (memTotal > 0) summaryParts.add("内存: ${memTotal}MB")
            if (batPct in 0..100) summaryParts.add("电量: ${batPct}%")
            _uiState.update { st ->
                st.copy(
                    appName = appName,
                    displayName = displayName,
                    subtitle = subtitle,
                    convTitle = if (history.isEmpty() && st.messages.isEmpty()) displayName else st.convTitle,
                    workspacePath = obj.optString("workspace", wsPath),
                    runtimeSummaryLine = summaryParts.joinToString(" · ")
                )
            }
        } catch (_: Exception) {
            _uiState.update { it.copy(workspacePath = wsPath) }
        }
    }

    fun setMode(mode: WorkMode) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(mode = mode) }
        savePersistedSettings()
        persistSessionAsync()
    }

    fun updatePrompt(text: String) {
        if (text.length <= 40_000) {
            _uiState.update { it.copy(promptInput = text) }
        }
    }

    fun setWebEnabled(enabled: Boolean) {
        if (_uiState.value.busy) return
        val currentModel = _uiState.value.model
        if (enabled && currentModel != "deepseek-flash") {
            _uiState.update {
                it.copy(
                    webEnabled = false,
                    statusText = "官方联网请在设置中选择 Flash"
                )
            }
        } else {
            _uiState.update { it.copy(webEnabled = enabled) }
        }
        savePersistedSettings()
    }

    fun setLocalToolsEnabled(enabled: Boolean) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(localToolsEnabled = enabled) }
        savePersistedSettings()
    }

    fun setDeviceToolsEnabled(enabled: Boolean) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(deviceToolsEnabled = enabled) }
        savePersistedSettings()
    }

    fun setSettingsDialogVisible(visible: Boolean) {
        if (_uiState.value.busy && visible) return
        _uiState.update { it.copy(showSettingsDialog = visible) }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
        savePersistedSettings()
    }

    fun updateModel(modelId: String) {
        _uiState.update { st ->
            if (modelId != "deepseek-flash") {
                st.copy(
                    model = modelId,
                    webEnabled = false,
                    statusText = "Pro 已关闭官方联网（仍可使用本地工具）"
                )
            } else {
                st.copy(model = modelId)
            }
        }
        savePersistedSettings()
    }

    fun updateEffort(effortId: String) {
        _uiState.update { it.copy(effort = effortId) }
        savePersistedSettings()
    }

    fun updateBudget(tokens: Int) {
        _uiState.update { it.copy(budget = tokens) }
        savePersistedSettings()
    }

    fun setAllowAndroidStorage(allowed: Boolean) {
        _uiState.update {
            it.copy(
                allowAndroidStorage = allowed,
                statusText = if (allowed) "已启用 /sdcard 只读访问" else "已关闭 /sdcard 访问"
            )
        }
        savePersistedSettings()
        initializeWorkspaceAndMeta()
    }

    fun togglePlanExpanded() {
        _uiState.update { it.copy(planExpanded = !it.planExpanded) }
    }

    fun toggleToolsExpanded() {
        _uiState.update { it.copy(toolsExpanded = !it.toolsExpanded) }
    }

    fun toggleReviewExpanded() {
        _uiState.update { it.copy(reviewExpanded = !it.reviewExpanded) }
    }

    fun selectToolCallDetail(record: ToolCallRecord?) {
        _uiState.update { it.copy(selectedToolCallDetail = record) }
    }

    fun notifyStatus(msg: String) {
        _uiState.update { it.copy(statusText = msg) }
    }

    fun newChat() {
        if (_uiState.value.busy) return
        history.clear()
        reports.clear()
        _uiState.update { st ->
            st.copy(
                convTitle = st.displayName,
                messages = emptyList(),
                showTaskPanel = false,
                activeWorkStep = -1,
                taskStatusText = "准备开始",
                planText = "",
                toolsLogText = "",
                reviewText = "",
                reportsCount = 0,
                statusText = ""
            )
        }
        persistSessionAsync()
    }

    fun clearPersistedData() {
        if (_uiState.value.busy) return
        history.clear()
        reports.clear()
        prefs().edit().clear().apply()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionFile().delete() }
        }
        _uiState.update { st ->
            st.copy(
                apiKey = "",
                convTitle = st.displayName,
                messages = emptyList(),
                showTaskPanel = false,
                activeWorkStep = -1,
                taskStatusText = "准备开始",
                planText = "",
                toolsLogText = "",
                reviewText = "",
                reportsCount = 0,
                statusText = "已清除本地保存的 Key 与会话记录"
            )
        }
    }

    fun discoverCapabilities() {
        if (_uiState.value.discoverBusy) return
        _uiState.update { it.copy(discoverBusy = true, modelInfoText = "正在检测本机 Harness 与上游模型…") }
        viewModelScope.launch {
            val ctx = appContext()
            val st = _uiState.value
            val wsPath = st.workspacePath.ifBlank { ensureWorkspaceSeeded(ctx).canonicalPath }
            val lines = mutableListOf<String>()
            try {
                val metaRaw = withContext(Dispatchers.IO) {
                    FluorineBridge.getMetaJson(ctx, wsPath, st.allowAndroidStorage)
                }
                val metaObj = JSONObject(metaRaw)
                val rt = metaObj.optJSONObject("runtime") ?: JSONObject()
                val engineLabel = if (st.isRustJniLoaded) "Rust JNI (libfluorine.so)" else "Android Native Engine"
                lines.add(
                    "执行引擎：$engineLabel（零 HTTP 回环 · 本地持久化）"
                )
                lines.add(
                    "本机 Harness：${rt.optString("os", "android")}/${rt.optString("arch", "arm64")} · " +
                        "Android=${rt.optBoolean("is_android", true)} · " +
                        "Termux-API=${rt.optBoolean("termux_api_ready", false)} · " +
                        "ADB=${rt.optBoolean("adb_ready", false)} · " +
                        "Shizuku=${rt.optBoolean("shizuku_ready", false)}"
                )
                lines.add("工作区：${metaObj.optString("workspace", wsPath)}")
                val bat = rt.optJSONObject("battery")
                val mem = rt.optJSONObject("memory")
                if (bat != null || mem != null) {
                    val batStr = if (bat != null) "${bat.optInt("percentage", -1)}% (${bat.optString("status")})" else "未知"
                    val memStr = if (mem != null) "${mem.optLong("mem_available_mb")}/${mem.optLong("mem_total_mb")} MB" else "未知"
                    lines.add("设备遥测：电池 $batStr · 可用内存 $memStr")
                }

                val key = st.apiKey.trim()
                if (key.isEmpty()) {
                    lines.add("提示：尚未填写 API Key，跳过上游模型列表检测（本地 19 项工具已就绪）。")
                    _uiState.update { it.copy(modelInfoText = lines.joinToString("\n")) }
                    return@launch
                }

                val modelsRaw = withContext(Dispatchers.IO) {
                    FluorineBridge.fetchModelsJson(key)
                }
                val modelsObj = JSONObject(modelsRaw)
                val errObj = modelsObj.optJSONObject("error")
                if (errObj != null) {
                    throw IllegalStateException(errObj.optString("message", "上游模型检测失败"))
                }
                lines.add("Key 检测成功。上游返回：")
                val dataArr = modelsObj.optJSONArray("data") ?: JSONArray()
                for (i in 0 until dataArr.length()) {
                    val m = dataArr.optJSONObject(i) ?: continue
                    val id = m.optString("id")
                    val ctxWin = if (m.has("context_window")) m.optString("context_window") else "1M"
                    val maxOut = if (m.has("max_output_tokens")) m.optString("max_output_tokens") else "384K"
                    lines.add("• $id · context $ctxWin · output $maxOut")
                }
                _uiState.update { it.copy(modelInfoText = lines.joinToString("\n")) }
            } catch (e: Exception) {
                lines.add("检测提示：${e.message ?: "检测超时或网络异常"}")
                _uiState.update { it.copy(modelInfoText = lines.joinToString("\n")) }
            } finally {
                _uiState.update { it.copy(discoverBusy = false) }
            }
        }
    }

    fun stopGeneration() {
        if (!_uiState.value.busy) return
        abortRequested.set(true)
        val reqId = activeRequestId
        if (reqId != 0L) {
            FluorineBridge.cancelStream(reqId)
        }
    }

    private fun appendUiMessage(role: String, text: String = "", progress: String? = null): Long {
        val msgId = messageIdCounter.getAndIncrement()
        val uiMsg = UiChatMessage(
            id = msgId,
            role = role,
            content = text,
            progressText = progress
        )
        _uiState.update { st ->
            st.copy(messages = st.messages + uiMsg)
        }
        return msgId
    }

    private fun updateUiMessage(msgId: Long, transform: (UiChatMessage) -> UiChatMessage) {
        _uiState.update { st ->
            st.copy(
                messages = st.messages.map { if (it.id == msgId) transform(it) else it }
            )
        }
    }

    private fun finalizeAssistantUiMessage(
        msgId: Long,
        raw: ProtocolMessage,
        usage: TokenUsage?,
        model: String
    ) {
        val citations = CoreProtocol.citationPoints(raw)
        val sources = CoreProtocol.sourceList(raw)
        val uText = CoreProtocol.usageText(usage, model)
        updateUiMessage(msgId) { existing ->
            existing.copy(
                content = raw.content,
                reasoningContent = raw.reasoningContent.trim(),
                progressText = null,
                citations = citations,
                sources = sources,
                localToolCalls = raw.localToolCalls,
                usageText = uText
            )
        }
    }

    private suspend fun runStage(
        stage: String,
        messagesJson: JSONArray,
        apiKey: String,
        model: String,
        web: Boolean,
        localTools: Boolean,
        deviceTools: Boolean,
        effort: String,
        budget: Int,
        targetMsgId: Long?
    ): CoreProtocol.StreamResult {
        if (abortRequested.get()) throw StreamAbortedException()
        if (targetMsgId != null) {
            updateUiMessage(targetMsgId) { it.copy(progressText = "正在连接…") }
        }

        val maxTokens = when (stage) {
            "plan" -> 1024
            "review" -> 2048
            else -> budget
        }

        val reqObj = JSONObject()
            .put("stage", stage)
            .put("model", model)
            .put("web", web)
            .put("local_tools", localTools)
            .put("device_tools", localTools && deviceTools)
            .put("effort", effort)
            .put("max_tokens", maxTokens)
            .put("messages", messagesJson)

        val reqId = requestIdCounter.getAndIncrement()
        activeRequestId = reqId
        val ctx = appContext()
        val wsPath = _uiState.value.workspacePath.ifBlank { ensureWorkspaceSeeded(ctx).canonicalPath }
        val allowStorage = _uiState.value.allowAndroidStorage

        return try {
            withContext(Dispatchers.IO) {
                val acc = CoreProtocol.SseAccumulator { update ->
                    val raw = update.message
                    val webStatus = CoreProtocol.formatServerToolEvent(update.serverToolEvent)
                    val localEvt = update.localToolEvent
                    val newProgress = when (localEvt?.optString("type")) {
                        "round_started" -> {
                            "本地执行第 ${localEvt.optInt("round", 1)}/${localEvt.optInt("max_rounds", 20)} 轮…"
                        }
                        "call_started" -> {
                            val name = localEvt.optString("name")
                            val args = localEvt.optString("arguments")
                            _uiState.update { st ->
                                st.copy(toolsLogText = st.toolsLogText + "▶ $name($args)\n")
                            }
                            "调用本地工具：$name(${CoreProtocol.clipText(args, 48)})"
                        }
                        "call_completed" -> {
                            val name = localEvt.optString("name")
                            val res = localEvt.optString("result")
                            _uiState.update { st ->
                                st.copy(toolsLogText = st.toolsLogText + "✓ ${CoreProtocol.clipText(res, 600)}\n\n")
                            }
                            "已完成：$name"
                        }
                        else -> when {
                            webStatus != null -> webStatus
                            update.thinking -> "正在思考…"
                            raw.content.isNotEmpty() -> "正在生成…"
                            else -> null
                        }
                    }

                    if (targetMsgId != null) {
                        updateUiMessage(targetMsgId) { existing ->
                            existing.copy(
                                content = raw.content,
                                reasoningContent = raw.reasoningContent.trim(),
                                progressText = newProgress ?: existing.progressText
                            )
                        }
                    }
                    if (stage == "plan") {
                        _uiState.update { it.copy(planText = raw.content) }
                    }
                    if (stage == "review") {
                        _uiState.update { it.copy(reviewText = raw.content) }
                    }
                }

                var streamError: Throwable? = null
                FluorineBridge.streamChat(
                    context = ctx,
                    requestId = reqId,
                    apiKey = apiKey,
                    requestJson = reqObj.toString(),
                    workspacePath = wsPath,
                    allowStorage = allowStorage
                ) { jsonLine ->
                    if (abortRequested.get()) {
                        false
                    } else {
                        try {
                            acc.pushFrame(jsonLine)
                            true
                        } catch (t: Throwable) {
                            streamError = t
                            false
                        }
                    }
                }

                if (abortRequested.get()) {
                    throw StreamAbortedException()
                }
                streamError?.let { throw it }
                acc.result()
            }
        } catch (e: ContentFilterException) {
            if (targetMsgId != null) {
                updateUiMessage(targetMsgId) { it.copy(content = "", progressText = null) }
            }
            if (stage == "plan") _uiState.update { it.copy(planText = "") }
            if (stage == "review") _uiState.update { it.copy(reviewText = "") }
            throw e
        } finally {
            activeRequestId = 0L
            if (targetMsgId != null) {
                updateUiMessage(targetMsgId) { it.copy(progressText = null) }
            }
        }
    }

    private fun setWorkStep(stepIdx: Int, statusLabel: String) {
        _uiState.update {
            it.copy(
                activeWorkStep = stepIdx,
                taskStatusText = statusLabel
            )
        }
    }

    fun submitPrompt(overridePrompt: String? = null) {
        val current = _uiState.value
        if (current.busy) return
        val prompt = (overridePrompt ?: current.promptInput).trim()
        if (prompt.isEmpty()) return

        val key = current.apiKey.trim()
        if (key.isEmpty()) {
            _uiState.update {
                it.copy(
                    showSettingsDialog = true,
                    statusText = "请先填入 DeepSeek API Key"
                )
            }
            return
        }

        val maxHistory = if (current.mode == WorkMode.WORK) 105 else 114
        if (history.size > maxHistory) {
            _uiState.update {
                it.copy(statusText = "会话已接近长度上限，请导出后新建会话")
            }
            return
        }

        abortRequested.set(false)
        _uiState.update {
            it.copy(
                busy = true,
                statusText = "",
                promptInput = if (overridePrompt == null) "" else it.promptInput
            )
        }
        appendUiMessage(role = "user", text = prompt)
        persistSessionAsync()

        val mode = current.mode
        val model = current.model
        val web = current.webEnabled
        val localTools = current.localToolsEnabled
        val deviceTools = current.deviceToolsEnabled
        val effort = current.effort
        val budget = current.budget

        val keepHistoryServerCalls = mode == WorkMode.CHAT && web && model == "deepseek-flash"
        val pendingMessages = mutableListOf<ProtocolMessage>()
        for (m in history) {
            if (m.role == "assistant") {
                pendingMessages.add(
                    ProtocolMessage(
                        role = "assistant",
                        content = m.content,
                        serverToolCallsJson = if (keepHistoryServerCalls) m.serverToolCallsJson else null
                    )
                )
            } else {
                pendingMessages.add(m)
            }
        }
        pendingMessages.add(
            ProtocolMessage(
                role = "latest_reminder",
                content = CoreProtocol.buildLatestReminder(Date(), "App")
            )
        )
        pendingMessages.add(
            ProtocolMessage(
                role = "user",
                content = prompt
            )
        )

        fun toMessagesJsonArray(list: List<ProtocolMessage>, keepStc: Boolean): JSONArray {
            val arr = JSONArray()
            for (item in list) {
                arr.put(CoreProtocol.sanitizeHistoryMessage(item, keepServerToolCalls = keepStc))
            }
            return arr
        }

        activeJob = viewModelScope.launch {
            var targetMsgId: Long? = null
            var executionCommitted = false
            var report = WorkReport(
                prompt = prompt,
                mode = mode.id,
                model = model,
                effort = effort,
                web = web,
                localTools = localTools,
                deviceTools = deviceTools
            )
            val usagesList = mutableListOf<TokenUsage?>()

            try {
                if (mode == WorkMode.WORK) {
                    _uiState.update {
                        it.copy(
                            showTaskPanel = true,
                            planText = "",
                            toolsLogText = "",
                            reviewText = "",
                            planExpanded = true,
                            toolsExpanded = false,
                            reviewExpanded = false
                        )
                    }

                    // Step 0: Plan
                    setWorkStep(0, "拆解目标")
                    notifyStatus("Work 1/4 · 生成计划")
                    val planRes = runStage(
                        stage = "plan",
                        messagesJson = toMessagesJsonArray(pendingMessages, keepStc = false),
                        apiKey = key,
                        model = model,
                        web = web,
                        localTools = false,
                        deviceTools = false,
                        effort = effort,
                        budget = budget,
                        targetMsgId = null
                    )
                    report = report.copy(plan = planRes.message.content)
                    usagesList.add(planRes.usage)
                    _uiState.update { it.copy(planText = planRes.message.content) }

                    val executeHistory = mutableListOf<ProtocolMessage>().apply {
                        addAll(pendingMessages)
                        add(ProtocolMessage(role = "assistant", content = planRes.message.content))
                        add(
                            ProtocolMessage(
                                role = "user",
                                content = "请按这份计划执行最初任务，交付结果；不要把计划当成已完成的事实。"
                            )
                        )
                    }

                    // Step 1: Research (only when Flash + web + localTools)
                    if (web && localTools && model == "deepseek-flash") {
                        setWorkStep(1, "联网取证")
                        notifyStatus("Work 2/4 · 联网收集资料")
                        val res = runStage(
                            stage = "research",
                            messagesJson = toMessagesJsonArray(executeHistory, keepStc = true),
                            apiKey = key,
                            model = model,
                            web = true,
                            localTools = false,
                            deviceTools = false,
                            effort = effort,
                            budget = budget,
                            targetMsgId = null
                        )
                        usagesList.add(res.usage)
                        executeHistory.add(ProtocolMessage(role = "assistant", content = res.message.content))
                        executeHistory.add(
                            ProtocolMessage(
                                role = "user",
                                content = "请结合以上联网收集的事实，必要时调用本地计算或设备/工作区工具获取精确结果，生成最终交付正文。"
                            )
                        )
                    } else {
                        setWorkStep(1, if (web) "并入执行阶段" else "跳过联网")
                    }

                    // Step 2: Execute
                    setWorkStep(2, if (localTools) "本地工具执行" else "执行与交付")
                    notifyStatus(if (localTools) "Work 3/4 · 本地工具执行" else "Work 3/4 · 生成交付")
                    if (localTools) {
                        _uiState.update { it.copy(toolsExpanded = true) }
                    }
                    targetMsgId = appendUiMessage(role = "assistant")
                    val execRes = runStage(
                        stage = "execute",
                        messagesJson = toMessagesJsonArray(executeHistory, keepStc = !localTools && web),
                        apiKey = key,
                        model = model,
                        web = web,
                        localTools = localTools,
                        deviceTools = deviceTools,
                        effort = effort,
                        budget = budget,
                        targetMsgId = targetMsgId
                    )
                    val toolsLogNow = _uiState.value.toolsLogText
                    report = report.copy(
                        answer = execRes.message,
                        toolsLog = toolsLogNow
                    )
                    usagesList.add(execRes.usage)

                    history = mutableListOf<ProtocolMessage>().apply {
                        addAll(executeHistory)
                        add(ProtocolMessage(role = "assistant", content = execRes.message.content))
                    }
                    executionCommitted = true
                    finalizeAssistantUiMessage(targetMsgId, execRes.message, execRes.usage, model)

                    // Step 3: Review
                    setWorkStep(3, "文本审查")
                    notifyStatus("Work 4/4 · 审查交付物")
                    val reviewHistory = mutableListOf<ProtocolMessage>().apply {
                        addAll(history)
                        add(
                            ProtocolMessage(
                                role = "user",
                                content = "请检查刚才的交付物，指出计算、工具结果、证据或完成度方面的缺口。不要假定计划已经全部完成。"
                            )
                        )
                    }
                    val revRes = runStage(
                        stage = "review",
                        messagesJson = toMessagesJsonArray(reviewHistory, keepStc = false),
                        apiKey = key,
                        model = model,
                        web = false,
                        localTools = false,
                        deviceTools = false,
                        effort = effort,
                        budget = budget,
                        targetMsgId = null
                    )
                    report = report.copy(
                        review = revRes.message.content,
                        usages = usagesList.toList()
                    )
                    usagesList.add(revRes.usage)
                    report = report.copy(usages = usagesList.toList())
                    _uiState.update {
                        it.copy(
                            reviewText = revRes.message.content,
                            reviewExpanded = true
                        )
                    }
                    setWorkStep(-1, "已完成")
                } else {
                    targetMsgId = appendUiMessage(role = "assistant")
                    val chatRes = runStage(
                        stage = "chat",
                        messagesJson = toMessagesJsonArray(
                            pendingMessages,
                            keepStc = web && model == "deepseek-flash"
                        ),
                        apiKey = key,
                        model = model,
                        web = web,
                        localTools = false,
                        deviceTools = false,
                        effort = effort,
                        budget = budget,
                        targetMsgId = targetMsgId
                    )
                    if (!chatRes.title.isNullOrBlank()) {
                        _uiState.update { it.copy(convTitle = chatRes.title) }
                    }
                    usagesList.add(chatRes.usage)
                    report = report.copy(
                        answer = chatRes.message,
                        usages = usagesList.toList()
                    )
                    history = mutableListOf<ProtocolMessage>().apply {
                        addAll(pendingMessages)
                        add(
                            ProtocolMessage(
                                role = "assistant",
                                content = chatRes.message.content,
                                serverToolCallsJson = if (web && model == "deepseek-flash") {
                                    chatRes.message.serverToolCallsJson
                                } else {
                                    null
                                }
                            )
                        )
                    }
                    executionCommitted = true
                    finalizeAssistantUiMessage(targetMsgId, chatRes.message, chatRes.usage, model)
                }

                reports.add(report)
                _uiState.update {
                    it.copy(
                        reportsCount = reports.size,
                        statusText = ""
                    )
                }
            } catch (err: Throwable) {
                val why = when {
                    err is StreamAbortedException || abortRequested.get() ->
                        "已停止接收"
                    else -> err.message ?: "请求未完成"
                }
                if (targetMsgId != null && !executionCommitted) {
                    val isFilter = err is ContentFilterException
                    updateUiMessage(targetMsgId) { existing ->
                        existing.copy(
                            role = "error",
                            content = if (isFilter) "" else existing.content,
                            progressText = null,
                            incompleteNote = "本轮未完成，未加入下一轮上下文。"
                        )
                    }
                }
                appendUiMessage(role = "error", text = why)
                notifyStatus(why)
                if (mode == WorkMode.WORK) {
                    setWorkStep(-1, if (executionCommitted) "执行完成，审查未完成" else "任务未完成")
                }
                if (executionCommitted) {
                    val updatedReview = (report.review + "\n审查未完成：$why").trim()
                    val committedReport = report.copy(
                        review = updatedReview,
                        usages = usagesList.toList()
                    )
                    reports.add(committedReport)
                    _uiState.update {
                        it.copy(
                            reviewText = updatedReview,
                            reportsCount = reports.size
                        )
                    }
                } else {
                    _uiState.update { it.copy(promptInput = prompt) }
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
                persistSessionAsync()
            }
        }
    }

    fun getExportFileName(): String {
        val slug = CoreProtocol.slugifyAppName(_uiState.value.appName)
        return "$slug-report.md"
    }

    fun buildMarkdownContent(): String? {
        if (reports.isEmpty()) {
            notifyStatus("尚无已完成的回复可导出")
            return null
        }
        return CoreProtocol.buildMarkdownReport(reports)
    }

    fun writeMarkdownToUri(context: Context, uri: Uri) {
        val md = buildMarkdownContent() ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(md.toByteArray(StandardCharsets.UTF_8))
                        os.flush()
                    }
                }.isSuccess
            }
            notifyStatus(
                if (ok) "已导出 ${getExportFileName()}"
                else "导出失败，请重试"
            )
        }
    }
}
