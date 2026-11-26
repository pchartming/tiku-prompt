package io.github.pchartming.tikuprompt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pchartming.tikuprompt.lan.LanAddress
import io.github.pchartming.tikuprompt.prompt.PromptProfile

/**
 * A per-target address book for the TV's parent web page.
 *
 * Editing is explicit: the URL is written only when the user taps 保存, after
 * which the editor collapses so the link cannot be changed by an accidental
 * tap. The app only stores the full URL and launches ACTION_VIEW; it never
 * probes the address, so no INTERNET permission or WebView is needed.
 */
@Composable
fun LanAccessSection(
    profile: PromptProfile,
    savedUrl: String,
    onSave: (String) -> Unit,
    onPaste: () -> String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (profile.lanUrlHint.isBlank()) return

    val savedCheck = LanAddress.normalize(savedUrl)
    var draft by remember(profile.id, savedUrl) { mutableStateOf(savedUrl) }
    var editing by remember(profile.id, savedUrl) {
        mutableStateOf(savedUrl.isBlank() || !savedCheck.isValid)
    }
    val draftCheck = LanAddress.normalize(draft)

    SectionBlock(
        title = "局域网家长端",
        subtitle = if (editing || !savedCheck.isValid) {
            "地址按目标分别保存；保存后折叠，减少误碰。"
        } else {
            null
        },
        modifier = modifier,
    ) {
        if (!editing && savedCheck.isValid) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${profile.name} · 已保存",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = savedCheck.normalizedUrl,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = {
                        draft = savedUrl
                        editing = true
                    },
                ) {
                    Text("修改")
                }
            }
            Button(
                onClick = { onOpen(savedCheck.normalizedUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开家长端")
            }
        } else {
            Text(
                text = "当前目标：${profile.name}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("家长端地址") },
                placeholder = { Text(profile.lanUrlPlaceholder) },
                singleLine = true,
                isError = draft.isNotBlank() && !draftCheck.isValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                shape = MaterialTheme.shapes.medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { onPaste()?.let { pasted -> draft = pasted } },
                ) {
                    Text("粘贴地址")
                }
                TextButton(
                    onClick = { draft = "" },
                    enabled = draft.isNotEmpty(),
                ) {
                    Text("清除")
                }
                Spacer(modifier = Modifier.weight(1f))
                if (savedUrl.isNotBlank()) {
                    TextButton(
                        onClick = {
                            draft = savedUrl
                            editing = false
                        },
                    ) {
                        Text("取消")
                    }
                }
                TextButton(
                    onClick = {
                        if (draft.isBlank()) {
                            onSave("")
                            editing = false
                        } else if (draftCheck.isValid) {
                            onSave(draftCheck.normalizedUrl)
                            draft = draftCheck.normalizedUrl
                            editing = false
                        }
                    },
                    enabled = draftCheck.isValid ||
                        (draft.isBlank() && savedUrl.isNotBlank()),
                ) {
                    Text("保存")
                }
            }
            Text(
                text = profile.lanUrlHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                draft.isBlank() -> Unit

                !draftCheck.isValid -> WarningNote(
                    text = draftCheck.error ?: "地址格式不正确。",
                )

                else -> {
                    Text(
                        text = "✓ 地址格式正确，保存后生效",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (draftCheck.normalizedUrl != draft) {
                        Text(
                            text = "保存后会使用：${draftCheck.normalizedUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = "地址可能包含临时验证码，只保存在本机；应用没有网络权限，打开后由系统浏览器访问。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}