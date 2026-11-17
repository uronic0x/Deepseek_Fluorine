package com.uronic0x.fluorine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uronic0x.fluorine.protocol.WorkMode
import com.uronic0x.fluorine.ui.FluorineUiState
import com.uronic0x.fluorine.ui.FluorineViewModel
import com.uronic0x.fluorine.ui.components.MessageItemCard
import com.uronic0x.fluorine.ui.components.MinimalEmptyState
import com.uronic0x.fluorine.ui.components.SettingsDialog
import com.uronic0x.fluorine.ui.components.ToolCallDetailDialog
import com.uronic0x.fluorine.ui.components.WorkTaskPanel
import com.uronic0x.fluorine.ui.theme.FluorineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FluorineTheme {
                FluorineWorkbenchApp()
            }
        }
    }
}

@Composable
fun FluorineWorkbenchApp(
    viewModel: FluorineViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            viewModel.writeMarkdownToUri(context, uri)
        }
    }

    val storagePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setAllowAndroidStorage(granted)
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    if (uiState.showSettingsDialog) {
        SettingsDialog(
            uiState = uiState,
            onDismiss = { viewModel.setSettingsDialogVisible(false) },
            onApiKeyChange = viewModel::updateApiKey,
            onModelChange = viewModel::updateModel,
            onEffortChange = viewModel::updateEffort,
            onBudgetChange = viewModel::updateBudget,
            onRequestStorageToggle = { wantEnable ->
                if (!wantEnable) {
                    viewModel.setAllowAndroidStorage(false)
                } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        viewModel.setAllowAndroidStorage(true)
                    } else {
                        storagePermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                } else {
                    viewModel.setAllowAndroidStorage(true)
                }
            },
            onDiscoverClick = viewModel::discoverCapabilities,
            onClearLocalData = viewModel::clearPersistedData
        )
    }

    uiState.selectedToolCallDetail?.let { detail ->
        ToolCallDetailDialog(
            record = detail,
            onDismiss = { viewModel.selectToolCallDetail(null) }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FluorineMinimalTopBar(
                displayName = uiState.displayName,
                mode = uiState.mode,
                hasApiKey = uiState.apiKey.isNotBlank(),
                hasMessages = uiState.messages.isNotEmpty(),
                hasReports = uiState.reportsCount > 0,
                busy = uiState.busy,
                onSelectMode = viewModel::setMode,
                onNewChatClick = viewModel::newChat,
                onExportClick = {
                    val md = viewModel.buildMarkdownContent()
                    if (md != null) {
                        createDocLauncher.launch(viewModel.getExportFileName())
                    }
                },
                onSettingsClick = { viewModel.setSettingsDialogVisible(true) }
            )
        },
        bottomBar = {
            FluorineComposerBar(
                uiState = uiState,
                onPromptChange = viewModel::updatePrompt,
                onWebToggle = viewModel::setWebEnabled,
                onLocalToolsToggle = viewModel::setLocalToolsEnabled,
                onDeviceToolsToggle = viewModel::setDeviceToolsEnabled,
                onSendClick = { viewModel.submitPrompt() },
                onStopClick = viewModel::stopGeneration
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 820.dp)
                    .testTag("main_lazy_column")
            ) {
                if (uiState.messages.isEmpty() && !uiState.showTaskPanel) {
                    item(key = "empty_state") {
                        MinimalEmptyState(mode = uiState.mode)
                    }
                }

                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { msg ->
                    MessageItemCard(
                        message = msg,
                        displayName = uiState.displayName,
                        onToolBadgeClick = viewModel::selectToolCallDetail,
                        onCopyStatus = viewModel::notifyStatus
                    )
                }

                if (uiState.showTaskPanel) {
                    item(key = "work_panel") {
                        WorkTaskPanel(
                            activeStep = uiState.activeWorkStep,
                            taskStatusText = uiState.taskStatusText,
                            planText = uiState.planText,
                            toolsLogText = uiState.toolsLogText,
                            reviewText = uiState.reviewText,
                            planExpanded = uiState.planExpanded,
                            toolsExpanded = uiState.toolsExpanded,
                            reviewExpanded = uiState.reviewExpanded,
                            onTogglePlan = viewModel::togglePlanExpanded,
                            onToggleTools = viewModel::toggleToolsExpanded,
                            onToggleReview = viewModel::toggleReviewExpanded
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FluorineMinimalTopBar(
    displayName: String,
    mode: WorkMode,
    hasApiKey: Boolean,
    hasMessages: Boolean,
    hasReports: Boolean,
    busy: Boolean,
    onSelectMode: (WorkMode) -> Unit,
    onNewChatClick: () -> Unit,
    onExportClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 1080.dp)
                    .height(58.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = displayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .clickable(enabled = !busy) { onNewChatClick() }
                        .padding(vertical = 6.dp)
                        .testTag("brand_header")
                )

                // Segmented Chat / Work switcher
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MinimalModeSegment(
                            label = stringResource(R.string.mode_chat_title),
                            selected = mode == WorkMode.CHAT,
                            enabled = !busy,
                            onClick = { onSelectMode(WorkMode.CHAT) },
                            tag = "chat_mode_btn"
                        )
                        MinimalModeSegment(
                            label = stringResource(R.string.mode_work_title),
                            selected = mode == WorkMode.WORK,
                            enabled = !busy,
                            onClick = { onSelectMode(WorkMode.WORK) },
                            tag = "work_mode_btn"
                        )
                    }
                }

                // Right-side actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hasMessages) {
                        Text(
                            text = stringResource(R.string.new_chat),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !busy) { onNewChatClick() }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("new_chat_btn")
                        )
                    }

                    if (hasReports) {
                        Text(
                            text = stringResource(R.string.action_export_md),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onExportClick() }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("export_md_btn")
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !busy) { onSettingsClick() }
                            .testTag("settings_open_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (hasApiKey) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    )
                            )
                            Text(
                                text = stringResource(R.string.settings_connect),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun MinimalModeSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "mode_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 200),
        label = "mode_fg"
    )

    Surface(
        shape = RoundedCornerShape(50),
        color = bgColor,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled) { onClick() }
            .testTag(tag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FluorineComposerBar(
    uiState: FluorineUiState,
    onPromptChange: (String) -> Unit,
    onWebToggle: (Boolean) -> Unit,
    onLocalToolsToggle: (Boolean) -> Unit,
    onDeviceToolsToggle: (Boolean) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val isWork = uiState.mode == WorkMode.WORK

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 820.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                if (uiState.statusText.isNotBlank()) {
                    Text(
                        text = uiState.statusText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 6.dp, bottom = 6.dp)
                            .testTag("status_bar_text")
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp, vertical = 4.dp)
                        ) {
                            if (uiState.promptInput.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.prompt_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )
                            }
                            BasicTextField(
                                value = uiState.promptInput,
                                onValueChange = onPromptChange,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onBackground
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                minLines = 1,
                                maxLines = 6,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("prompt_input")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ModernColorToggle(
                                    active = uiState.webEnabled,
                                    label = stringResource(R.string.toggle_web),
                                    enabled = !uiState.busy,
                                    onToggle = { onWebToggle(!uiState.webEnabled) },
                                    tag = "web_toggle"
                                )
                                if (isWork) {
                                    ModernColorToggle(
                                        active = uiState.localToolsEnabled,
                                        label = stringResource(R.string.toggle_local_tools),
                                        enabled = !uiState.busy,
                                        onToggle = { onLocalToolsToggle(!uiState.localToolsEnabled) },
                                        tag = "local_tools_toggle"
                                    )
                                    if (uiState.localToolsEnabled) {
                                        ModernColorToggle(
                                            active = uiState.deviceToolsEnabled,
                                            label = stringResource(R.string.toggle_device_tools),
                                            enabled = !uiState.busy,
                                            onToggle = { onDeviceToolsToggle(!uiState.deviceToolsEnabled) },
                                            tag = "device_tools_toggle"
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (uiState.busy) {
                                    Button(
                                        onClick = onStopClick,
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                                        modifier = Modifier
                                            .height(34.dp)
                                            .testTag("stop_btn")
                                    ) {
                                        Text(
                                            text = stringResource(R.string.action_stop),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }

                                Button(
                                    onClick = onSendClick,
                                    enabled = !uiState.busy,
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("send_btn")
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_send),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modern color-transitioning capsule toggle without checkmarks.
 */
@Composable
private fun ModernColorToggle(
    active: Boolean,
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    tag: String
) {
    val containerColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "toggle_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 200),
        label = "toggle_fg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(durationMillis = 200),
        label = "toggle_border"
    )
    val dotColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(durationMillis = 200),
        label = "toggle_dot"
    )

    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled) { onToggle() }
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}
