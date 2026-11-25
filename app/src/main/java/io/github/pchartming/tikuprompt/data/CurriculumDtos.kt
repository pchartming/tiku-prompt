package io.github.pchartming.tikuprompt.data

import kotlinx.serialization.Serializable

@Serializable
data class LabelsDto(
    val zh: String = "",
    val en: String = "",
)

@Serializable
data class CurriculumGradeDto(
    val no: Int = 0,
    val labels: LabelsDto = LabelsDto(),
)

@Serializable
data class CurriculumSubjectDto(
    val id: String = "",
    val labels: LabelsDto = LabelsDto(),
)

@Serializable
data class CurriculumDto(
    val grades: List<CurriculumGradeDto> = emptyList(),
    val subjects: List<CurriculumSubjectDto> = emptyList(),
)

@Serializable
data class CatalogUnitDto(
    val no: Int = 0,
    val title: String = "",
    val kind: String = "",
    val lessons: List<String> = emptyList(),
)

@Serializable
data class CatalogBookDto(
    val grade: Int = 0,
    val semesters: List<Int> = emptyList(),
    val subject: String = "",
    val textbook: String = "",
    val textbookName: String = "",
    val volume: String = "",
    val editionType: String = "",
    val units: List<CatalogUnitDto> = emptyList(),
)

@Serializable
data class UnitCatalogDto(
    val books: List<CatalogBookDto> = emptyList(),
)