package io.github.pchartming.tikuprompt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pchartming.tikuprompt.data.CourseCatalog
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.model.ScopeMode
import io.github.pchartming.tikuprompt.ui.FormCallbacks

@Composable
fun ScopeSection(
    catalog: CourseCatalog,
    form: PromptForm,
    callbacks: FormCallbacks,
) {
    val gradeOptions = catalog.grades.map {
        SelectorOption(key = it.no.toString(), label = it.label)
    }
    val subjectOptions = catalog.subjectsFor(form.grade).map {
        SelectorOption(key = it.id, label = it.label)
    }
    val semesterOptions = catalog.semestersFor(form.grade, form.subjectId).map {
        SelectorOption(key = it.toString(), label = catalog.semesterLabel(it))
    }
    val books = catalog.booksFor(form.grade, form.semester, form.subjectId)
    val bookOptions = books.map {
        SelectorOption(key = it.key, label = it.displayName)
    }
    val book = catalog.findBook(form.bookKey)
    val units = book?.units.orEmpty()
    val selectedUnit = units.firstOrNull { it.no == form.unitNo }
    val lessonOptions = selectedUnit?.lessons?.mapIndexed { index, lesson ->
        SelectorOption(key = index.toString(), label = lesson)
    }.orEmpty()
    val scopeOptions = ScopeMode.entries.map {
        SelectorOption(key = it.name, label = it.label)
    }

    SectionBlock(
        title = "出题范围",
        subtitle = "课程目录含单元和课时名称；需要补充教材原文或额外约束时，生成后直接修改提示词。",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SelectorField(
                label = "年级",
                value = catalog.gradeLabel(form.grade),
                options = gradeOptions,
                onSelect = { key -> key.toIntOrNull()?.let(callbacks.onGrade) },
                modifier = Modifier.weight(1f),
            )
            SelectorField(
                label = "学期",
                value = catalog.semesterLabel(form.semester),
                options = semesterOptions,
                emptyValue = "暂无教材",
                onSelect = { key -> key.toIntOrNull()?.let(callbacks.onSemester) },
                modifier = Modifier.weight(1f),
            )
        }

        SelectorField(
            label = "学科",
            value = catalog.subjectLabel(form.subjectId),
            options = subjectOptions,
            onSelect = callbacks.onSubject,
        )

        SelectorField(
            label = "教材",
            value = book?.displayName.orEmpty(),
            options = bookOptions,
            emptyValue = "暂无教材",
            onSelect = callbacks.onBook,
        )

        FieldLabel("范围")
        SingleChoiceChips(
            options = scopeOptions,
            selectedKey = form.scopeMode.name,
            onSelect = { key ->
                ScopeMode.entries.firstOrNull { it.name == key }?.let(callbacks.onScopeMode)
            },
        )

        when (form.scopeMode) {
            ScopeMode.UNIT -> {
                SelectorField(
                    label = "单元",
                    value = selectedUnit?.pickerLabel.orEmpty(),
                    options = units.map {
                        SelectorOption(key = it.no.toString(), label = it.pickerLabel)
                    },
                    emptyValue = "本册暂无单元",
                    onSelect = { key -> key.toIntOrNull()?.let(callbacks.onUnit) },
                )
                if (selectedUnit != null && selectedUnit.lessons.isNotEmpty()) {
                    SelectorField(
                        label = "课时（可选）",
                        value = form.lessonIndex?.let { selectedUnit.lessons.getOrNull(it) }
                            ?: "整个单元",
                        options = listOf(
                            SelectorOption(key = "all", label = "整个单元（推荐）"),
                        ) + lessonOptions,
                        onSelect = { key ->
                            callbacks.onLesson(
                                if (key == "all") null else key.toIntOrNull(),
                            )
                        },
                    )
                    HintNote("选择具体课时后，提示词会聚焦这一课；不选则覆盖整个单元。")
                }
            }

            ScopeMode.MIDTERM -> HintNote("按前半学期的实际教学进度出题，不锁定具体单元。")
            ScopeMode.FINAL -> HintNote("覆盖全册；提示词会自动附带本册单元目录，提醒覆盖主要单元。")
            ScopeMode.REVIEW -> HintNote("全册查漏补缺，适合期末总复习和跨单元综合练习。")
        }
    }
}