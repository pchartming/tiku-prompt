package io.github.pchartming.tikuprompt.prompt

import io.github.pchartming.tikuprompt.data.CourseCatalog
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.model.ScopeMode

/**
 * Keeps the form internally consistent when one selector changes. Pure code so
 * the transitions can be unit tested without an Android runtime.
 */
object FormPlanner {

    fun normalize(
        form: PromptForm,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm {
        val grades = catalog.grades
        if (grades.isEmpty()) {
            return form.copy(targetId = profile.id)
        }
        val grade = form.grade.takeIf { value -> grades.any { it.no == value } }
            ?: grades.first().no

        val subjects = catalog.subjectsFor(grade)
        val subjectId = form.subjectId.takeIf { value -> subjects.any { it.id == value } }
            ?: subjects.firstOrNull()?.id.orEmpty()

        val semesters = catalog.semestersFor(grade, subjectId)
        val semester = form.semester.takeIf { it in semesters }
            ?: semesters.firstOrNull()
            ?: 1

        val previous = catalog.findBook(form.bookKey)
        val book = catalog.booksFor(grade, semester, subjectId)
            .firstOrNull { it.key == form.bookKey }
            ?: catalog.pickBook(grade, semester, subjectId, previous)

        val units = book?.units.orEmpty()
        val unitNo = form.unitNo.takeIf { no -> units.any { it.no == no } }
            ?: units.firstOrNull()?.no
            ?: 1
        val unit = units.firstOrNull { it.no == unitNo }
        val lessonIndex = form.lessonIndex?.takeIf { index ->
            unit != null && index in unit.lessons.indices
        }

        val supported = profile.supportedTypes.toSet()
        val types = form.questionTypes.filter { it in supported }.toSet()
            .ifEmpty { profile.defaultTypes.filter { it in supported }.toSet() }
            .ifEmpty { supported.take(1).toSet() }

        val count = form.count.takeIf { it in profile.countChoices }
            ?: profile.defaultCount
        val options = form.optionsPerQuestion.takeIf { it in profile.optionChoices }
            ?: profile.defaultOptions

        return form.copy(
            targetId = profile.id,
            grade = grade,
            semester = semester,
            subjectId = subjectId,
            bookKey = book?.key.orEmpty(),
            scopeMode = form.scopeMode,
            unitNo = unitNo,
            lessonIndex = lessonIndex,
            questionTypes = types,
            count = count,
            optionsPerQuestion = options,
        )
    }

    fun changeGrade(
        form: PromptForm,
        grade: Int,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm = normalize(
        form.copy(grade = grade, unitNo = 0, lessonIndex = null),
        profile,
        catalog,
    )

    fun changeSemester(
        form: PromptForm,
        semester: Int,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm = normalize(
        form.copy(semester = semester, unitNo = 0, lessonIndex = null),
        profile,
        catalog,
    )

    fun changeSubject(
        form: PromptForm,
        subjectId: String,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm = normalize(
        form.copy(
            subjectId = subjectId,
            bookKey = "",
            unitNo = 0,
            lessonIndex = null,
        ),
        profile,
        catalog,
    )

    fun changeBook(
        form: PromptForm,
        bookKey: String,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm = normalize(
        form.copy(bookKey = bookKey, unitNo = 0, lessonIndex = null),
        profile,
        catalog,
    )

    fun changeUnit(
        form: PromptForm,
        unitNo: Int,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm = normalize(
        form.copy(unitNo = unitNo, lessonIndex = null),
        profile,
        catalog,
    )

    fun changeLesson(
        form: PromptForm,
        lessonIndex: Int?,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm = normalize(
        form.copy(lessonIndex = lessonIndex),
        profile,
        catalog,
    )

    fun changeScopeMode(
        form: PromptForm,
        mode: ScopeMode,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm = normalize(form.copy(scopeMode = mode), profile, catalog)

    fun toggleType(
        form: PromptForm,
        type: io.github.pchartming.tikuprompt.model.QuestionType,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm {
        if (!profile.supports(type)) return form
        val current = form.questionTypes
        val next = if (type in current) current - type else current + type
        if (next.isEmpty()) return form
        return normalize(form.copy(questionTypes = next), profile, catalog)
    }

    fun changeCount(
        form: PromptForm,
        count: Int,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm {
        if (count !in profile.countChoices) return form
        return normalize(form.copy(count = count), profile, catalog)
    }

    fun changeDifficulty(
        form: PromptForm,
        difficulty: io.github.pchartming.tikuprompt.model.DifficultyIntent,
    ): PromptForm = form.copy(difficulty = difficulty)

    fun changeOptions(
        form: PromptForm,
        options: Int,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm {
        if (options !in profile.optionChoices) return form
        return normalize(form.copy(optionsPerQuestion = options), profile, catalog)
    }

    fun changeLanguage(
        form: PromptForm,
        language: io.github.pchartming.tikuprompt.model.OutputLanguage,
    ): PromptForm = form.copy(outputLanguage = language)

    fun changeTarget(
        form: PromptForm,
        profile: PromptProfile,
        catalog: CourseCatalog,
    ): PromptForm = normalize(form.copy(targetId = profile.id), profile, catalog)
}