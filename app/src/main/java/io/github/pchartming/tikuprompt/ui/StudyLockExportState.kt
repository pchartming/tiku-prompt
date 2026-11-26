package io.github.pchartming.tikuprompt.ui

import io.github.pchartming.tikuprompt.data.CourseCatalog
import io.github.pchartming.tikuprompt.export.StudyLockPackMetadata
import io.github.pchartming.tikuprompt.model.PromptForm

data class StudyLockDraft(
    val key: Long,
    val form: PromptForm,
    val titleOverride: String? = null,
    val json: String = "",
) {
    fun effectiveTitle(catalog: CourseCatalog): String =
        titleOverride?.takeIf { it.isNotBlank() }
            ?: StudyLockPackMetadata.defaultTitle(form, catalog)
}

data class StudyLockExportUiState(
    val drafts: List<StudyLockDraft> = emptyList(),
    val selectedKey: Long? = null,
    val nextKey: Long = 1,
) {
    val selectedDraft: StudyLockDraft?
        get() = drafts.firstOrNull { it.key == selectedKey } ?: drafts.firstOrNull()
}

data class StudyLockExportFile(
    val bytes: ByteArray,
    val suggestedName: String,
    val packCount: Int,
    val questionCount: Int,
)

class StudyLockCallbacks(
    val onAdd: () -> Unit,
    val onSelect: (Long) -> Unit,
    val onJsonChange: (Long, String) -> Unit,
    val onTitleChange: (Long, String) -> Unit,
    val onClear: (Long) -> Unit,
    val onRemove: (Long) -> Unit,
    val onPaste: (Long) -> Unit,
    val onPickFile: (Long) -> Unit,
    val onExport: (StudyLockExportFile) -> Unit,
)