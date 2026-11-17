package com.uronic0x.fluorine.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uronic0x.fluorine.R
import com.uronic0x.fluorine.protocol.BUDGET_OPTIONS
import com.uronic0x.fluorine.protocol.EFFORT_OPTIONS
import com.uronic0x.fluorine.protocol.MODEL_OPTIONS
import com.uronic0x.fluorine.protocol.ToolCallRecord
import com.uronic0x.fluorine.ui.FluorineUiState
import com.uronic0x.fluorine.ui.theme.JetBrainsMono

@Composable
fun WorkTaskPanel(
    activeStep: Int,
    taskStatusText: String,
    planText: String,
    toolsLogText: String,
    reviewText: String,
    planExpanded: Boolean,
    toolsExpanded: Boolean,
    reviewExpanded: Boolean,
    onTogglePlan: () -> Unit,
    onToggleTools: () -> Unit,
    onToggleReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = listOf(
        stringResource(R.string.step_1),
        stringResource(R.string.step_2),
        stringResource(R.string.step_3),
        stringResource(R.string.step_4)
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("work_task_panel")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    steps.forEachIndexed { index, label ->
                        val isActive = activeStep == index
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Text(
                    text = taskStatusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            CollapsibleWorkBox(
                title = stringResource(R.string.section_plan),
                content = planText,
                expanded = planExpanded,
                onToggle = onTogglePlan,
                tag = "work_plan_box"
            )

            CollapsibleWorkBox(
                title = stringResource(R.string.section_tools),
                content = toolsLogText,
                expanded = toolsExpanded,
                onToggle = onToggleTools,
                tag = "work_tools_box"
            )

            CollapsibleWorkBox(
                title = stringResource(R.string.section_review),
                content = reviewText,
                expanded = reviewExpanded,
                onToggle = onToggleReview,
                tag = "work_review_box"
            )
        }
    }
}

@Composable
private fun CollapsibleWorkBox(
    title: String,
    content: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    tag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .testTag(tag)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Text(
                    text = content.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMono,
                        fontSize = 11.5.sp,
                        lineHeight = 19.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsDialog(
    uiState: FluorineUiState,
    onDismiss: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEffortChange: (String) -> Unit,
    onBudgetChange: (Int) -> Unit,
    onRequestStorageToggle: (Boolean) -> Unit,
    onDiscoverClick: () -> Unit,
    onClearLocalData: () -> Unit
) {
    var keyVisible by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp)
                .testTag("settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("settings_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.settings_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.settings_api_key),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = onApiKeyChange,
                    placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_key_visibility)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.settings_model),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(6.dp))
                DropdownSelectorField(
                    selectedLabel = MODEL_OPTIONS.find { it.id == uiState.model }?.label ?: uiState.model,
                    options = MODEL_OPTIONS.map { it.id to it.label },
                    onSelect = onModelChange,
                    tag = "model_selector"
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.settings_effort),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(6.dp))
                DropdownSelectorField(
                    selectedLabel = EFFORT_OPTIONS.find { it.id == uiState.effort }?.label ?: uiState.effort,
                    options = EFFORT_OPTIONS.map { it.id to it.label },
                    onSelect = onEffortChange,
                    tag = "effort_selector"
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.settings_budget),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(6.dp))
                DropdownSelectorField(
                    selectedLabel = BUDGET_OPTIONS.find { it.tokens == uiState.budget }?.label
                        ?: "${uiState.budget} tokens",
                    options = BUDGET_OPTIONS.map { it.tokens.toString() to it.label },
                    onSelect = { str -> str.toIntOrNull()?.let(onBudgetChange) },
                    tag = "budget_selector"
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_storage_toggle),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_storage_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = uiState.allowAndroidStorage,
                            onCheckedChange = onRequestStorageToggle,
                            modifier = Modifier.testTag("storage_permission_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onDiscoverClick,
                            enabled = !uiState.discoverBusy,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("discover_btn")
                        ) {
                            Text(
                                text = if (uiState.discoverBusy) "检测中…" else stringResource(R.string.settings_discover)
                            )
                        }
                        TextButton(
                            onClick = onClearLocalData,
                            modifier = Modifier.testTag("clear_data_btn")
                        ) {
                            Text(
                                text = stringResource(R.string.settings_clear_local),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("settings_done_btn")
                    ) {
                        Text(stringResource(R.string.settings_done))
                    }
                }

                if (uiState.modelInfoText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = uiState.modelInfoText,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = JetBrainsMono,
                                    fontSize = 11.sp,
                                    lineHeight = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_fine),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DropdownSelectorField(
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    tag: String
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .testTag(tag)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    }
                )
            }
        }
    }
}

@Composable
fun ToolCallDetailDialog(
    record: ToolCallRecord,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tool_detail_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stringResource(R.string.tool_detail_title)} · ${record.name}",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.tool_detail_args),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = record.arguments,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.tool_detail_result),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = record.result,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = JetBrainsMono,
                                fontSize = 12.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            copyToClipboard(
                                context,
                                "${record.name}(${record.arguments})\n${record.result}"
                            )
                        }
                    ) {
                        Text(stringResource(R.string.action_copy))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_done))
                    }
                }
            }
        }
    }
}
