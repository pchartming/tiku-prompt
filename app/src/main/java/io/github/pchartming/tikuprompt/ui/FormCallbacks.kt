package io.github.pchartming.tikuprompt.ui

import io.github.pchartming.tikuprompt.model.DifficultyIntent
import io.github.pchartming.tikuprompt.model.OutputLanguage
import io.github.pchartming.tikuprompt.model.QuestionType
import io.github.pchartming.tikuprompt.model.ScopeMode

class FormCallbacks(
    val onGrade: (Int) -> Unit,
    val onSemester: (Int) -> Unit,
    val onSubject: (String) -> Unit,
    val onBook: (String) -> Unit,
    val onScopeMode: (ScopeMode) -> Unit,
    val onUnit: (Int) -> Unit,
    val onLesson: (Int?) -> Unit,
    val onToggleType: (QuestionType) -> Unit,
    val onCount: (Int) -> Unit,
    val onDifficulty: (DifficultyIntent) -> Unit,
    val onOptions: (Int) -> Unit,
    val onLanguage: (OutputLanguage) -> Unit,
    val onExtraRequirement: (String) -> Unit,
    val onReferenceNotes: (String) -> Unit,
)

class PromptCallbacks(
    val onBeginEdit: () -> Unit,
    val onEditText: (String) -> Unit,
    val onFinishEdit: () -> Unit,
    val onReset: () -> Unit,
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
)