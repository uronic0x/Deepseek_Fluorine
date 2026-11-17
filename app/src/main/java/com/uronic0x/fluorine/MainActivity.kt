package com.uronic0x.fluorine

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.WorkspaceFileArtifact
import com.uronic0x.fluorine.ui.FluorineUiState
import com.uronic0x.fluorine.ui.FluorineViewModel
import com.uronic0x.fluorine.ui.components.AgentExecutionPanel
import com.uronic0x.fluorine.ui.components.MessageItemCard
import com.uronic0x.fluorine.ui.components.RenameSessionDialog
import com.uronic0x.fluorine.ui.components.SessionDrawerContent
import com.uronic0x.fluorine.ui.components.SettingsDialog
import com.uronic0x.fluorine.ui.components.WelcomeSection
import com.uronic0x.fluorine.ui.components.WorkspaceFilePreviewDialog
import com.uronic0x.fluorine.ui.components.WorkspaceFilesDialog
import com.uronic0x.fluorine.ui.theme.AppThemeMode
import com.uronic0x.fluorine.ui.theme.FluorineTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: FluorineViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            FluorineTheme(themeMode = uiState.themeMode) {
                FluorineWorkbenchApp(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun FluorineWorkbenchApp(
    uiState: FluorineUiState,
    viewModel: FluorineViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val dismissKeyboard: () -> Unit = remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    // Automatically hide soft keyboard when user scrolls the conversation list
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            dismissKeyboard()
        }
    }

    // Auto-scroll to latest message
    LaunchedEffect(uiState.chatMessages.size, uiState.isBusy) {
        if (uiState.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.chatMessages.lastIndex)
        }
    }

    // One-shot Toast feedback for warnings/exports without persistent bottom status bar
    LaunchedEffect(uiState.transientNotice) {
        val notice = uiState.transientNotice
        if (!notice.isNullOrBlank()) {
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            viewModel.clearTransientNotice()
        }
    }

    // Close navigation drawer on system back press
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // SAF Launchers for exporting Workspace Files, Chat History, and App Logs
    var pendingExportArtifact by remember { mutableStateOf<WorkspaceFileArtifact?>(null) }

    val createWorkspaceFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val artifact = pendingExportArtifact
        if (uri != null && artifact != null) {
            viewModel.writeWorkspaceFileToUri(context, artifact, uri) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportArtifact = null
    }

    val exportHistoryJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.writeTextContentToUri(
                context = context,
                targetUri = uri,
                contentProducer = { viewModel.buildSessionsJsonExport() },
                successLabel = "已导出聊天历史 JSON"
            ) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportHistoryMarkdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            viewModel.writeTextContentToUri(
                context = context,
                targetUri = uri,
                contentProducer = { viewModel.buildSessionsMarkdownExport() },
                successLabel = "已导出聊天历史 Markdown"
            ) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importHistoryJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importSessionsFromUri(context, uri) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportAppLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            viewModel.writeTextContentToUri(
                context = context,
                targetUri = uri,
                contentProducer = { CoreProtocol.AppLogger.dumpText() },
                successLabel = "已导出应用运行日志"
            ) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val triggerExportWorkspaceFile: (WorkspaceFileArtifact) -> Unit = { artifact ->
        pendingExportArtifact = artifact
        createWorkspaceFileLauncher.launch(artifact.fileName.ifBlank { "workspace_output.txt" })
    }

    val triggerShareWorkspaceFile: (WorkspaceFileArtifact) -> Unit = { artifact ->
        val file = viewModel.resolveWorkspaceFile(artifact)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(context, "文件不存在: ${artifact.relativePath}", Toast.LENGTH_SHORT).show()
        } else {
            val text = runCatching {
                if (file.length() <= 256 * 1024) {
                    file.readText(Charsets.UTF_8)
                } else {
                    file.inputStream().bufferedReader(Charsets.UTF_8).use {
                        val buf = CharArray(64 * 1024)
                        val n = it.read(buf)
                        String(buf, 0, n.coerceAtLeast(0))
                    }
                }
            }.getOrDefault("")
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, artifact.fileName)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享 ${artifact.fileName}"))
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawerContent(
                sessions = uiState.sessions,
                activeSessionId = uiState.activeSessionId,
                themeMode = uiState.themeMode,
                workspaceFileCount = uiState.workspaceFiles.size,
                onSelectSession = { id ->
                    dismissKeyboard()
                    viewModel.selectSession(id)
                    scope.launch { drawerState.close() }
                },
                onNewSession = {
                    dismissKeyboard()
                    viewModel.newConversation()
                    scope.launch { drawerState.close() }
                },
                onRenameSessionRequest = { session ->
                    viewModel.requestRenameSession(session)
                },
                onDeleteSession = { id ->
                    viewModel.deleteSession(id)
                },
                onOpenWorkspaceFiles = {
                    dismissKeyboard()
                    scope.launch { drawerState.close() }
                    viewModel.openWorkspaceFilesDialog(true)
                },
                onCycleThemeMode = {
                    viewModel.cycleThemeMode()
                },
                onOpenSettings = {
                    dismissKeyboard()
                    scope.launch { drawerState.close() }
                    viewModel.openSettings(true)
                }
            )
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopHeaderBar(
                    activeModel = uiState.model,
                    isKeyReady = uiState.apiKey.isNotBlank(),
                    themeMode = uiState.themeMode,
                    workspaceFileCount = uiState.workspaceFiles.size,
                    onOpenDrawer = {
                        dismissKeyboard()
                        scope.launch { drawerState.open() }
                    },
                    onOpenSettings = {
                        dismissKeyboard()
                        viewModel.openSettings(true)
                    },
                    onNewChat = {
                        dismissKeyboard()
                        viewModel.newConversation()
                    },
                    onOpenWorkspaceFiles = {
                        dismissKeyboard()
                        viewModel.openWorkspaceFilesDialog(true)
                    },
                    onCycleTheme = {
                        viewModel.cycleThemeMode()
                    }
                )
            },
            bottomBar = {
                ComposerBottomBar(
                    uiState = uiState,
                    onUpdatePrompt = viewModel::updatePromptInput,
                    onToggleWeb = viewModel::toggleWeb,
                    onToggleThinking = viewModel::toggleThinking,
                    onSubmit = {
                        dismissKeyboard()
                        viewModel.submitPrompt()
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { dismissKeyboard() })
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 860.dp)
                        .padding(horizontal = 14.dp)
                ) {
                    ModeNavigationRow(
                        mode = uiState.mode,
                        onSelectMode = { mode ->
                            dismissKeyboard()
                            viewModel.setMode(mode)
                        }
                    )

                    if (uiState.isBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("messages_list"),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (uiState.chatMessages.isEmpty()) {
                            item(key = "welcome") {
                                WelcomeSection()
                            }
                        }

                        items(
                            items = uiState.chatMessages,
                            key = { it.id }
                        ) { msg ->
                            MessageItemCard(
                                message = msg,
                                enableMarkdown = uiState.enableMarkdown,
                                enableLatex = uiState.enableLatex,
                                defaultExpandThinking = uiState.defaultExpandThinking,
                                defaultExpandTools = uiState.defaultExpandTools,
                                defaultExpandSources = uiState.defaultExpandSources,
                                onExportWorkspaceFile = triggerExportWorkspaceFile,
                                onShareWorkspaceFile = triggerShareWorkspaceFile,
                                onPreviewWorkspaceFile = { artifact ->
                                    dismissKeyboard()
                                    viewModel.previewWorkspaceFile(artifact)
                                },
                                onRetryMessage = if (!uiState.isBusy) {
                                    { retryMsg ->
                                        dismissKeyboard()
                                        viewModel.submitPrompt(retryMsg.text)
                                    }
                                } else {
                                    null
                                },
                                onOutsideTap = dismissKeyboard
                            )
                        }

                        if (uiState.mode == "work") {
                            item(key = "agent_execution_panel") {
                                AgentExecutionPanel(
                                    statusText = uiState.taskStatusText,
                                    toolsLogText = uiState.toolsText,
                                    isBusy = uiState.isBusy
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (uiState.showSettingsDialog) {
        val ts = remember {
            SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        }
        SettingsDialog(
            uiState = uiState,
            onDismiss = { viewModel.openSettings(false) },
            onSaveSettings = viewModel::saveSettings,
            onDiscoverCapabilities = { key, url -> viewModel.discoverCapabilities(key, url) },
            onOpenWorkspaceFiles = {
                viewModel.openSettings(false)
                viewModel.openWorkspaceFilesDialog(true)
            },
            onExportHistoryJson = {
                exportHistoryJsonLauncher.launch("fluorine_history_$ts.json")
            },
            onExportHistoryMarkdown = {
                exportHistoryMarkdownLauncher.launch("fluorine_history_$ts.md")
            },
            onImportHistoryJson = {
                importHistoryJsonLauncher.launch(arrayOf("application/json", "text/*"))
            },
            onExportAppLogs = {
                exportAppLogsLauncher.launch("fluorine_app_$ts.log")
            },
            onClearAppLogs = viewModel::clearAppLogs,
            onClearAllSessions = viewModel::clearAllSessions
        )
    }

    if (uiState.showWorkspaceFilesDialog) {
        WorkspaceFilesDialog(
            files = uiState.workspaceFiles,
            onDismiss = { viewModel.openWorkspaceFilesDialog(false) },
            onRefresh = viewModel::refreshWorkspaceFiles,
            onPreviewFile = { artifact -> viewModel.previewWorkspaceFile(artifact) },
            onExportFile = triggerExportWorkspaceFile,
            onShareFile = triggerShareWorkspaceFile,
            onDeleteFile = viewModel::deleteWorkspaceFile
        )
    }

    val previewTarget = uiState.previewArtifact
    if (previewTarget != null) {
        WorkspaceFilePreviewDialog(
            artifact = previewTarget,
            content = uiState.previewArtifactContent,
            onDismiss = viewModel::closePreviewDialog,
            onExport = triggerExportWorkspaceFile,
            onShare = triggerShareWorkspaceFile
        )
    }

    val renaming = uiState.renamingSession
    if (renaming != null) {
        RenameSessionDialog(
            session = renaming,
            onDismiss = { viewModel.requestRenameSession(null) },
            onConfirm = { newTitle -> viewModel.renameSession(renaming.id, newTitle) }
        )
    }
}

@Composable
private fun TopHeaderBar(
    activeModel: String,
    isKeyReady: Boolean,
    themeMode: AppThemeMode,
    workspaceFileCount: Int,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onOpenWorkspaceFiles: () -> Unit,
    onCycleTheme: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.testTag("open_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Menu,
                        contentDescription = stringResource(R.string.drawer_title),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("header_model_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isKeyReady) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                    }
                    Text(
                        text = activeModel,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenWorkspaceFiles)
                        .testTag("top_workspace_files_button"),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = "工作区文件",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "$workspaceFileCount",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                IconButton(
                    onClick = onCycleTheme,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("top_theme_toggle_button")
                ) {
                    Icon(
                        imageVector = if (themeMode == AppThemeMode.LIGHT) {
                            Icons.Outlined.LightMode
                        } else {
                            Icons.Outlined.DarkMode
                        },
                        contentDescription = "切换暗色/白色主题",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onNewChat)
                        .testTag("new_chat_button"),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "新会话",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ModeNavigationRow(
    mode: String,
    onSelectMode: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(modifier = Modifier.padding(3.dp)) {
                ModeTabItem(
                    title = stringResource(R.string.mode_chat),
                    selected = mode == "chat",
                    onClick = { onSelectMode("chat") },
                    testTag = "mode_chat_button"
                )
                ModeTabItem(
                    title = stringResource(R.string.mode_work),
                    selected = mode == "work",
                    onClick = { onSelectMode("work") },
                    testTag = "mode_work_button"
                )
            }
        }
    }
}

@Composable
private fun ModeTabItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = fg
        )
    }
}

@Composable
private fun ComposerBottomBar(
    uiState: FluorineUiState,
    onUpdatePrompt: (String) -> Unit,
    onToggleWeb: () -> Unit,
    onToggleThinking: () -> Unit,
    onSubmit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                OutlinedTextField(
                    value = uiState.promptInput,
                    onValueChange = onUpdatePrompt,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.prompt_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp, max = 160.dp)
                        .testTag("prompt_input"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToggleChipButton(
                            icon = Icons.Outlined.Language,
                            label = stringResource(R.string.toggle_web),
                            active = uiState.useWeb,
                            onClick = onToggleWeb,
                            testTag = "web_toggle_button"
                        )
                        ToggleChipButton(
                            icon = Icons.Outlined.Psychology,
                            label = stringResource(R.string.toggle_thinking),
                            active = uiState.useThinking && uiState.thinkingMode != "disabled",
                            onClick = onToggleThinking,
                            testTag = "thinking_toggle_button"
                        )
                    }

                    Button(
                        onClick = onSubmit,
                        enabled = !uiState.isBusy && uiState.promptInput.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = stringResource(R.string.send_action),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isBusy) {
                                stringResource(R.string.sending_action)
                            } else {
                                stringResource(R.string.send_action)
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleChipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bg = if (active) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val fg = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(7.dp),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = fg
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg
            )
        }
    }
}
