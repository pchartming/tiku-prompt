package io.github.pchartming.tikuprompt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.pchartming.tikuprompt.export.HaloTvQuizExporter
import io.github.pchartming.tikuprompt.export.HaloTvQuizFile
import io.github.pchartming.tikuprompt.export.HaloTvQuizParseResult
import io.github.pchartming.tikuprompt.ui.HaloTvCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HaloTvExportSection(
    json: String,
    callbacks: HaloTvCallbacks,
    modifier: Modifier = Modifier,
) {
    var isParsing by remember { mutableStateOf(false) }
    var parseResult by remember { mutableStateOf(HaloTvQuizParseResult()) }

    LaunchedEffect(json) {
        parseResult = HaloTvQuizParseResult()
        if (json.isBlank()) {
            isParsing = false
            return@LaunchedEffect
        }
        isParsing = true
        delay(350)
        parseResult = withContext(Dispatchers.Default) {
            HaloTvQuizExporter.parse(json)
        }
        isParsing = false
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = "AI 结果 → HaloTV 题库",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "按 HaloTV 3.6+ 家长出题格式在本机校验；只支持 2–4 选一单选题。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = json,
            onValueChange = callbacks.onJsonChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("AI 返回的 JSON") },
            placeholder = {
                Text("粘贴 AI 返回的 JSON；带 Markdown 代码围栏也可以")
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            minLines = 6,
            maxLines = 10,
            shape = MaterialTheme.shapes.medium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = callbacks.onPaste) {
                Text("从剪贴板粘贴")
            }
            TextButton(onClick = callbacks.onPickFile) {
                Text("选择文件")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = callbacks.onClear,
                enabled = json.isNotEmpty(),
            ) {
                Text("清空")
            }
        }

        when {
            isParsing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "正在校验 HaloTV 格式…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            json.isBlank() -> HintNote(
                "粘贴 AI 返回的 JSON 后自动校验。导出前不会上传或调用模型。",
            )

            parseResult.errors.isNotEmpty() -> WarningNote(
                buildString {
                    append("暂时不能导出：")
                    parseResult.errors.forEach { error -> append("\n• ").append(error) }
                },
            )

            parseResult.quiz != null -> {
                val quiz = checkNotNull(parseResult.quiz)
                Text(
                    text = "✓ 格式校验通过：题库「${quiz.name}」· ${quiz.questions.size} 道题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (parseResult.warnings.isNotEmpty()) {
                    HintNote(
                        parseResult.warnings.joinToString("\n") { warning -> "• $warning" },
                    )
                }
                HintNote(
                    "HaloTV 全部题库合计最多 6 套、80 题；同一台电视上题库名不能重复，每次导入只新增一套。导出文件不超过 256 KB。",
                )
            }

            else -> HintNote("准备校验…")
        }

        Button(
            onClick = {
                val quiz = parseResult.quiz
                if (quiz != null) {
                    callbacks.onExport(
                        HaloTvQuizFile(
                            bytes = HaloTvQuizExporter.build(quiz),
                            suggestedName = HaloTvQuizExporter.suggestedFileName(quiz.name),
                            questionCount = quiz.questions.size,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = parseResult.quiz != null && !isParsing,
        ) {
            Text("导出 HaloTV JSON")
        }

        HintNote(
            "导入：HaloTV 手机管理页 → 儿童与长辈 → 家长出题 → 导入题库，选择刚导出的 .json 文件。",
        )
    }
}