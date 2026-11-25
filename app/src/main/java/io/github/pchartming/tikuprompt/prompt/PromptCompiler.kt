package io.github.pchartming.tikuprompt.prompt

import io.github.pchartming.tikuprompt.data.CourseCatalog
import io.github.pchartming.tikuprompt.model.CourseBook
import io.github.pchartming.tikuprompt.model.CourseUnit
import io.github.pchartming.tikuprompt.model.DifficultyIntent
import io.github.pchartming.tikuprompt.model.OutputLanguage
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.model.QuestionType
import io.github.pchartming.tikuprompt.model.ScopeMode

data class PromptBuildResult(
    val prompt: String?,
    val errors: List<String>,
)

/**
 * Compiles "shared scope/context + target format spec" into the final prompt.
 * The format spec ends with its own "出题要求：" heading; the shared block is
 * appended below it, exactly like the parent-center web page does.
 */
object PromptCompiler {

    fun build(
        form: PromptForm,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptBuildResult {
        val errors = mutableListOf<String>()
        val book = catalog.findBook(form.bookKey)
        if (book == null) {
            errors += "请选择有效的年级、学期、学科和教材。"
        } else {
            if (book.grade != form.grade ||
                book.subjectId != form.subjectId ||
                form.semester !in book.semesters
            ) {
                errors += "当前教材与所选的年级、学期或学科不一致。"
            }
        }

        val types = profile.supportedTypes.filter { it in form.questionTypes }
        if (types.isEmpty()) {
            errors += "请至少选择一种当前格式支持的题型。"
        }
        if (form.questionTypes.any { !profile.supports(it) }) {
            errors += "当前格式不支持所选的某种题型。"
        }

        if (form.count !in 1..profile.maxCount) {
            errors += "题量必须在 1–${profile.maxCount} 道之间。"
        }

        val needsOptions = types.any { it == QuestionType.SINGLE || it == QuestionType.MULTIPLE }
        if (needsOptions) {
            val fixedOptions = profile.fixedOptionCount
            if (fixedOptions != null && form.optionsPerQuestion != fixedOptions) {
                errors += "当前格式固定 ${fixedOptions} 个选项。"
            } else if (fixedOptions == null &&
                form.optionsPerQuestion !in 2..profile.maxOptions
            ) {
                errors += "选项数必须在 2–${profile.maxOptions} 个之间。"
            }
        }

        val unit = if (form.scopeMode == ScopeMode.UNIT && book != null) {
            catalog.findUnit(book, form.unitNo).also {
                if (it == null) errors += "当前教材中没有这个单元，请重新选择。"
            }
        } else {
            null
        }

        val lesson = if (unit != null && form.lessonIndex != null) {
            unit.lessons.getOrNull(form.lessonIndex).also {
                if (it == null) errors += "课时选择已失效，请重新选择。"
            }
        } else {
            null
        }

        if (errors.isNotEmpty()) {
            return PromptBuildResult(prompt = null, errors = errors.distinct())
        }

        val spec = profile.spec.trim()
        if (spec.isEmpty()) {
            return PromptBuildResult(
                prompt = null,
                errors = listOf("目标格式的提示词规范未能加载，请重试。"),
            )
        }

        val context = buildContext(
            form = form,
            profile = profile,
            catalog = catalog,
            book = requireNotNull(book),
            unit = unit,
            selectedLesson = lesson,
            types = types,
            needsOptions = needsOptions,
        )
        return PromptBuildResult(
            prompt = spec + "\n\n" + context.trim(),
            errors = emptyList(),
        )
    }

    private fun buildContext(
        form: PromptForm,
        profile: PromptProfile,
        catalog: CourseCatalog,
        book: CourseBook,
        unit: CourseUnit?,
        selectedLesson: String?,
        types: List<QuestionType>,
        needsOptions: Boolean,
    ): String {
        val lines = mutableListOf<String>()
        lines += "学段/年级：${catalog.gradeLabel(form.grade)}"
        lines += "学期：${catalog.semesterLabel(form.semester)}"
        lines += "科目：${catalog.subjectLabel(form.subjectId)}"
        lines += "教材：${book.displayName}"
        lines += scopeLines(form, book, unit, selectedLesson)
        lines += "题型：${typeText(types, profile, form.outputLanguage)}"
        lines += "题量：${form.count} 道"
        lines += "难度：${difficultyText(form.difficulty, profile)}"
        if (needsOptions) {
            lines += "每题选项数：${form.optionsPerQuestion} 个"
        }
        lines += "输出语言：${languageText(form.outputLanguage)}"
        lines += "额外要求：${form.extraRequirement.trim().ifBlank { "无" }}"

        val notes = form.referenceNotes.trim()
        if (notes.isNotEmpty()) {
            lines += ""
            lines += "补充资料（用户提供，用于保证题目内容准确；以下面资料为事实依据，不要编造资料里没有的内容，也不要逐字照抄）："
            lines += "【补充资料开始】"
            lines += notes
            lines += "【补充资料结束】"
        }
        return lines.joinToString("\n")
    }

    private fun scopeLines(
        form: PromptForm,
        book: CourseBook,
        unit: CourseUnit?,
        selectedLesson: String?,
    ): List<String> {
        return when (form.scopeMode) {
            ScopeMode.UNIT -> {
                val actual = requireNotNull(unit)
                val lines = mutableListOf<String>()
                val base = buildString {
                    append("范围：第${actual.no}单元「${actual.displayTitle}」")
                    if (selectedLesson != null) {
                        append("，本课重点：$selectedLesson")
                    } else {
                        append("，覆盖本单元全部课时")
                    }
                }
                lines += base
                if (selectedLesson == null && actual.lessons.isNotEmpty()) {
                    lines += "本单元课时：${actual.lessons.joinToString("、")}"
                }
                lines
            }

            ScopeMode.MIDTERM -> listOf(
                "范围：本学期期中考试范围（按前半学期实际教学进度覆盖重点知识与易错题）",
            )

            ScopeMode.FINAL -> buildList {
                add("范围：本学期期末考试范围（覆盖本册全部单元，兼顾综合运用）")
                if (book.units.isNotEmpty()) {
                    add(
                        "本册单元目录（用于安排考点，不要遗漏主要单元）：" +
                            book.units.joinToString("、") { "第${it.no}单元「${it.displayTitle}」" },
                    )
                }
            }

            ScopeMode.REVIEW -> buildList {
                add("范围：本学期总复习（覆盖全册，查漏补缺，突出易错点和跨单元综合）")
                if (book.units.isNotEmpty()) {
                    add(
                        "本册单元目录（用于安排考点，不要遗漏主要单元）：" +
                            book.units.joinToString("、") { "第${it.no}单元「${it.displayTitle}」" },
                    )
                }
            }
        }
    }

    private fun typeText(
        types: List<QuestionType>,
        profile: PromptProfile,
        language: OutputLanguage,
    ): String {
        val labels = types.joinToString("、") { type ->
            val label = when (language) {
                OutputLanguage.ZH -> type.labelZh
                OutputLanguage.EN -> type.labelEn
            }
            if (profile.emitTypeIds) "$label（${type.id}）" else label
        }
        val coverage = if (types.size > 1) "；题量允许时每种题型至少 1 道" else ""
        return labels + coverage
    }

    private fun difficultyText(intent: DifficultyIntent, profile: PromptProfile): String {
        return when (profile.difficultyStyle) {
            DifficultyStyle.EASY_MEDIUM_HARD -> when (intent) {
                DifficultyIntent.AUTO ->
                    "由你按题目实际难度判断（difficulty 只能使用 easy、medium、hard）"
                DifficultyIntent.EASY -> "简单（difficulty 使用 \"easy\"）"
                DifficultyIntent.MEDIUM -> "中等（difficulty 使用 \"medium\"）"
                DifficultyIntent.HARD -> "较难（difficulty 使用 \"hard\"）"
            }

            DifficultyStyle.STARS_1_TO_5 -> when (intent) {
                DifficultyIntent.AUTO ->
                    "由你按题目实际难度判断（difficulty 使用 1–5 的整数）"
                DifficultyIntent.EASY -> "简单（difficulty 使用 1 或 2）"
                DifficultyIntent.MEDIUM -> "中等（difficulty 使用 3）"
                DifficultyIntent.HARD -> "较难（difficulty 使用 4 或 5）"
            }

            DifficultyStyle.CONTENT_ONLY -> when (intent) {
                DifficultyIntent.AUTO ->
                    "由你按题目实际难度控制（本格式没有 difficulty 字段，难度只影响题目内容）"
                DifficultyIntent.EASY -> "简单（只影响题目内容，不输出 difficulty 字段）"
                DifficultyIntent.MEDIUM -> "中等（只影响题目内容，不输出 difficulty 字段）"
                DifficultyIntent.HARD -> "较难（只影响题目内容，不输出 difficulty 字段）"
            }
        }
    }

    private fun languageText(language: OutputLanguage): String = when (language) {
        OutputLanguage.ZH -> "中文（简体）"
        OutputLanguage.EN ->
            "English（题干、选项、解析等正文用英文；JSON 字段名、枚举值和 subject/level 字段的填写规则保持格式规范不变）"
    }
}