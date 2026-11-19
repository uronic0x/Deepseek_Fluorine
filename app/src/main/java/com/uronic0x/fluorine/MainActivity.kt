package com.uronic0x.fluorine

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uronic0x.fluorine.protocol.ChatAttachment
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.WorkspaceFileArtifact
import com.uronic0x.fluorine.ui.FluorineUiState
import com.uronic0x.fluorine.ui.FluorineViewModel
import com.uronic0x.fluorine.ui.components.AgentExecutionPanel
import com.uronic0x.fluorine.ui.components.ClearAllSessionsConfirmDialog
import com.uronic0x.fluorine.ui.components.DeleteSessionConfirmDialog
import com.uronic0x.fluorine.ui.components.MessageItemCard
import com.uronic0x.fluorine.ui.components.RenameSessionDialog
import com.uronic0x.fluorine.ui.components.SessionDrawerContent
import com.uronic0x.fluorine.ui.components.SettingsDialog
import com.uronic0x.fluorine.ui.components.WelcomeSection
import com.uronic0x.fluorine.ui.components.WorkspaceFilePreviewDialog
import com.uronic0x.fluorine.ui.components.WorkspaceFilesDialog
import com.uronic0x.fluorine.ui.components.loadThumbnailBitmap
import com.uronic0x.fluorine.ui.theme.AppThemeMode
import com.uronic0x.fluorine.ui.theme.FluorineTheme
import kotlinx.coroutines.launch
import java.io.File
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

    var showAttachmentDrawer by rememberSaveable { mutableStateOf(false) }

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

    // Auto-scroll to latest message when session changes or new messages arrive
    LaunchedEffect(uiState.activeSessionId, uiState.chatMessages.size, uiState.isBusy) {
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

    // Close navigation drawer or attachment tray on system back press
    BackHandler(enabled = drawerState.isOpen || showAttachmentDrawer) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            showAttachmentDrawer = false
        }
    }

    // Camera capture launcher via FileProvider
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (saved && file != null) {
            viewModel.addCameraCapture(file)
        } else {
            runCatching { file?.delete() }
        }
    }

    // Android Photo Picker (zero storage permission required per Google Play policy)
    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        uris.forEach { uri ->
            viewModel.addUriAttachment(context, uri, requestedKind = "image")
        }
    }

    // Document / text / spreadsheet file picker via SAF OpenDocument
    val pickDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.addUriAttachment(context, uri, requestedKind = "document")
        }
    }

    val multimodalSupported = remember(uiState.model, uiState.discoveredCapabilities) {
        viewModel.isMultimodalSupportedForCurrentModel()
    }

    val triggerCamera: () -> Unit = {
        if (!multimodalSupported) {
            viewModel.notifyMultimodalUnavailable()
        } else {
            runCatching {
                val file = viewModel.createCameraCaptureFile()
                pendingCameraFile = file
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                takePictureLauncher.launch(uri)
            }.onFailure { err ->
                pendingCameraFile = null
                Toast.makeText(context, "无法启动相机: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val triggerPhotoPicker: () -> Unit = {
        if (!multimodalSupported) {
            viewModel.notifyMultimodalUnavailable()
        } else {
            pickPhotoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    val triggerDocumentPicker: () -> Unit = {
        if (!multimodalSupported) {
            viewModel.notifyMultimodalUnavailable()
        } else {
            pickDocumentLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/epub+zip",
                    "application/json",
                    "text/*",
                    "*/*"
                )
            )
        }
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
        val fileResult = runCatching { viewModel.resolveWorkspaceFile(artifact) }
        val file = fileResult.getOrNull()
        if (file == null || !file.exists() || !file.isFile) {
            Toast.makeText(context, "文件不存在或路径受限: ${artifact.relativePath}", Toast.LENGTH_SHORT).show()
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
                onNewSession = { targetMode ->
                    dismissKeyboard()
                    viewModel.newConversation(targetMode)
                    scope.launch { drawerState.close() }
                },
                onRenameSessionRequest = { session ->
                    viewModel.requestRenameSession(session)
                },
                onDeleteSession = { id ->
                    viewModel.requestDeleteSession(id)
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
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ComposerBottomBar(
                        uiState = uiState,
                        multimodalSupported = multimodalSupported,
                        showAttachmentDrawer = showAttachmentDrawer,
                        onToggleAttachmentDrawer = {
                            if (!multimodalSupported && !showAttachmentDrawer) {
                                viewModel.notifyMultimodalUnavailable()
                            } else {
                                dismissKeyboard()
                                showAttachmentDrawer = !showAttachmentDrawer
                            }
                        },
                        onUpdatePrompt = viewModel::updatePromptInput,
                        onToggleWeb = viewModel::toggleWeb,
                        onToggleThinking = viewModel::toggleThinking,
                        onPickCamera = triggerCamera,
                        onPickPhoto = triggerPhotoPicker,
                        onPickDocument = triggerDocumentPicker,
                        onRemoveAttachment = viewModel::removePendingAttachment,
                        onSetPdfParsePurpose = viewModel::setPdfParsePurpose,
                        onSubmit = {
                            dismissKeyboard()
                            showAttachmentDrawer = false
                            viewModel.submitPrompt()
                        },
                        onStop = viewModel::stopGeneration,
                        modifier = Modifier.widthIn(max = 860.dp)
                    )
                }
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
                        isBusy = uiState.isBusy,
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
                                WelcomeSection(mode = uiState.mode)
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
                                        viewModel.retryMessage(retryMsg)
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
            onClearAllSessions = viewModel::requestClearAllSessions
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

    val deleting = uiState.deletingSession
    if (deleting != null) {
        DeleteSessionConfirmDialog(
            session = deleting,
            onDismiss = viewModel::dismissDeleteSession,
            onConfirm = viewModel::confirmDeleteSession
        )
    }

    if (uiState.showClearAllSessionsConfirm) {
        ClearAllSessionsConfirmDialog(
            sessionCount = uiState.sessions.size,
            onDismiss = viewModel::dismissClearAllSessions,
            onConfirm = viewModel::confirmClearAllSessions
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
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val compactHeader = maxWidth < 360.dp || fontScale > 1.15f

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
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "工作区文件",
                                modifier = Modifier.size(16.dp),
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
                            .size(40.dp)
                            .testTag("top_theme_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (themeMode == AppThemeMode.LIGHT) {
                                Icons.Outlined.LightMode
                            } else {
                                Icons.Outlined.DarkMode
                            },
                            contentDescription = "切换暗色/白色主题",
                            modifier = Modifier.size(19.dp),
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
                            modifier = Modifier.padding(
                                horizontal = if (compactHeader) 9.dp else 10.dp,
                                vertical = 7.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "新会话",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            if (!compactHeader) {
                                Text(
                                    text = "新会话",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
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
    isBusy: Boolean,
    onSelectMode: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeTabItem(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = stringResource(R.string.mode_chat),
                    selected = mode == "chat",
                    enabled = !isBusy,
                    onClick = { onSelectMode("chat") },
                    testTag = "mode_chat_button"
                )
                ModeTabItem(
                    icon = Icons.Outlined.Build,
                    title = stringResource(R.string.mode_work),
                    selected = mode == "work",
                    enabled = !isBusy,
                    onClick = { onSelectMode("work") },
                    testTag = "mode_work_button"
                )
            }
        }
    }
}

@Composable
private fun ModeTabItem(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 9.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = fg
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg
        )
    }
}

/**
 * UI001-inspired clean composer bar:
 * - Unified rounded container card (24dp corners), borderless text input
 * - Bottom-left: Think & Search pill toggles
 * - Bottom-right: Circular (+ / x) attachment toggle & circular Send (↑) / Stop (■) action
 * - NO voice input button
 * - Expandable bottom attachment tray with horizontal thumbnail strip + Add more tile + 3 cards (Camera, Photo, Document)
 */
@Composable
private fun ComposerBottomBar(
    uiState: FluorineUiState,
    multimodalSupported: Boolean,
    showAttachmentDrawer: Boolean,
    onToggleAttachmentDrawer: () -> Unit,
    onUpdatePrompt: (String) -> Unit,
    onToggleWeb: () -> Unit,
    onToggleThinking: () -> Unit,
    onPickCamera: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickDocument: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSetPdfParsePurpose: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = uiState.promptInput.isNotBlank() || uiState.pendingAttachments.isNotEmpty()
    val hasRichDocPending = remember(uiState.pendingAttachments) {
        uiState.pendingAttachments.any { it.kind == "document" }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Unified Composer Card (UI001-inspired, reference layout)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)
            ) {
                BasicTextField(
                    value = uiState.promptInput,
                    onValueChange = onUpdatePrompt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .heightIn(min = 48.dp, max = 160.dp)
                        .testTag("prompt_input"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (uiState.promptInput.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.prompt_placeholder),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                        fontSize = 18.sp,
                                        lineHeight = 25.sp
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val useTwoRows = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.15f
                    val toggles: @Composable () -> Unit = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ComposerPillToggle(
                                icon = null,
                                label = stringResource(R.string.toggle_thinking),
                                active = uiState.useThinking && uiState.thinkingMode != "disabled",
                                onClick = onToggleThinking,
                                testTag = "thinking_toggle_button"
                            )
                            ComposerPillToggle(
                                icon = Icons.Outlined.Language,
                                label = stringResource(R.string.toggle_web),
                                active = uiState.useWeb,
                                onClick = onToggleWeb,
                                testTag = "web_toggle_button"
                            )
                        }
                    }
                    val actions: @Composable () -> Unit = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ComposerCircleButton(
                                icon = if (showAttachmentDrawer) Icons.Outlined.Close else Icons.Outlined.Add,
                                description = if (showAttachmentDrawer) "收起附件面板" else "上传图片或文件",
                                enabled = multimodalSupported || showAttachmentDrawer,
                                filled = false,
                                onClick = onToggleAttachmentDrawer,
                                testTag = "attach_toggle_button"
                            )
                            if (uiState.isBusy) {
                                ComposerCircleButton(
                                    icon = Icons.Outlined.Stop,
                                    description = stringResource(R.string.stop_action),
                                    enabled = true,
                                    filled = true,
                                    danger = true,
                                    onClick = onStop,
                                    testTag = "stop_button"
                                )
                            } else {
                                ComposerCircleButton(
                                    icon = Icons.Outlined.ArrowUpward,
                                    description = stringResource(R.string.send_action),
                                    enabled = canSend,
                                    filled = true,
                                    onClick = onSubmit,
                                    testTag = "send_button"
                                )
                            }
                        }
                    }

                    if (useTwoRows) {
                        Column(Modifier.fillMaxWidth()) {
                            toggles()
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.align(Alignment.End)) { actions() }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            toggles()
                            Spacer(Modifier.weight(1f).width(8.dp))
                            actions()
                        }
                    }
                }
            }
        }

        // Expandable attachment strip & 3-card drawer (UI001 bottom layout)
        AnimatedVisibility(
            visible = showAttachmentDrawer || uiState.pendingAttachments.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showAttachmentDrawer || uiState.pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showAttachmentDrawer) {
                            Surface(
                                modifier = Modifier
                                    .size(82.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(onClick = onPickPhoto)
                                    .testTag("attach_add_more_tile"),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.attach_add_more),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        uiState.pendingAttachments.forEach { att ->
                            PendingAttachmentTile(
                                attachment = att,
                                onRemove = { onRemoveAttachment(att.id) }
                            )
                        }
                    }
                }

                if (showAttachmentDrawer && hasRichDocPending) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "文档解析：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = uiState.pdfParsePurpose == "text_recognition",
                            onClick = { onSetPdfParsePurpose("text_recognition") },
                            label = { Text("纯文字", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = uiState.pdfParsePurpose == "rich_layout_recognition",
                            onClick = { onSetPdfParsePurpose("rich_layout_recognition") },
                            label = { Text("图文混排", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                if (showAttachmentDrawer) {
                    if (!multimodalSupported) {
                        Text(
                            text = "当前模型 (${uiState.model}) 暂不支持多模态上传；切换为 deepseek-flash 后可用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AttachmentSourceCard(
                            icon = Icons.Outlined.PhotoCamera,
                            label = stringResource(R.string.attach_camera),
                            enabledStyle = multimodalSupported,
                            onClick = onPickCamera,
                            testTag = "attach_camera_card",
                            modifier = Modifier.weight(1f)
                        )
                        AttachmentSourceCard(
                            icon = Icons.Outlined.Image,
                            label = stringResource(R.string.attach_photo),
                            enabledStyle = multimodalSupported,
                            onClick = onPickPhoto,
                            testTag = "attach_photo_card",
                            modifier = Modifier.weight(1f)
                        )
                        AttachmentSourceCard(
                            icon = Icons.Outlined.AttachFile,
                            label = stringResource(R.string.attach_document),
                            enabledStyle = multimodalSupported,
                            onClick = onPickDocument,
                            testTag = "attach_document_card",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingAttachmentTile(
    attachment: ChatAttachment,
    onRemove: () -> Unit
) {
    val thumb = if (attachment.kind == "image" && attachment.localPath.isNotBlank()) {
        loadThumbnailBitmap(attachment.localPath, 220)
    } else {
        null
    }

    Box(modifier = Modifier.size(82.dp)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "移除附件",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun AttachmentSourceCard(
    icon: ImageVector,
    label: String,
    enabledStyle: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(82.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabledStyle) 0.78f else 0.38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = if (enabledStyle) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabledStyle) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ComposerPillToggle(
    icon: ImageVector?,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bg = if (active) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
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
    val accent = MaterialTheme.colorScheme.primary

    // 48dp 点击热区，内部可见药丸 36dp
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.heightIn(min = 36.dp),
            shape = RoundedCornerShape(24.dp),
            color = bg,
            border = BorderStroke(1.dp, border)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (icon == null) {
                    ThinkGlyph(tint = fg, accent = accent)
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = fg
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = fg,
                    maxLines = 1
                )
            }
        }
    }
}

/** 圆形操作按钮：描边款（filled=false）或实心款（filled=true，发送/停止）。 */
@Composable
private fun ComposerCircleButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    danger: Boolean = false
) {
    val tint = when {
        danger -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .testTag(testTag)
    ) {
        if (filled) {
            val fill = when {
                danger -> MaterialTheme.colorScheme.error
                enabled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val fgColor = when {
                danger -> MaterialTheme.colorScheme.onError
                enabled -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(fill, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, description, Modifier.size(22.dp), fgColor)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .border(1.6.dp, tint, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, description, Modifier.size(18.dp), tint)
            }
        }
    }
}

/** 原子形状的 Think 图标（三个椭圆轨道 + 原子核），比 Psychology 更贴近参照图。 */
@Composable
private fun ThinkGlyph(tint: Color, accent: Color) {
    Canvas(Modifier.size(18.dp)) {
        val line = Stroke(width = 1.4.dp.toPx())
        for (angle in listOf(0f, 60f, 120f)) {
            rotate(angle) {
                drawOval(
                    color = tint,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.32f),
                    size = Size(size.width * 0.84f, size.height * 0.36f),
                    style = line
                )
            }
        }
        drawCircle(color = tint, radius = size.minDimension * 0.075f)
    }
}
