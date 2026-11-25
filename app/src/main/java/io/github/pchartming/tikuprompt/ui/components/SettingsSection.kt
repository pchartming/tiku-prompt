package io.github.pchartming.tikuprompt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pchartming.tikuprompt.model.DifficultyIntent
import io.github.pchartming.tikuprompt.model.OutputLanguage
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.model.QuestionType
import io.github.pchartming.tikuprompt.prompt.DifficultyStyle
import io.github.pchartming.tikuprompt.prompt.PromptProfile
import io.github.pchartming.tikuprompt.ui.FormCallbacks

@Composable
fun SettingsSection(
    profile: PromptProfile,
    form: PromptForm,
    callbacks: FormCallbacks,
) {
    val typeOptions = profile.supportedTypes.map {
        SelectorOption(key = it.id, label = it.labelZh)
    }
    val selectedTypeIds = form.questionTypes.map { it.id }.toSet()
    val countOptions = profile.countChoices.map {
        SelectorOption(key = it.toString(), label = "$it 道")
    }
    val difficultyOptions = DifficultyIntent.entries.map {
        SelectorOption(key = it.name, label = it.label)
    }
    val optionCounts = profile.optionChoices.map {
        SelectorOption(key = it.toString(), label = "$it 个")
    }
    val languageOptions = OutputLanguage.entries.map {
        SelectorOption(key = it.name, label = it.label)
    }
    val hasChoice = form.questionTypes.any {
        it == QuestionType.SINGLE || it == QuestionType.MULTIPLE
    }
    val needsOptions = hasChoice && profile.optionChoices.isNotEmpty()

    SectionBlock(
        title = "出题设置",
        subtitle = if (profile.supportedTypes.size > 1) {
            "题型可多选；同时选择单选和多选，就相当于混合题。"
        } else {
            "当前格式的题型固定，出题范围和题量仍然可调。"
        },
    ) {
        FieldLabel("题型")
        if (profile.supportedTypes.size > 1) {
            MultiChoiceChips(
                options = typeOptions,
                selectedKeys = selectedTypeIds,
                onToggle = { key ->
                    QuestionType.entries.firstOrNull { it.id == key }
                        ?.let(callbacks.onToggleType)
                },
            )
            if (form.questionTypes.size > 1) {
                HintNote("题量允许时，提示词会要求每种题型至少出 1 道。")
            }
        } else {
            HintNote(
                "该格式只支持${profile.supportedTypes.firstOrNull()?.labelZh.orEmpty()}题，无需选择题型。",
            )
        }
        if (QuestionType.MULTIPLE in form.questionTypes) {
            WarningNote("含多选题：AI 可能漏选正确项，导入目标 App 前请逐题核对答案。")
        }

        FieldLabel("题目数量")
        SingleChoiceChips(
            options = countOptions,
            selectedKey = form.count.toString(),
            onSelect = { key -> key.toIntOrNull()?.let(callbacks.onCount) },
        )

        FieldLabel("难度")
        SingleChoiceChips(
            options = difficultyOptions,
            selectedKey = form.difficulty.name,
            onSelect = { key ->
                DifficultyIntent.entries.firstOrNull { it.name == key }
                    ?.let(callbacks.onDifficulty)
            },
        )
        if (profile.difficultyStyle == DifficultyStyle.CONTENT_ONLY) {
            HintNote("该格式没有难度字段；难度只影响题目内容，不会写入 JSON。")
        }

        if (needsOptions) {
            val fixedOptions = profile.fixedOptionCount
            FieldLabel("每题选项数")
            if (fixedOptions != null) {
                HintNote("该格式固定 $fixedOptions 个选项（A/B/C/D），无需设置。")
            } else {
                SingleChoiceChips(
                    options = optionCounts,
                    selectedKey = form.optionsPerQuestion.toString(),
                    onSelect = { key -> key.toIntOrNull()?.let(callbacks.onOptions) },
                )
            }
            if (QuestionType.BOOLEAN in form.questionTypes) {
                HintNote("判断题的选项固定为“正确 / 错误”，选项数只作用于单选题和多选题。")
            }
        } else if (QuestionType.BOOLEAN in form.questionTypes) {
            HintNote("判断题的选项固定为“正确 / 错误”，不需要设置选项数。")
        } else if (form.questionTypes.isNotEmpty()) {
            HintNote("当前题型不需要选项。")
        }

        FieldLabel("输出语言")
        SingleChoiceChips(
            options = languageOptions,
            selectedKey = form.outputLanguage.name,
            onSelect = { key ->
                OutputLanguage.entries.firstOrNull { it.name == key }
                    ?.let(callbacks.onLanguage)
            },
        )
        HintNote("输出语言只影响题干和解析等正文；JSON 字段名与格式规则不变。")
    }
}

@Composable
fun NotesSection(
    form: PromptForm,
    callbacks: FormCallbacks,
) {
    SectionBlock(
        title = "补充要求与资料",
        subtitle = "可选。资料越贴近课堂，生成的题目越准确。",
    ) {
        OutlinedTextField(
            value = form.extraRequirement,
            onValueChange = callbacks.onExtraRequirement,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("额外要求") },
            placeholder = {
                Text("如：只考计算题、重点覆盖易错点、不要出应用题…")
            },
            minLines = 2,
            maxLines = 4,
            shape = MaterialTheme.shapes.medium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = form.referenceNotes,
                onValueChange = callbacks.onReferenceNotes,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("补充资料（教材摘录 / 知识点 / 课堂笔记）") },
                placeholder = {
                    Text(
                        "粘贴本课原文、公式、单词表或老师强调的知识点。留空则由模型按教材常识出题。",
                    )
                },
                minLines = 4,
                maxLines = 8,
                shape = MaterialTheme.shapes.medium,
            )
            Text(
                text = "补充资料会原样加入提示词，只在本机处理，不会上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}