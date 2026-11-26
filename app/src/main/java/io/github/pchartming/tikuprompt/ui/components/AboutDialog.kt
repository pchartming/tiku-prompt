package io.github.pchartming.tikuprompt.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.pchartming.tikuprompt.R
import kotlinx.coroutines.delay

private const val AUTHOR_NAME = "pchartming"
private const val DOUYIN_ID = "34204490303"
private const val GITHUB_URL = "https://github.com/pchartming/tiku-prompt"
private const val GITHUB_LABEL = "github.com/pchartming/tiku-prompt"

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    fun openGithub() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
        runCatching { context.startActivity(view) }
            .onFailure {
                Toast.makeText(
                    context,
                    "没有找到可打开网页的应用",
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

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
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "关于",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "题库提示词 · 完全离线",
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

                    HorizontalDivider()

                    Text(
                        text = "多格式 AI 出题提示词工具。不调用模型、不需要账号，资料只留在本机。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "作者 $AUTHOR_NAME，AI 工具爱好者。有问题、建议或发现 bug，欢迎来抖音找我，后续更新和 AI 工具分享也会发在那里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.about_douyin_qr),
                                contentDescription = "抖音二维码：$AUTHOR_NAME，抖音号 $DOUYIN_ID",
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                                contentScale = ContentScale.Fit,
                            )
                            Text(
                                text = "抖音扫码 · $AUTHOR_NAME",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "抖音号 $DOUYIN_ID",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("抖音号", DOUYIN_ID),
                                        )
                                        copied = true
                                    },
                                ) {
                                    Text(if (copied) "已复制" else "复制")
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = "GitHub",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "源码、更新与问题反馈",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = GITHUB_LABEL,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    OutlinedButton(
                        onClick = { openGithub() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("打开 GitHub 项目")
                    }
                    Text(
                        text = "外部链接由系统浏览器打开；应用本身不联网。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = "非官方格式兼容 · MIT License",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}