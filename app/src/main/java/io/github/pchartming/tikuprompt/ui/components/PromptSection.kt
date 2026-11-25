package io.github.pchartming.tikuprompt.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.pchartming.tikuprompt.R
import io.github.pchartming.tikuprompt.prompt.PromptProfile
import io.github.pchartming.tikuprompt.ui.PromptCallbacks

@Composable
fun PromptSection(
    profile: PromptProfile,
    prompt: String,
    isEditing: Boolean,
    isModified: Boolean,
    errors: List<String>,
    callbacks: PromptCallbacks,
) {
    SectionBlock(
        title = "生成提示词",
        subtitle = "按 ${profile.name} ${profile.formatBadge} 的格式拼接，复制后粘贴到任意 AI 对话。",
    ) {
        if (errors.isNotEmpty()) {
            WarningNote(
                text = buildString {
                    append("暂时无法生成提示词：")
                    errors.forEach { error -> append("\n• $error") }
                },
            )
            HintNote("请检查上方的范围、题型和题量设置。")
            return@SectionBlock
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                isEditing -> Badge(
                    text = "编辑中",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )

                isModified -> Badge(
                    text = "已手动修改",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )

                else -> Badge(text = "自动生成")
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${prompt.length} 字符",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isEditing) {
            OutlinedTextField(
                value = prompt,
                onValueChange = callbacks.onEditText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提示词内容") },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 10,
                maxLines = 14,
                shape = MaterialTheme.shapes.medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = callbacks.onFinishEdit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("完成编辑")
                }
                TextButton(onClick = callbacks.onReset) {
                    Text("恢复自动")
                }
            }
            HintNote("编辑只覆盖当前这一份提示词；更改上方任何设置后会自动重新生成，并丢弃修改。")
        } else {
            PromptPreview(prompt = prompt)

            Button(
                onClick = callbacks.onCopy,
                modifier = Modifier.fillMaxWidth(),
                enabled = prompt.isNotBlank(),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_content_copy),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("复制提示词")
            }

            if (profile.exportHint.isNotBlank()) {
                HintNote(profile.exportHint)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = callbacks.onBeginEdit,
                    modifier = Modifier.weight(1f),
                    enabled = prompt.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("编辑")
                }
                OutlinedButton(
                    onClick = callbacks.onShare,
                    modifier = Modifier.weight(1f),
                    enabled = prompt.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("分享")
                }
            }

            if (isModified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "当前是手动修改版本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = callbacks.onReset) {
                        Text("恢复自动")
                    }
                }
            }
            HintNote(
                if (isModified) {
                    "手动修改只作用于当前提示词；更改上方任一设置都会重新自动生成。"
                } else {
                    "复制后粘贴到任意 AI 对话。模型返回 JSON 后，请在目标 App 中导入并核对题目与答案。"
                },
            )
        }
    }
}

@Composable
private fun PromptPreview(prompt: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 330.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}