package io.github.pchartming.tikuprompt.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pchartming.tikuprompt.data.CourseCatalog
import io.github.pchartming.tikuprompt.export.StudyLockPackContent
import io.github.pchartming.tikuprompt.export.StudyLockPackMetadata
import io.github.pchartming.tikuprompt.export.StudyLockParseResult
import io.github.pchartming.tikuprompt.export.StudyLockZipExporter
import io.github.pchartming.tikuprompt.ui.StudyLockCallbacks
import io.github.pchartming.tikuprompt.ui.StudyLockDraft
import io.github.pchartming.tikuprompt.ui.StudyLockExportFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StudyLockExportSection(
    drafts: List<StudyLockDraft>,
    selectedKey: Long?,
    catalog: CourseCatalog,
    callbacks: StudyLockCallbacks,
    modifier: Modifier = Modifier,
) {
    var isParsing by remember { mutableStateOf(false) }
    var parseResults by remember {
        mutableStateOf<Map<Long, StudyLockParseResult>>(emptyMap())
    }
    var buildErrors by remember { mutableStateOf(emptyList<String>()) }
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selected = drafts.firstOrNull { it.key == selectedKey } ?: drafts.firstOrNull()
    val inputsKey = drafts.map { it.key to it.json }

    LaunchedEffect(inputsKey) {
        buildErrors = emptyList()
        if (drafts.isEmpty()) {
            isParsing = false
            parseResults = emptyMap()
            return@LaunchedEffect
        }
        isParsing = true
        delay(350)
        parseResults = withContext(Dispatchers.Default) {
            drafts.associate { draft ->
                draft.key to if (draft.json.isBlank()) {
                    StudyLockParseResult()
                } else {
                    StudyLockZipExporter.parse(draft.json)
                }
            }
        }
        isParsing = false
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = "AI 结果 → 学习锁 zip",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "可添加多个 AI 结果；每个题库使用添加时的范围，一次生成一个 zip。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (selected == null) {
            HintNote("还没有题库。点击“新增”粘贴或选择一个 AI 返回的 JSON。")
            Button(onClick = callbacks.onAdd) {
                Text("新增题库")
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                drafts.forEach { draft ->
                    FilterChip(
                        selected = draft.key == selected.key,
                        onClick = { callbacks.onSelect(draft.key) },
                        label = {
                            Text(
                                text = draft.effectiveTitle(catalog),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 148.dp),
                            )
                        },
                    )
                }
            }
            TextButton(
                onClick = callbacks.onAdd,
                enabled = drafts.size < StudyLockZipExporter.MAX_PACKS,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("新增")
            }
        }

        val defaultTitle = StudyLockPackMetadata.defaultTitle(selected.form, catalog)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "题库 ${drafts.indexOfFirst { it.key == selected.key } + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = StudyLockPackMetadata.contextSummary(selected.form, catalog),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (drafts.size > 1) {
                IconButton(onClick = { callbacks.onRemove(selected.key) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除此题库",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedTextField(
            value = selected.titleOverride ?: defaultTitle,
            onValueChange = { callbacks.onTitleChange(selected.key, it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("题库名称") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        OutlinedTextField(
            value = selected.json,
            onValueChange = { callbacks.onJsonChange(selected.key, it) },
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
            TextButton(onClick = { callbacks.onPaste(selected.key) }) {
                Text("从剪贴板粘贴")
            }
            TextButton(onClick = { callbacks.onPickFile(selected.key) }) {
                Text("选择文件")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = { callbacks.onClear(selected.key) },
                enabled = selected.json.isNotEmpty(),
            ) {
                Text("清空")
            }
        }

        val result = parseResults[selected.key]
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
                    text = "正在校验…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            selected.json.isBlank() -> HintNote(
                "粘贴 AI 返回的 JSON 后自动校验。支持直接粘贴带代码围栏的内容。",
            )

            result == null -> HintNote("准备校验…")

            result.errors.isNotEmpty() -> WarningNote(
                buildString {
                    append("暂时不能导出：")
                    result.errors.forEach { error -> append("\n• ").append(error) }
                },
            )

            else -> {
                Text(
                    text = "✓ 已识别 ${result.questions.size} 道题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (result.warnings.isNotEmpty()) {
                    HintNote(
                        result.warnings.joinToString("\n") { warning -> "• $warning" },
                    )
                }
            }
        }

        val totalQuestions = drafts.sumOf { draft ->
            parseResults[draft.key]?.questions?.size ?: 0
        }
        val unfinished = drafts.count { draft ->
            draft.json.isBlank() || parseResults[draft.key]?.isValid != true
        }
        val allReady = drafts.isNotEmpty() && !isParsing && unfinished == 0

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${drafts.size} 个题库 · $totalQuestions 道题",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isParsing) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (!allReady && !isParsing) {
            Text(
                text = "还有 $unfinished 个题库未完成或未通过校验。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (buildErrors.isNotEmpty()) {
            WarningNote(
                text = buildString {
                    append("打包失败：")
                    buildErrors.forEach { error -> append("\n• ").append(error) }
                },
            )
        }

        Button(
            onClick = {
                buildErrors = emptyList()
                val ready = drafts.mapNotNull { draft ->
                    val parsed = parseResults[draft.key] ?: return@mapNotNull null
                    if (!parsed.isValid) return@mapNotNull null
                    StudyLockPackContent(
                        meta = StudyLockPackMetadata.from(
                            form = draft.form,
                            catalog = catalog,
                            title = draft.effectiveTitle(catalog),
                        ),
                        questions = parsed.questions,
                    )
                }
                if (ready.size != drafts.size) return@Button
                exporting = true
                scope.launch {
                    val built = withContext(Dispatchers.Default) {
                        StudyLockZipExporter.buildZip(ready)
                    }
                    exporting = false
                    val bytes = built.bytes
                    if (bytes != null) {
                        callbacks.onExport(
                            StudyLockExportFile(
                                bytes = bytes,
                                suggestedName = studyLockZipName(),
                                packCount = built.packCount,
                                questionCount = built.questionCount,
                            ),
                        )
                    } else {
                        buildErrors = built.errors
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = allReady && !exporting,
        ) {
            if (exporting) {
                Text("正在打包…")
            } else {
                Text("生成学习锁 zip")
            }
        }
        HintNote(
            "保存后：学习锁家长端 → 题库 → 上传 zip。也可以把 zip 放到电视“下载”目录后本机导入。",
        )
    }
}

private fun studyLockZipName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "tikuprompt-studylock-$stamp.zip"
}