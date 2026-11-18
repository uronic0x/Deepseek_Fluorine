package com.uronic0x.fluorine.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uronic0x.fluorine.R
import com.uronic0x.fluorine.protocol.ConversationSession
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.WorkspaceFileArtifact
import com.uronic0x.fluorine.ui.FluorineUiState
import com.uronic0x.fluorine.ui.theme.AppThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * RikkaHub-style Navigation Drawer:
 * - Top: Brand header + New Session button + Search filter
 * - Middle: Scrollable list of historical sessions (ConversationSession) with mode badge, rename, and delete
 * - Bottom pinned bar: Settings entry button (left/main), Workspace Files button, and Theme quick toggle
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionDrawerContent(
    sessions: List<ConversationSession>,
    activeSessionId: String,
    themeMode: AppThemeMode,
    workspaceFileCount: Int,
    onSelectSession: (String) -> Unit,
    onNewSession: (String) -> Unit,
    onRenameSessionRequest: (ConversationSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenWorkspaceFiles: () -> Unit,
    onCycleThemeMode: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var modeFilter by rememberSaveable { mutableStateOf("all") } // "all" | "chat" | "work"

    val filteredSessions = remember(sessions, searchQuery, modeFilter) {
        val q = searchQuery.trim()
        sessions.filter { session ->
            val modeMatch = modeFilter == "all" || session.mode == modeFilter
            val queryMatch = q.isEmpty() ||
                session.title.contains(q, ignoreCase = true) ||
                session.chatMessages.any { it.text.contains(q, ignoreCase = true) }
            modeMatch && queryMatch
        }
    }

    ModalDrawerSheet(
        modifier = modifier.width(320.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // Drawer Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${stringResource(R.string.drawer_title)} · ${sessions.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onNewSession("chat") },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 10.dp,
                                vertical = 6.dp
                            ),
                            modifier = Modifier.testTag("drawer_new_chat_button")
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Chat", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { onNewSession("work") },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 10.dp,
                                vertical = 6.dp
                            ),
                            modifier = Modifier.testTag("drawer_new_work_button")
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Work", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("all" to "全部", "chat" to "Chat", "work" to "Work").forEach { (id, label) ->
                        FilterChip(
                            selected = modeFilter == id,
                            onClick = { modeFilter = id },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.drawer_search_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "清除",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("drawer_search_input")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Session List
            if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.drawer_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items = filteredSessions, key = { it.id }) { session ->
                        val isSelected = session.id == activeSessionId
                        SessionListItem(
                            session = session,
                            isSelected = isSelected,
                            onClick = { onSelectSession(session.id) },
                            onRename = { onRenameSessionRequest(session) },
                            onDelete = { onDeleteSession(session.id) }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // RikkaHub-style Bottom Bar: Workspace Files + Settings + Theme Toggle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Workspace files manager row
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenWorkspaceFiles)
                        .testTag("drawer_workspace_files_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.drawer_workspace_files),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "$workspaceFileCount 个文件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Bottom row: Settings button (primary action at bottom of session list) + Theme switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenSettings)
                            .testTag("drawer_open_settings_button"),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.drawer_open_settings),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.drawer_open_settings),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onCycleThemeMode)
                            .testTag("drawer_theme_toggle_button"),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = when (themeMode) {
                                    AppThemeMode.DARK -> Icons.Outlined.DarkMode
                                    AppThemeMode.LIGHT -> Icons.Outlined.LightMode
                                    AppThemeMode.SYSTEM -> Icons.Outlined.AutoAwesome
                                },
                                contentDescription = "切换主题",
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = when (themeMode) {
                                    AppThemeMode.DARK -> "暗色"
                                    AppThemeMode.LIGHT -> "白色"
                                    AppThemeMode.SYSTEM -> "系统"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionListItem(
    session: ConversationSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val timeStr = remember(session.updatedAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(session.updatedAt))
    }
    val msgCount = session.chatMessages.size
    val lastMsg = session.chatMessages.lastOrNull()
    val preview = remember(session.id, msgCount, lastMsg?.id, lastMsg?.text?.length) {
        lastMsg?.text?.take(96)?.replace('\n', ' ')?.trim()?.take(48).orEmpty()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("session_item_${session.id}"),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (session.mode == "work") {
                            Icons.Outlined.Build
                        } else {
                            Icons.Outlined.ChatBubbleOutline
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = session.title.ifBlank { "新对话" },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (session.mode == "work") "Work" else "Chat",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                if (preview.isNotBlank()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "$msgCount 条消息 · $timeStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRename,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.drawer_rename_session),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.drawer_delete_session),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Lightweight, non-rigid Agent status banner for Work mode (Requirement #8:
 * no fixed 4-step workflow; provides tools and autonomous agent capability).
 */
@Composable
fun AgentExecutionPanel(
    statusText: String,
    toolsLogText: String,
    isBusy: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isBusy && toolsLogText.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = toolsLogText.isNotBlank()) { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isBusy) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary
                            )
                    )
                    Text(
                        text = stringResource(R.string.task_panel_title),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (toolsLogText.isNotBlank()) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded && toolsLogText.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = toolsLogText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 17.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
fun RenameSessionDialog(
    session: ConversationSession,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var titleInput by rememberSaveable(session.id) { mutableStateOf(session.title) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.drawer_rename_session),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (titleInput.isNotBlank()) {
                                onConfirm(titleInput.trim())
                            }
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.settings_save))
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteSessionConfirmDialog(
    session: ConversationSession,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "删除会话确认",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "确定要删除会话「${session.title.ifBlank { "新对话" }}」（共 ${session.chatMessages.size} 条消息）吗？此操作不可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("cancel_delete_session_button")
                    ) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.testTag("confirm_delete_session_button")
                    ) {
                        Text("确认删除")
                    }
                }
            }
        }
    }
}

@Composable
fun ClearAllSessionsConfirmDialog(
    sessionCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "清空全部会话确认",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "确定要永久清空全部 $sessionCount 个会话记录吗？建议先导出 JSON 或 Markdown 备份，此操作不可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("cancel_clear_all_sessions_button")
                    ) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.testTag("confirm_clear_all_sessions_button")
                    ) {
                        Text("确认清空")
                    }
                }
            }
        }
    }
}

/**
 * Workspace File Manager Dialog (Requirement #1):
 * Allows non-root users to browse, preview, export (via SAF), share, or delete
 * any file inside `context.filesDir/workspace`.
 */
@Composable
fun WorkspaceFilesDialog(
    files: List<WorkspaceFileArtifact>,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onPreviewFile: (WorkspaceFileArtifact) -> Unit,
    onExportFile: (WorkspaceFileArtifact) -> Unit,
    onShareFile: (WorkspaceFileArtifact) -> Unit,
    onDeleteFile: (WorkspaceFileArtifact) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.workspace_files_dialog_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "支持将 fs_write / fs_edit 生成的工作区文件一键导出到手机任意目录或分享",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "刷新"
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "关闭"
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (files.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_files_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = files, key = { it.relativePath }) { artifact ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Description,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Column {
                                            Text(
                                                text = artifact.relativePath,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontFamily = FontFamily.Monospace
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = CoreProtocol.formatFileSize(artifact.bytes),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { onPreviewFile(artifact) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Visibility,
                                                contentDescription = "预览",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onShareFile(artifact) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Share,
                                                contentDescription = "分享",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Button(
                                            onClick = { onExportFile(artifact) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                                horizontal = 10.dp,
                                                vertical = 6.dp
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "导出",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteFile(artifact) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.DeleteOutline,
                                                contentDescription = "删除",
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.error
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
    }
}

@Composable
fun WorkspaceFilePreviewDialog(
    artifact: WorkspaceFileArtifact,
    content: String,
    onDismiss: () -> Unit,
    onExport: (WorkspaceFileArtifact) -> Unit,
    onShare: (WorkspaceFileArtifact) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artifact.fileName,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
                        )
                        Text(
                            text = "${artifact.relativePath} · ${CoreProtocol.formatFileSize(artifact.bytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onShare(artifact) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("分享", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { onExport(artifact) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导出文件", style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭")
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Surface(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Comprehensive Refactored Settings Dialog (Requirements #6, #7, #11, #13):
 * Organized into 6 rich tabs:
 * 0. 外观与显示 (Theme Dark/White/System, Markdown, LaTeX, Default collapse states)
 * 1. 连接与网络 (API Key, Custom Base URL, Timeouts, Discover capabilities)
 * 2. 模型与推理 (Preset & Custom Model ID, Thinking Mode, Reasoning Effort, Preset & Custom Max Tokens, Temperature, Unlock Web)
 * 3. Agent与提示词 (Max Tool Rounds, Max History Turns, latest_reminder, Auto-title, Custom Chat/Work System Prompts)
 * 4. 工作区与终端 (/sdcard read-only mount, Shell timeout, Open Workspace File Manager)
 * 5. 日志与数据导出 (Export Chat History JSON/Markdown, Import Chat History JSON, View/Export App Logs, Clear Session/All)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    uiState: FluorineUiState,
    onDismiss: () -> Unit,
    onSaveSettings: (FluorineSettingsDraft) -> Unit,
    onDiscoverCapabilities: (String, String) -> Unit,
    onOpenWorkspaceFiles: () -> Unit,
    onExportHistoryJson: () -> Unit,
    onExportHistoryMarkdown: () -> Unit,
    onImportHistoryJson: () -> Unit,
    onExportAppLogs: () -> Unit,
    onClearAppLogs: () -> Unit,
    onClearAllSessions: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var draft by remember(uiState.settingsOpenToken) {
        mutableStateOf(FluorineSettingsDraft.fromUiState(uiState))
    }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var showLogPreview by rememberSaveable { mutableStateOf(false) }

    val tabTitles = listOf(
        stringResource(R.string.settings_tab_appearance),
        stringResource(R.string.settings_tab_connection),
        stringResource(R.string.settings_tab_model),
        stringResource(R.string.settings_tab_agent),
        stringResource(R.string.settings_tab_workspace),
        stringResource(R.string.settings_tab_data)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 24.dp, bottom = 12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = uiState.engineLabel.ifBlank { "Fluorine" },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                // Tabs
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        )
                    }
                }

                // Tab Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (selectedTab) {
                        0 -> AppearanceSettingsTab(
                            draft = draft,
                            onDraftChange = { draft = it }
                        )
                        1 -> ConnectionSettingsTab(
                            draft = draft,
                            showApiKey = showApiKey,
                            onToggleShowApiKey = { showApiKey = !showApiKey },
                            isDiscovering = uiState.isDiscovering,
                            discoverySummary = uiState.discoverySummary,
                            onDiscover = { onDiscoverCapabilities(draft.apiKey, draft.baseUrl) },
                            onDraftChange = { draft = it }
                        )
                        2 -> ModelInferenceSettingsTab(
                            draft = draft,
                            onDraftChange = { draft = it }
                        )
                        3 -> AgentPromptSettingsTab(
                            draft = draft,
                            onDraftChange = { draft = it }
                        )
                        4 -> WorkspaceSettingsTab(
                            draft = draft,
                            workspacePath = uiState.workspacePath,
                            workspaceFileCount = uiState.workspaceFiles.size,
                            onOpenWorkspaceFiles = onOpenWorkspaceFiles,
                            onDraftChange = { draft = it }
                        )
                        5 -> DataAndLogsSettingsTab(
                            sessionCount = uiState.sessions.size,
                            showLogPreview = showLogPreview,
                            onToggleLogPreview = { showLogPreview = !showLogPreview },
                            onExportHistoryJson = onExportHistoryJson,
                            onExportHistoryMarkdown = onExportHistoryMarkdown,
                            onImportHistoryJson = onImportHistoryJson,
                            onExportAppLogs = onExportAppLogs,
                            onClearAppLogs = onClearAppLogs,
                            onClearAllSessions = onClearAllSessions
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Footer Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (draft.apiKey.isNotBlank()) "API Key 已配置" else "未填写 API Key",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (draft.apiKey.isNotBlank()) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.settings_cancel))
                        }
                        Button(
                            onClick = {
                                onSaveSettings(draft)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("save_settings_button")
                        ) {
                            Text(stringResource(R.string.settings_save))
                        }
                    }
                }
            }
        }
    }
}

data class FluorineSettingsDraft(
    val themeMode: AppThemeMode,
    val enableMarkdown: Boolean,
    val enableLatex: Boolean,
    val defaultExpandThinking: Boolean,
    val defaultExpandTools: Boolean,
    val defaultExpandSources: Boolean,
    val apiKey: String,
    val rememberKey: Boolean,
    val baseUrl: String,
    val connectTimeoutSec: Int,
    val readTimeoutSec: Int,
    val model: String,
    val customModel: String,
    val thinkingMode: String,
    val effort: String,
    val maxTokens: Int,
    val customMaxTokens: String,
    val temperature: Float,
    val enableTemperature: Boolean,
    val unlockWebSearch: Boolean,
    val maxToolRounds: Int,
    val maxHistoryTurns: Int,
    val enableLatestReminder: Boolean,
    val autoGenerateTitle: Boolean,
    val customChatSystemPrompt: String,
    val customWorkSystemPrompt: String,
    val workToolsConfig: com.uronic0x.fluorine.protocol.WorkToolsConfig,
    val allowSdcardReadonly: Boolean,
    val shellTimeoutSec: Int
) {
    companion object {
        fun fromUiState(s: FluorineUiState): FluorineSettingsDraft {
            val isPresetModel = CoreProtocol.MODEL_OPTIONS.any { it.id == s.model }
            val isPresetTokens = CoreProtocol.MAX_TOKEN_OPTIONS.any { it.value == s.maxTokens }
            return FluorineSettingsDraft(
                themeMode = s.themeMode,
                enableMarkdown = s.enableMarkdown,
                enableLatex = s.enableLatex,
                defaultExpandThinking = s.defaultExpandThinking,
                defaultExpandTools = s.defaultExpandTools,
                defaultExpandSources = s.defaultExpandSources,
                apiKey = s.apiKey,
                rememberKey = s.rememberKey,
                baseUrl = s.baseUrl,
                connectTimeoutSec = s.connectTimeoutSec,
                readTimeoutSec = s.readTimeoutSec,
                model = if (isPresetModel) s.model else CoreProtocol.MODEL_OPTIONS.first().id,
                customModel = if (isPresetModel) s.customModel else s.model,
                thinkingMode = s.thinkingMode,
                effort = s.effort,
                maxTokens = s.maxTokens,
                customMaxTokens = if (isPresetTokens) "" else s.maxTokens.toString(),
                temperature = s.temperature,
                enableTemperature = s.enableTemperature,
                unlockWebSearch = s.unlockWebSearch,
                maxToolRounds = s.maxToolRounds,
                maxHistoryTurns = s.maxHistoryTurns,
                enableLatestReminder = s.enableLatestReminder,
                autoGenerateTitle = s.autoGenerateTitle,
                customChatSystemPrompt = s.customChatSystemPrompt.ifBlank { CoreProtocol.DEFAULT_CHAT_SYSTEM_PROMPT },
                customWorkSystemPrompt = s.customWorkSystemPrompt.ifBlank { CoreProtocol.DEFAULT_WORK_SYSTEM_PROMPT },
                workToolsConfig = s.workToolsConfig,
                allowSdcardReadonly = s.allowSdcardReadonly,
                shellTimeoutSec = s.shellTimeoutSec
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettingsTab(
    draft: FluorineSettingsDraft,
    onDraftChange: (FluorineSettingsDraft) -> Unit
) {
    SettingsSectionCard(title = stringResource(R.string.settings_theme_label)) {
        Text(
            text = "去除旧版浅绿底色，采用纯净碳黑暗色与纯白亮色主题，支持随时切换：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                AppThemeMode.DARK to stringResource(R.string.theme_dark),
                AppThemeMode.LIGHT to stringResource(R.string.theme_light),
                AppThemeMode.SYSTEM to stringResource(R.string.theme_system)
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = draft.themeMode == mode,
                    onClick = { onDraftChange(draft.copy(themeMode = mode)) },
                    label = { Text(label) }
                )
            }
        }
    }

    SettingsSectionCard(title = "内容渲染与折叠默认状态") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_render_markdown),
            subtitle = "支持标题、代码块一键复制、表格、引用块、任务列表与行内高亮",
            checked = draft.enableMarkdown,
            onCheckedChange = { onDraftChange(draft.copy(enableMarkdown = it)) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = stringResource(R.string.settings_render_latex),
            subtitle = "支持 $$...$$、\\[...\\] 块级公式与 $...$、\\(...\\) 行内数学公式分式/根号/矩阵/上下标原生排版",
            checked = draft.enableLatex,
            onCheckedChange = { onDraftChange(draft.copy(enableLatex = it)) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = stringResource(R.string.settings_default_expand_thinking),
            subtitle = "关闭时推理过程默认折叠，点击可随时展开",
            checked = draft.defaultExpandThinking,
            onCheckedChange = { onDraftChange(draft.copy(defaultExpandThinking = it)) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = stringResource(R.string.settings_default_expand_tools),
            subtitle = "关闭时本地工具调用详情默认折叠",
            checked = draft.defaultExpandTools,
            onCheckedChange = { onDraftChange(draft.copy(defaultExpandTools = it)) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "默认展开联网搜索引用链接",
            subtitle = "默认关闭（即搜索链接默认折叠，点击可展开查看详情与跳转）",
            checked = draft.defaultExpandSources,
            onCheckedChange = { onDraftChange(draft.copy(defaultExpandSources = it)) }
        )
    }
}

@Composable
private fun ConnectionSettingsTab(
    draft: FluorineSettingsDraft,
    showApiKey: Boolean,
    onToggleShowApiKey: () -> Unit,
    isDiscovering: Boolean,
    discoverySummary: String,
    onDiscover: () -> Unit,
    onDraftChange: (FluorineSettingsDraft) -> Unit
) {
    SettingsSectionCard(title = "API 密钥与自定义端点") {
        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { onDraftChange(draft.copy(apiKey = it.trim())) },
            label = { Text(stringResource(R.string.settings_key_label)) },
            placeholder = { Text(stringResource(R.string.settings_key_hint)) },
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleShowApiKey) {
                    Icon(
                        imageVector = if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = "显示/隐藏密钥"
                    )
                }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("api_key_input")
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_remember_key),
            subtitle = "关闭后仅在当前运行内存中保留 API Key",
            checked = draft.rememberKey,
            onCheckedChange = { onDraftChange(draft.copy(rememberKey = it)) }
        )

        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { onDraftChange(draft.copy(baseUrl = it.trim())) },
            label = { Text(stringResource(R.string.settings_base_url_label)) },
            placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    onDraftChange(draft.copy(baseUrl = CoreProtocol.DEFAULT_API_BASE_URL))
                }
            ) {
                Text("恢复官方默认端点")
            }

            OutlinedButton(
                onClick = onDiscover,
                enabled = !isDiscovering && draft.apiKey.isNotBlank()
            ) {
                Text(
                    if (isDiscovering) stringResource(R.string.settings_discovering)
                    else stringResource(R.string.settings_discover_btn)
                )
            }
        }

        if (discoverySummary.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Text(
                    text = discoverySummary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }

    SettingsSectionCard(title = "网络超时配置") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "连接超时：${draft.connectTimeoutSec} 秒",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = draft.connectTimeoutSec.toFloat(),
                onValueChange = { onDraftChange(draft.copy(connectTimeoutSec = it.roundToInt())) },
                valueRange = 5f..60f
            )
            Text(
                text = "流式/推理读取超时：${draft.readTimeoutSec} 秒",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = draft.readTimeoutSec.toFloat(),
                onValueChange = { onDraftChange(draft.copy(readTimeoutSec = it.roundToInt())) },
                valueRange = 30f..600f
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelInferenceSettingsTab(
    draft: FluorineSettingsDraft,
    onDraftChange: (FluorineSettingsDraft) -> Unit
) {
    SettingsSectionCard(title = stringResource(R.string.settings_model_label)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoreProtocol.MODEL_OPTIONS.forEach { option ->
                FilterChip(
                    selected = draft.customModel.isBlank() && draft.model == option.id,
                    onClick = {
                        onDraftChange(draft.copy(model = option.id, customModel = ""))
                    },
                    label = { Text(option.label) }
                )
            }
        }
        OutlinedTextField(
            value = draft.customModel,
            onValueChange = { onDraftChange(draft.copy(customModel = it.trim())) },
            label = { Text(stringResource(R.string.settings_custom_model_label)) },
            placeholder = { Text(stringResource(R.string.settings_custom_model_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }

    SettingsSectionCard(title = "深度思考与推理强度") {
        Text(
            text = stringResource(R.string.settings_thinking_mode_label),
            style = MaterialTheme.typography.labelLarge
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoreProtocol.THINKING_MODE_OPTIONS.forEach { (id, label) ->
                FilterChip(
                    selected = draft.thinkingMode == id,
                    onClick = { onDraftChange(draft.copy(thinkingMode = id)) },
                    label = { Text(label) }
                )
            }
        }

        Text(
            text = stringResource(R.string.settings_effort_label),
            style = MaterialTheme.typography.labelLarge
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoreProtocol.EFFORT_OPTIONS.forEach { option ->
                FilterChip(
                    selected = draft.effort == option.id,
                    onClick = { onDraftChange(draft.copy(effort = option.id)) },
                    label = { Text(option.label) }
                )
            }
        }
    }

    SettingsSectionCard(title = "输出 Token 预算与采样自由度") {
        Text(
            text = stringResource(R.string.settings_max_tokens_label),
            style = MaterialTheme.typography.labelLarge
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoreProtocol.MAX_TOKEN_OPTIONS.forEach { option ->
                FilterChip(
                    selected = draft.customMaxTokens.isBlank() && draft.maxTokens == option.value,
                    onClick = {
                        onDraftChange(draft.copy(maxTokens = option.value, customMaxTokens = ""))
                    },
                    label = { Text(option.label) }
                )
            }
        }

        OutlinedTextField(
            value = draft.customMaxTokens,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }
                val parsed = digits.toIntOrNull()?.coerceIn(256, 393216) ?: draft.maxTokens
                onDraftChange(draft.copy(customMaxTokens = digits, maxTokens = parsed))
            },
            label = { Text(stringResource(R.string.settings_custom_max_tokens_label)) },
            placeholder = { Text("例如 24576") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSwitchRow(
            title = "启用自定义采样温度 (Temperature)",
            subtitle = "官方规范提示：开启 thinking 模式时建议保持默认不传 temperature；仅在非思考模式或兼容接口下按需开启",
            checked = draft.enableTemperature,
            onCheckedChange = { onDraftChange(draft.copy(enableTemperature = it)) }
        )
        if (draft.enableTemperature) {
            Text(
                text = "Temperature: ${String.format(Locale.US, "%.2f", draft.temperature)}",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = draft.temperature,
                onValueChange = { onDraftChange(draft.copy(temperature = it)) },
                valueRange = 0.0f..2.0f
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSwitchRow(
            title = stringResource(R.string.settings_unlock_web_label),
            subtitle = "解除仅 deepseek-flash 可开启内置联网搜索的限制，允许 deepseek-v4-pro 或自定义模型尝试下发 web_search 工具",
            checked = draft.unlockWebSearch,
            onCheckedChange = { onDraftChange(draft.copy(unlockWebSearch = it)) }
        )
    }
}

@Composable
private fun AgentLimitField(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    onValueChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in minimum..maximum } == true
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.toIntOrNull()?.takeIf { it in minimum..maximum }?.let(onValueChange)
        },
        label = { Text(label) },
        supportingText = { Text("范围 $minimum–$maximum；当前生效值 $value") },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AgentPromptSettingsTab(
    draft: FluorineSettingsDraft,
    onDraftChange: (FluorineSettingsDraft) -> Unit
) {
    SettingsSectionCard(title = "Work 自主 Agent 调度与上下文缓存") {
        AgentLimitField(
            label = stringResource(R.string.settings_max_tool_rounds_label),
            value = draft.maxToolRounds,
            minimum = 1,
            maximum = CoreProtocol.MAX_TOOL_ROUNDS,
            onValueChange = { onDraftChange(draft.copy(maxToolRounds = it)) }
        )
        AgentLimitField(
            label = stringResource(R.string.settings_max_history_label),
            value = draft.maxHistoryTurns,
            minimum = 4,
            maximum = CoreProtocol.MAX_HISTORY_MESSAGES,
            onValueChange = { onDraftChange(draft.copy(maxHistoryTurns = it)) }
        )
        Text(
            text = "工具轮次用尽后仍允许一次收尾回复。上下文按消息条数保留，并保持完整的用户/助手轮次；调大后将在后续请求生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSwitchRow(
            title = stringResource(R.string.settings_latest_reminder_label),
            subtitle = "遵循 DeepSeek 2026-11 规范注入 latest_reminder 元信息（不影响历史前缀缓存命中）",
            checked = draft.enableLatestReminder,
            onCheckedChange = { onDraftChange(draft.copy(enableLatestReminder = it)) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_title_label),
            subtitle = "根据首条用户提问自动生成历史会话标题",
            checked = draft.autoGenerateTitle,
            onCheckedChange = { onDraftChange(draft.copy(autoGenerateTitle = it)) }
        )
    }

    SettingsSectionCard(title = "可编辑 System Prompt（无锁定隐藏词）") {
        Text(
            text = "为保证 DeepSeek Prefix Cache 高命中率，System Prompt 会固定置于 messages[0]；下方展示的就是实际发送的全部系统提示词，可自由修改或恢复默认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = draft.customChatSystemPrompt,
            onValueChange = { onDraftChange(draft.copy(customChatSystemPrompt = it)) },
            label = { Text(stringResource(R.string.settings_chat_prompt_label)) },
            minLines = 4,
            maxLines = 8,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_system_prompt_input")
        )

        OutlinedTextField(
            value = draft.customWorkSystemPrompt,
            onValueChange = { onDraftChange(draft.copy(customWorkSystemPrompt = it)) },
            label = { Text(stringResource(R.string.settings_work_prompt_label)) },
            minLines = 5,
            maxLines = 10,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("work_system_prompt_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    onDraftChange(
                        draft.copy(
                            customChatSystemPrompt = CoreProtocol.DEFAULT_CHAT_SYSTEM_PROMPT,
                            customWorkSystemPrompt = CoreProtocol.DEFAULT_WORK_SYSTEM_PROMPT
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.settings_reset_prompts))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceSettingsTab(
    draft: FluorineSettingsDraft,
    workspacePath: String,
    workspaceFileCount: Int,
    onOpenWorkspaceFiles: () -> Unit,
    onDraftChange: (FluorineSettingsDraft) -> Unit
) {
    val cfg = draft.workToolsConfig
    var showFineGrainedTools by rememberSaveable { mutableStateOf(false) }

    SettingsSectionCard(title = "Work 模式工具注入与设备控制") {
        Text(
            text = "按需自定义注入给 Work Agent 的本地工具组；声明与执行端会严格保持一致。可随时在设备端开启任意 Shell 与设备控制工具。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingsSwitchRow(
            title = "确定性计算工具 (9 个)",
            subtitle = "calc, stats, unit_convert, date_calc, text_metrics, json_query, csv_summary, text_diff, encode_hash",
            checked = cfg.deterministic,
            onCheckedChange = { onDraftChange(draft.copy(workToolsConfig = cfg.copy(deterministic = it))) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "工作区只读工具 (fs_list / fs_read / fs_search)",
            subtitle = "浏览、读取和搜索工作区文件（及授权后的只读 /sdcard）",
            checked = cfg.workspaceRead,
            onCheckedChange = { onDraftChange(draft.copy(workToolsConfig = cfg.copy(workspaceRead = it))) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "工作区写入工具 (fs_write / fs_edit)",
            subtitle = "在工作区沙盒内创建、追加和局部修改文件",
            checked = cfg.workspaceWrite,
            onCheckedChange = { onDraftChange(draft.copy(workToolsConfig = cfg.copy(workspaceWrite = it))) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "Git 仓库检查 (git_cmd)",
            subtitle = "执行 status, diff, log, show, branch, rev-parse",
            checked = cfg.git,
            onCheckedChange = { onDraftChange(draft.copy(workToolsConfig = cfg.copy(git = it))) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "任意 Shell 命令执行 (shell_exec)",
            subtitle = "开启后允许 Work Agent 在工作区内执行 sh -c 命令（带超时与危险命令防误触拦截）",
            checked = cfg.shellExec,
            onCheckedChange = { onDraftChange(draft.copy(workToolsConfig = cfg.copy(shellExec = it))) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "设备控制与系统诊断 (termux_api / self_adb)",
            subtitle = "开启后允许调用 termux-* 扩展或本地 Android dumpsys/pm/wm/am/input 诊断指令",
            checked = cfg.deviceControl,
            onCheckedChange = { onDraftChange(draft.copy(workToolsConfig = cfg.copy(deviceControl = it))) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "当前生效工具数：${cfg.enabledToolNames().size} / 18",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        onDraftChange(
                            draft.copy(
                                workToolsConfig = com.uronic0x.fluorine.protocol.WorkToolsConfig(
                                    deterministic = true,
                                    workspaceRead = true,
                                    workspaceWrite = true,
                                    git = true,
                                    shellExec = true,
                                    deviceControl = true,
                                    disabledTools = emptySet()
                                )
                            )
                        )
                    }
                ) {
                    Text("全部开启")
                }
                TextButton(
                    onClick = { showFineGrainedTools = !showFineGrainedTools }
                ) {
                    Text(if (showFineGrainedTools) "收起单工具列表" else "逐个工具定制")
                }
            }
        }

        AnimatedVisibility(visible = showFineGrainedTools) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                com.uronic0x.fluorine.protocol.WorkToolsConfig.ALL_DESCRIPTORS.forEach { desc ->
                    val enabled = cfg.isToolEnabled(desc.name)
                    FilterChip(
                        selected = enabled,
                        onClick = {
                            val newDisabled = cfg.disabledTools.toMutableSet()
                            val catEnabled = when (desc.category) {
                                "deterministic" -> cfg.deterministic
                                "workspace_read" -> cfg.workspaceRead
                                "workspace_write" -> cfg.workspaceWrite
                                "git" -> cfg.git
                                "shell_exec" -> cfg.shellExec
                                "device_control" -> cfg.deviceControl
                                else -> true
                            }
                            if (enabled) {
                                newDisabled.add(desc.name)
                                onDraftChange(draft.copy(workToolsConfig = cfg.copy(disabledTools = newDisabled)))
                            } else {
                                newDisabled.remove(desc.name)
                                val nextCfg = when (desc.category) {
                                    "deterministic" -> cfg.copy(deterministic = true, disabledTools = newDisabled)
                                    "workspace_read" -> cfg.copy(workspaceRead = true, disabledTools = newDisabled)
                                    "workspace_write" -> cfg.copy(workspaceWrite = true, disabledTools = newDisabled)
                                    "git" -> cfg.copy(git = true, disabledTools = newDisabled)
                                    "shell_exec" -> cfg.copy(shellExec = true, disabledTools = newDisabled)
                                    "device_control" -> cfg.copy(deviceControl = true, disabledTools = newDisabled)
                                    else -> if (catEnabled) cfg.copy(disabledTools = newDisabled) else cfg.copy(disabledTools = newDisabled)
                                }
                                onDraftChange(draft.copy(workToolsConfig = nextCfg))
                            }
                        },
                        label = { Text(desc.name, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }

    SettingsSectionCard(title = "工作区目录与生成产物导出") {
        Text(
            text = "当前工作区沙盒路径：\n$workspacePath",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "当前工作区共有 $workspaceFileCount 个文件",
                style = MaterialTheme.typography.labelLarge
            )
            Button(
                onClick = onOpenWorkspaceFiles,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("浏览并导出工作区文件")
            }
        }
    }

    SettingsSectionCard(title = "外部目录访问与命令超时") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_sdcard_label),
            subtitle = "开启后 fs_read / fs_list / fs_search 可按绝对路径只读访问 /sdcard，但写入操作仍严格限制在应用沙盒工作区内",
            checked = draft.allowSdcardReadonly,
            onCheckedChange = { onDraftChange(draft.copy(allowSdcardReadonly = it)) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
            text = "${stringResource(R.string.settings_shell_timeout_label)}：${draft.shellTimeoutSec} 秒",
            style = MaterialTheme.typography.labelLarge
        )
        Slider(
            value = draft.shellTimeoutSec.toFloat(),
            onValueChange = { onDraftChange(draft.copy(shellTimeoutSec = it.roundToInt())) },
            valueRange = 5f..120f
        )
    }
}

@Composable
private fun DataAndLogsSettingsTab(
    sessionCount: Int,
    showLogPreview: Boolean,
    onToggleLogPreview: () -> Unit,
    onExportHistoryJson: () -> Unit,
    onExportHistoryMarkdown: () -> Unit,
    onImportHistoryJson: () -> Unit,
    onExportAppLogs: () -> Unit,
    onClearAppLogs: () -> Unit,
    onClearAllSessions: () -> Unit
) {
    SettingsSectionCard(title = "聊天历史导入与导出（共 $sessionCount 个会话）") {
        Text(
            text = "支持将全部历史会话、工具调用记录与 Token 统计导出为标准 JSON 备份或 Markdown 文档：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onExportHistoryJson,
                modifier = Modifier
                    .weight(1f)
                    .testTag("export_history_json_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.settings_export_history_json))
            }

            OutlinedButton(
                onClick = onExportHistoryMarkdown,
                modifier = Modifier
                    .weight(1f)
                    .testTag("export_history_md_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.settings_export_history_md))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onImportHistoryJson,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.settings_import_history_json))
            }

            TextButton(
                onClick = onClearAllSessions,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("清空全部会话")
            }
        }
    }

    SettingsSectionCard(title = "应用运行诊断日志 (App Logs)") {
        Text(
            text = "记录 API 请求状态、Prefix Cache 缓存命中率、工具链调用耗时与异常堆栈（自动脱敏 API Key）：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onExportAppLogs,
                modifier = Modifier
                    .weight(1f)
                    .testTag("export_app_logs_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.settings_export_logs))
            }

            OutlinedButton(
                onClick = onToggleLogPreview,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (showLogPreview) "隐藏日志预览" else stringResource(R.string.settings_view_logs))
            }

            TextButton(onClick = onClearAppLogs) {
                Text("清空")
            }
        }

        if (showLogPreview) {
            val logText = remember(showLogPreview) { CoreProtocol.AppLogger.dumpText() }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = logText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
