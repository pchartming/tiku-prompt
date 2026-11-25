package io.github.pchartming.tikuprompt.model

import kotlinx.serialization.Serializable

@Serializable
enum class ScopeMode(val label: String) {
    UNIT("单元"),
    MIDTERM("期中"),
    FINAL("期末"),
    REVIEW("复习"),
}

@Serializable
enum class QuestionType(
    val id: String,
    val labelZh: String,
    val labelEn: String,
) {
    SINGLE("single", "单选", "Single choice"),
    MULTIPLE("multiple", "多选", "Multiple choice"),
    BOOLEAN("boolean", "判断", "True / false"),
    TEXT("text", "填空", "Fill in the blank"),
    SUBJECTIVE("subjective", "主观", "Open response"),
}

@Serializable
enum class DifficultyIntent(val label: String) {
    AUTO("由模型决定"),
    EASY("简单"),
    MEDIUM("中等"),
    HARD("较难"),
}

@Serializable
enum class OutputLanguage(
    val label: String,
    val code: String,
) {
    ZH("中文", "zh-CN"),
    EN("English", "en-US"),
}

/**
 * Everything the user chooses. Format-independent on purpose: switching the
 * target app keeps the same scope and settings, only the prompt format changes.
 *
 * [promptOverride] deliberately lives in the view model, not here: it is a
 * temporary edit of one generated prompt and must not survive a form change.
 */
@Serializable
data class PromptForm(
    val targetId: String = "planyourtv",
    val grade: Int = 4,
    val semester: Int = 1,
    val subjectId: String = "MATH",
    val bookKey: String = "",
    val scopeMode: ScopeMode = ScopeMode.UNIT,
    val unitNo: Int = 1,
    val lessonIndex: Int? = null,
    val questionTypes: Set<QuestionType> = setOf(QuestionType.SINGLE),
    val count: Int = 20,
    val difficulty: DifficultyIntent = DifficultyIntent.AUTO,
    val optionsPerQuestion: Int = 4,
    val outputLanguage: OutputLanguage = OutputLanguage.ZH,
    val extraRequirement: String = "",
    val referenceNotes: String = "",
)

data class GradeInfo(
    val no: Int,
    val label: String,
)

data class SubjectInfo(
    val id: String,
    val label: String,
)

data class CourseUnit(
    val no: Int,
    val title: String,
    val kind: String,
    val lessons: List<String>,
) {
    val displayTitle: String get() = title.ifBlank { "第${no}单元" }

    val pickerLabel: String get() = "第${no}单元 · $displayTitle"
}

data class CourseBook(
    val key: String,
    val grade: Int,
    val semesters: List<Int>,
    val subjectId: String,
    val textbookId: String,
    val textbookName: String,
    val volume: String,
    val editionType: String,
    val units: List<CourseUnit>,
) {
    val displayName: String
        get() = buildList {
            add(textbookName.ifBlank { textbookId })
            if (volume.isNotBlank()) add(volume)
            if (editionType.isNotBlank()) add(editionType)
        }.joinToString(" · ")
}