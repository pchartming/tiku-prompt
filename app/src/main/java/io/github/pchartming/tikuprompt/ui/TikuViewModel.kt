package io.github.pchartming.tikuprompt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.pchartming.tikuprompt.data.AppRepository
import io.github.pchartming.tikuprompt.data.CourseCatalog
import io.github.pchartming.tikuprompt.export.StudyLockZipExporter
import io.github.pchartming.tikuprompt.lan.LanAddress
import io.github.pchartming.tikuprompt.model.DifficultyIntent
import io.github.pchartming.tikuprompt.model.OutputLanguage
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.model.QuestionType
import io.github.pchartming.tikuprompt.model.ScopeMode
import io.github.pchartming.tikuprompt.prompt.ExportKind
import io.github.pchartming.tikuprompt.prompt.FormPlanner
import io.github.pchartming.tikuprompt.prompt.PromptCompiler
import io.github.pchartming.tikuprompt.prompt.PromptProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val catalog: CourseCatalog? = null,
    val profiles: List<PromptProfile> = emptyList(),
    val form: PromptForm = PromptForm(),
    val autoPrompt: String = "",
    val validationErrors: List<String> = emptyList(),
    val isEditingPrompt: Boolean = false,
    val promptOverride: String? = null,
    val studyLockExport: StudyLockExportUiState = StudyLockExportUiState(),
    val haloTvJson: String = "",
    val lanUrls: Map<String, String> = emptyMap(),
) {
    val selectedProfile: PromptProfile?
        get() = profiles.firstOrNull { it.id == form.targetId } ?: profiles.firstOrNull()

    val lanUrl: String
        get() = selectedProfile?.let { lanUrls[it.id] }.orEmpty()

    val prompt: String
        get() = promptOverride ?: autoPrompt

    val isPromptModified: Boolean
        get() = promptOverride != null && promptOverride != autoPrompt

    val canCopy: Boolean
        get() = prompt.isNotBlank()
}

class TikuViewModel(private val repository: AppRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = UiState(loading = true)
            runCatching { repository.loadData() }
                .onSuccess { data ->
                    val saved = repository.loadForm()
                    val profile = data.profiles.firstOrNull { it.id == saved?.targetId }
                        ?: data.profiles.firstOrNull()
                    if (profile == null) {
                        _uiState.value = UiState(
                            loading = false,
                            loadError = "没有可用的目标格式。",
                        )
                        return@onSuccess
                    }
                    val base = (saved ?: PromptForm()).copy(targetId = profile.id)
                    val form = FormPlanner.normalize(base, profile, data.catalog)
                    val result = PromptCompiler.build(form, profile, data.catalog)
                    _uiState.value = ensureStudyLockDraft(
                        UiState(
                            loading = false,
                            catalog = data.catalog,
                            profiles = data.profiles,
                            form = form,
                            autoPrompt = result.prompt.orEmpty(),
                            validationErrors = result.errors,
                            lanUrls = repository.loadLanUrls(
                                data.profiles.map { it.id },
                            ),
                        ),
                    )
                    repository.saveForm(form)
                }
                .onFailure { error ->
                    val detail = error.message?.takeIf { it.isNotBlank() }
                    _uiState.value = UiState(
                        loading = false,
                        loadError = "课程数据加载失败${if (detail != null) "：$detail" else "。"}",
                    )
                }
        }
    }

    fun selectTarget(targetId: String) {
        val current = _uiState.value
        if (current.form.targetId == targetId) return
        val target = current.profiles.firstOrNull { it.id == targetId } ?: return
        val catalog = current.catalog ?: return
        val form = FormPlanner.changeTarget(current.form, target, catalog)
        publish(current, form)
    }

    fun setGrade(grade: Int) = mutate { form, profile, catalog ->
        FormPlanner.changeGrade(form, grade, profile, catalog)
    }

    fun setSemester(semester: Int) = mutate { form, profile, catalog ->
        FormPlanner.changeSemester(form, semester, profile, catalog)
    }

    fun setSubject(subjectId: String) = mutate { form, profile, catalog ->
        FormPlanner.changeSubject(form, subjectId, profile, catalog)
    }

    fun setBook(bookKey: String) = mutate { form, profile, catalog ->
        FormPlanner.changeBook(form, bookKey, profile, catalog)
    }

    fun setScopeMode(mode: ScopeMode) = mutate { form, profile, catalog ->
        FormPlanner.changeScopeMode(form, mode, profile, catalog)
    }

    fun setUnit(unitNo: Int) = mutate { form, profile, catalog ->
        FormPlanner.changeUnit(form, unitNo, profile, catalog)
    }

    fun setLesson(lessonIndex: Int?) = mutate { form, profile, catalog ->
        FormPlanner.changeLesson(form, lessonIndex, profile, catalog)
    }

    fun toggleQuestionType(type: QuestionType) = mutate { form, profile, catalog ->
        FormPlanner.toggleType(form, type, profile, catalog)
    }

    fun setCount(count: Int) = mutate { form, profile, catalog ->
        FormPlanner.changeCount(form, count, profile, catalog)
    }

    fun setDifficulty(difficulty: DifficultyIntent) = mutate { form, profile, catalog ->
        FormPlanner.changeDifficulty(form, difficulty)
    }

    fun setOptions(options: Int) = mutate { form, profile, catalog ->
        FormPlanner.changeOptions(form, options, profile, catalog)
    }

    fun setOutputLanguage(language: OutputLanguage) = mutate { form, profile, catalog ->
        FormPlanner.changeLanguage(form, language)
    }

    fun beginEditPrompt() {
        val current = _uiState.value
        if (current.autoPrompt.isBlank()) return
        _uiState.value = current.copy(
            isEditingPrompt = true,
            promptOverride = current.prompt.ifBlank { current.autoPrompt },
        )
    }

    fun updatePromptEdit(text: String) {
        val current = _uiState.value
        if (!current.isEditingPrompt) return
        _uiState.value = current.copy(promptOverride = text)
    }

    fun finishEditPrompt() {
        val current = _uiState.value
        val override = current.promptOverride
            ?.takeIf { it.isNotBlank() && it != current.autoPrompt }
        _uiState.value = current.copy(
            isEditingPrompt = false,
            promptOverride = override,
        )
    }

    fun resetPrompt() {
        _uiState.value = _uiState.value.copy(
            isEditingPrompt = false,
            promptOverride = null,
        )
    }

    fun updateHaloTvJson(text: String) {
        _uiState.value = _uiState.value.copy(haloTvJson = text)
    }

    fun clearHaloTvJson() {
        _uiState.value = _uiState.value.copy(haloTvJson = "")
    }

    fun saveLanUrl(profileId: String, text: String) {
        val current = _uiState.value
        if (current.profiles.none { it.id == profileId }) return
        val value = text.take(LanAddress.MAX_LENGTH)
        val urls = if (value.isBlank()) {
            current.lanUrls - profileId
        } else {
            current.lanUrls + (profileId to value)
        }
        _uiState.value = current.copy(lanUrls = urls)
        if (value.isBlank()) {
            repository.clearLanUrl(profileId)
        } else {
            repository.saveLanUrl(profileId, value)
        }
    }

    fun addStudyLockDraft() {
        val current = _uiState.value
        val profile = current.selectedProfile ?: return
        if (profile.exportKind != ExportKind.STUDY_LOCK_ZIP) return
        val export = current.studyLockExport
        if (export.drafts.size >= StudyLockZipExporter.MAX_PACKS) return
        val key = export.nextKey
        val draft = StudyLockDraft(
            key = key,
            form = current.form.copy(targetId = profile.id),
        )
        _uiState.value = current.copy(
            studyLockExport = export.copy(
                drafts = export.drafts + draft,
                selectedKey = key,
                nextKey = key + 1,
            ),
        )
    }

    fun selectStudyLockDraft(key: Long) {
        val current = _uiState.value
        if (current.studyLockExport.drafts.none { it.key == key }) return
        _uiState.value = current.copy(
            studyLockExport = current.studyLockExport.copy(selectedKey = key),
        )
    }

    fun updateStudyLockJson(key: Long, text: String) {
        updateStudyLockDraft(key) { it.copy(json = text) }
    }

    fun updateStudyLockTitle(key: Long, title: String) {
        updateStudyLockDraft(key) { it.copy(titleOverride = title.ifBlank { null }) }
    }

    fun clearStudyLockJson(key: Long) {
        updateStudyLockDraft(key) { it.copy(json = "") }
    }

    fun removeStudyLockDraft(key: Long) {
        val current = _uiState.value
        val export = current.studyLockExport
        if (export.drafts.size <= 1) return
        val index = export.drafts.indexOfFirst { it.key == key }
        if (index < 0) return
        val drafts = export.drafts.filterNot { it.key == key }
        val selectedKey = if (export.selectedKey == key) {
            drafts.getOrNull(index.coerceAtMost(drafts.lastIndex))?.key ?: drafts.firstOrNull()?.key
        } else {
            export.selectedKey
        }
        _uiState.value = current.copy(
            studyLockExport = export.copy(drafts = drafts, selectedKey = selectedKey),
        )
    }

    private fun updateStudyLockDraft(key: Long, transform: (StudyLockDraft) -> StudyLockDraft) {
        val current = _uiState.value
        val export = current.studyLockExport
        if (export.drafts.none { it.key == key }) return
        _uiState.value = current.copy(
            studyLockExport = export.copy(
                drafts = export.drafts.map { if (it.key == key) transform(it) else it },
            ),
        )
    }

    private fun ensureStudyLockDraft(state: UiState): UiState {
        val profile = state.selectedProfile ?: return state
        if (profile.exportKind != ExportKind.STUDY_LOCK_ZIP) return state
        if (state.studyLockExport.drafts.isNotEmpty()) return state
        val key = state.studyLockExport.nextKey
        return state.copy(
            studyLockExport = state.studyLockExport.copy(
                drafts = listOf(
                    StudyLockDraft(
                        key = key,
                        form = state.form.copy(targetId = profile.id),
                    ),
                ),
                selectedKey = key,
                nextKey = key + 1,
            ),
        )
    }

    private fun syncEmptyStudyLockDrafts(
        state: UiState,
        form: PromptForm,
        profile: PromptProfile,
    ): UiState {
        if (profile.exportKind != ExportKind.STUDY_LOCK_ZIP) return state
        val export = state.studyLockExport
        if (export.drafts.isEmpty()) return state
        return state.copy(
            studyLockExport = export.copy(
                drafts = export.drafts.map { draft ->
                    if (draft.json.isBlank()) {
                        draft.copy(form = form.copy(targetId = profile.id))
                    } else {
                        draft
                    }
                },
            ),
        )
    }

    private fun mutate(transform: (PromptForm, PromptProfile, CourseCatalog) -> PromptForm) {
        val current = _uiState.value
        val profile = current.selectedProfile ?: return
        val catalog = current.catalog ?: return
        val nextForm = FormPlanner.normalize(
            transform(current.form, profile, catalog),
            profile,
            catalog,
        )
        publish(current, nextForm)
    }

    private fun publish(current: UiState, form: PromptForm) {
        val profile = current.profiles.firstOrNull { it.id == form.targetId } ?: return
        val catalog = current.catalog ?: return
        val result = PromptCompiler.build(form, profile, catalog)
        val next = current.copy(
            form = form,
            autoPrompt = result.prompt.orEmpty(),
            validationErrors = result.errors,
            isEditingPrompt = false,
            promptOverride = null,
        )
        _uiState.value = ensureStudyLockDraft(
            syncEmptyStudyLockDrafts(next, form, profile),
        )
        repository.saveForm(form)
    }

    class Factory(private val repository: AppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TikuViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return TikuViewModel(repository) as T
        }
    }
}