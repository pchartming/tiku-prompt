package io.github.pchartming.tikuprompt.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.pchartming.tikuprompt.prompt.DifficultyStyle
import io.github.pchartming.tikuprompt.prompt.PromptProfile

@Composable
fun ProfileHelpDialog(
    profile: PromptProfile,
    onDismiss: () -> Unit,
) {
    var showSpec by remember(profile.id) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${profile.name} ${profile.formatBadge}",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = profile.tagline,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item {
                            Text(
                                text = profile.help.summary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item {
                            HelpInfo("支持题型", profile.supportedTypes.joinToString("、") { it.labelZh })
                        }
                        item {
                            val difficulty = when (profile.difficultyStyle) {
                                DifficultyStyle.EASY_MEDIUM_HARD -> "easy / medium / hard"
                                DifficultyStyle.STARS_1_TO_5 -> "1–5 星（整数）"
                                DifficultyStyle.CONTENT_ONLY ->
                                    "仅作为内容难度，不输出 difficulty 字段"
                            }
                            HelpInfo("难度写法", difficulty)
                        }
                        item {
                            val optionText = profile.fixedOptionCount?.let { "固定 $it 个" }
                                ?: "2–${profile.maxOptions} 个"
                            HelpInfo("选项数量", optionText)
                        }
                        item {
                            HelpInfo(
                                "题量",
                                "本工具提供 ${profile.countChoices.joinToString(" / ")} 道预设；格式上限 ${profile.maxCount} 题。",
                            )
                        }
                        item {
                            Text(
                                text = "格式要点",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        items(profile.help.notes) { note ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (profile.help.importSteps.isNotEmpty()) {
                            item {
                                Text(
                                    text = "导入方式",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            items(profile.help.importSteps) { step ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = step,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        item {
                            HintNote(
                                text = "本工具只在本机拼接提示词，不调用模型、不联网，也不会校验模型返回的 JSON。复制后请自行核对题目与答案。",
                            )
                        }
                        item {
                            HorizontalDivider()
                        }
                        item {
                            TextButton(
                                onClick = { showSpec = !showSpec },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (showSpec) "收起完整格式规范" else "查看完整格式规范",
                                )
                            }
                        }
                        if (showSpec) {
                            item {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = profile.spec,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpInfo(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}