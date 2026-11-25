package io.github.pchartming.tikuprompt.data

import io.github.pchartming.tikuprompt.model.CourseBook
import io.github.pchartming.tikuprompt.model.CourseUnit
import io.github.pchartming.tikuprompt.model.GradeInfo
import io.github.pchartming.tikuprompt.model.SubjectInfo

/**
 * The course data is title-only: it knows book, unit and lesson names but not
 * the textbooks' body text. The optional "supplementary notes" field exists so
 * a parent can paste the actual material when accuracy matters.
 */
class CourseCatalog internal constructor(
    val books: List<CourseBook>,
    private val curriculum: CurriculumDto,
) {
    private val subjectsById = curriculum.subjects.associateBy { it.id }
    private val subjectOrder = curriculum.subjects.map { it.id }
    private val gradeLabels = curriculum.grades.associate { it.no to it.labels.zh }
    private val gradesByNo = curriculum.grades.map { it.no }.toSet()

    val grades: List<GradeInfo> = curriculum.grades
        .asSequence()
        .filter { it.no in gradesByNo }
        .filter { grade -> books.any { it.grade == grade.no } }
        .sortedBy { it.no }
        .map { GradeInfo(no = it.no, label = it.labels.zh.ifBlank { "${it.no}年级" }) }
        .toList()

    fun gradeLabel(grade: Int): String =
        gradeLabels[grade]?.takeIf { it.isNotBlank() } ?: "${grade}年级"

    fun subjectLabel(subjectId: String): String =
        subjectsById[subjectId]?.labels?.zh?.takeIf { it.isNotBlank() } ?: subjectId

    fun semesterLabel(semester: Int): String = if (semester == 2) "下册" else "上册"

    fun subjectsFor(grade: Int): List<SubjectInfo> {
        val available = books.asSequence()
            .filter { it.grade == grade }
            .map { it.subjectId }
            .toSet()
        return subjectOrder
            .filter { it in available }
            .map { SubjectInfo(id = it, label = subjectLabel(it)) }
    }

    fun booksFor(grade: Int, semester: Int, subjectId: String): List<CourseBook> =
        books.filter {
            it.grade == grade && it.subjectId == subjectId && semester in it.semesters
        }

    fun semestersFor(grade: Int, subjectId: String): List<Int> =
        books.asSequence()
            .filter { it.grade == grade && it.subjectId == subjectId }
            .flatMap { it.semesters.asSequence() }
            .distinct()
            .sorted()
            .toList()

    fun findBook(key: String): CourseBook? = books.firstOrNull { it.key == key }

    /**
     * Tries to keep the same edition and volume when the grade or semester
     * changes, then falls back to the edition each subject normally uses.
     */
    fun pickBook(
        grade: Int,
        semester: Int,
        subjectId: String,
        previous: CourseBook? = null,
    ): CourseBook? {
        val candidates = booksFor(grade, semester, subjectId)
        if (candidates.isEmpty()) return null
        if (previous != null) {
            candidates.firstOrNull {
                it.textbookId == previous.textbookId && it.volume == previous.volume
            }?.let { return it }
            candidates.firstOrNull { it.textbookId == previous.textbookId }?.let { return it }
        }
        val preferred = preferredTextbook(subjectId)
        candidates.firstOrNull { it.textbookId == preferred && it.volume == "上册" }?.let { return it }
        candidates.firstOrNull { it.textbookId == preferred }?.let { return it }
        return candidates.first()
    }

    fun preferredTextbook(subjectId: String): String = when (subjectId) {
        "CHINESE" -> "BUBIAN"
        "MATH" -> "RENJIAO"
        "ENGLISH" -> "PEP"
        "SCIENCE" -> "KEJIAO"
        "PHYSICS", "CHEMISTRY", "BIOLOGY", "GEOGRAPHY" -> "RENJIAO"
        "HISTORY", "POLITICS" -> "BUBIAN"
        else -> ""
    }

    fun findUnit(book: CourseBook, unitNo: Int): CourseUnit? =
        book.units.firstOrNull { it.no == unitNo }

    companion object {
        fun from(curriculum: CurriculumDto, catalog: UnitCatalogDto): CourseCatalog {
            val usedKeys = mutableSetOf<String>()
            val books = catalog.books.mapIndexedNotNull { index, dto ->
                if (dto.grade <= 0 || dto.subject.isBlank()) return@mapIndexedNotNull null
                val baseKey = listOf(
                    dto.grade,
                    dto.subject,
                    dto.textbook,
                    dto.volume,
                    dto.editionType,
                ).joinToString("|").ifBlank { "book|$index" }
                var key = baseKey
                var duplicate = 2
                while (!usedKeys.add(key)) {
                    key = "$baseKey#$duplicate"
                    duplicate += 1
                }
                val units = dto.units
                    .filter { it.no > 0 }
                    .map { unit ->
                        CourseUnit(
                            no = unit.no,
                            title = unit.title.trim(),
                            kind = unit.kind,
                            lessons = unit.lessons.map { it.trim() }.filter { it.isNotEmpty() },
                        )
                    }
                CourseBook(
                    key = key,
                    grade = dto.grade,
                    semesters = dto.semesters.distinct().sorted(),
                    subjectId = dto.subject,
                    textbookId = dto.textbook,
                    textbookName = dto.textbookName.ifBlank { dto.textbook },
                    volume = dto.volume,
                    editionType = dto.editionType,
                    units = units,
                )
            }
            return CourseCatalog(books = books, curriculum = curriculum)
        }
    }
}