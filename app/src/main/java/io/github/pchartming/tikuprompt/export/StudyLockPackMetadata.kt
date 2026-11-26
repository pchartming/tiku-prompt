package io.github.pchartming.tikuprompt.export

import io.github.pchartming.tikuprompt.data.CourseCatalog
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.model.ScopeMode
import java.security.MessageDigest

/**
 * Builds the metadata that goes into index.json. The kit does not try to
 * invent a category for subjects StudyLock does not publish; it keeps the
 * subject in the visible title and omits the numeric field in that case.
 */
object StudyLockPackMetadata {
    private val subjectCodes = mapOf(
        "CHINESE" to 0,
        "MATH" to 1,
        "ENGLISH" to 2,
        "PHYSICS" to 3,
        "CHEMISTRY" to 4,
        "BIOLOGY" to 5,
        "HISTORY" to 6,
        "GEOGRAPHY" to 7,
        "POLITICS" to 8,
    )

    fun subjectCode(subjectId: String): Int? = subjectCodes[subjectId]

    fun defaultTitle(form: PromptForm, catalog: CourseCatalog): String {
        val grade = catalog.gradeLabel(form.grade)
        val subject = catalog.subjectLabel(form.subjectId)
        return "$grade$subject · ${scopeSummary(form, catalog)}"
    }

    fun contextSummary(form: PromptForm, catalog: CourseCatalog): String {
        val grade = catalog.gradeLabel(form.grade)
        val subject = catalog.subjectLabel(form.subjectId)
        return "$grade · $subject · ${scopeSummary(form, catalog)}"
    }

    fun scopeSummary(form: PromptForm, catalog: CourseCatalog): String {
        val book = catalog.findBook(form.bookKey)
        val unit = book?.units?.firstOrNull { it.no == form.unitNo }
        return when (form.scopeMode) {
            ScopeMode.UNIT -> {
                val unitLabel = unit?.pickerLabel ?: "第${form.unitNo}单元"
                val lesson = form.lessonIndex?.let { unit?.lessons?.getOrNull(it) }
                if (lesson.isNullOrBlank()) unitLabel else "$unitLabel · $lesson"
            }

            ScopeMode.MIDTERM -> "期中"
            ScopeMode.FINAL -> "期末"
            ScopeMode.REVIEW -> "总复习"
        }
    }

    fun from(
        form: PromptForm,
        catalog: CourseCatalog,
        title: String,
    ): StudyLockPackMeta {
        val effectiveTitle = title.trim().ifBlank { defaultTitle(form, catalog) }
        return StudyLockPackMeta(
            id = packId(form, effectiveTitle),
            title = effectiveTitle,
            grade = form.grade,
            subject = subjectCode(form.subjectId),
            enabled = true,
        )
    }

    /**
     * Stable across repeated exports with the same scope and title, so
     * uploading a corrected batch updates the same StudyLock pack instead of
     * creating a duplicate.
     */
    fun packId(form: PromptForm, title: String): String {
        val key = listOf(
            "studylock",
            form.grade.toString(),
            form.subjectId,
            form.bookKey,
            form.scopeMode.name,
            form.unitNo.toString(),
            form.lessonIndex?.toString() ?: "all",
            title.trim(),
        ).joinToString("|")
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        val suffix = digest.take(6).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        return "tiku_$suffix"
    }
}