package com.uronic0x.fluorine.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uronic0x.fluorine.R
import com.uronic0x.fluorine.protocol.CitationPoint
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.ToolCallRecord
import com.uronic0x.fluorine.protocol.UiChatMessage
import com.uronic0x.fluorine.protocol.WorkMode
import com.uronic0x.fluorine.ui.theme.JetBrainsMono
import kotlinx.coroutines.delay

@Composable
fun MinimalEmptyState(
    mode: WorkMode,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 96.dp)
            .testTag("minimal_empty_state"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (mode == WorkMode.WORK) {
                stringResource(R.string.empty_state_work)
            } else {
                stringResource(R.string.empty_state_chat)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageItemCard(
    message: UiChatMessage,
    displayName: String,
    onToolBadgeClick: (ToolCallRecord) -> Unit,
    onCopyStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    val isError = message.role == "error"
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    var thinkingExpanded by remember(message.id) { mutableStateOf(false) }
    var copiedRecently by remember(message.id) { mutableStateOf(false) }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(1500)
            copiedRecently = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isUser) {
                    val accentBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    Modifier
                        .padding(top = 14.dp)
                        .drawBehind {
                            drawLine(
                                color = accentBorder,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 2.5.dp.toPx()
                            )
                        }
                        .padding(start = 16.dp, top = 6.dp, bottom = 14.dp, end = 4.dp)
                } else {
                    Modifier.padding(vertical = 14.dp, horizontal = 4.dp)
                }
            )
            .testTag("message_item_${message.id}")
    ) {
        val roleText = when (message.role) {
            "user" -> stringResource(R.string.label_you)
            "error" -> stringResource(R.string.label_error)
            else -> displayName
        }
        Text(
            text = roleText,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (message.reasoningContent.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { thinkingExpanded = !thinkingExpanded }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                            .testTag("thinking_toggle_${message.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.thinking_process),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = if (thinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.thinking_process),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    AnimatedVisibility(visible = thinkingExpanded) {
                        Text(
                            text = message.reasoningContent,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = JetBrainsMono,
                                fontSize = 11.5.sp,
                                lineHeight = 19.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                        )
                    }
                }
            }
        }

        if (message.content.isNotEmpty()) {
            AnswerTextWithCitations(
                content = message.content,
                citations = message.citations,
                isError = isError,
                context = context
            )
        }

        if (!message.progressText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = message.progressText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!message.incompleteNote.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message.incompleteNote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (message.localToolCalls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                message.localToolCalls.forEachIndexed { idx, tc ->
                    val badgeText = CoreProtocol.clipText("${tc.name}(${tc.arguments}) → ${tc.result}", 72)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .clickable { onToolBadgeClick(tc) }
                            .testTag("tool_badge_${message.id}_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    fontSize = 11.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }

        if (message.sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                message.sources.forEachIndexed { i, src ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier
                            .clickable { openExternalUrl(context, src.url) }
                            .testTag("source_chip_${message.id}_$i")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "${i + 1}. ${src.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (!message.usageText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = message.usageText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clickable {
                            val copied = copyToClipboard(context, message.content)
                            if (copied) {
                                copiedRecently = true
                            } else {
                                onCopyStatus("剪贴板写入失败")
                            }
                        }
                        .testTag("copy_btn_${message.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (copiedRecently) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (copiedRecently) stringResource(R.string.action_copied) else stringResource(R.string.action_copy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = borderColor)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun AnswerTextWithCitations(
    content: String,
    citations: List<CitationPoint>,
    isError: Boolean,
    context: Context
) {
    val textColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    val accentColor = MaterialTheme.colorScheme.primary

    if (citations.isEmpty()) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor
        )
        return
    }

    val codePoints = remember(content) {
        val list = mutableListOf<String>()
        var i = 0
        while (i < content.length) {
            val cp = content.codePointAt(i)
            val charCount = Character.charCount(cp)
            list.add(content.substring(i, i + charCount))
            i += charCount
        }
        list
    }

    val annotated = remember(content, citations, textColor, accentColor) {
        buildAnnotatedString {
            var start = 0
            for (c in citations) {
                val safeEnd = c.endIndex.coerceIn(0, codePoints.size)
                if (safeEnd > start) {
                    append(codePoints.subList(start, safeEnd).joinToString(""))
                    start = safeEnd
                }
                pushStringAnnotation(tag = "URL", annotation = c.url)
                withStyle(
                    SpanStyle(
                        color = accentColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(" [${c.number}] ")
                }
                pop()
            }
            if (start < codePoints.size) {
                append(codePoints.subList(start, codePoints.size).joinToString(""))
            }
        }
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()
                ?.let { ann -> openExternalUrl(context, ann.item) }
        }
    )
}

fun copyToClipboard(context: Context, text: String): Boolean {
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("Fluorine", text))
        true
    } catch (_: Exception) {
        false
    }
}

fun openExternalUrl(context: Context, rawUrl: String) {
    val safe = CoreProtocol.safeUrl(rawUrl) ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
