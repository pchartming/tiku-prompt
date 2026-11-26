package io.github.pchartming.tikuprompt.ui

import io.github.pchartming.tikuprompt.export.HaloTvQuizFile

class HaloTvCallbacks(
    val onJsonChange: (String) -> Unit,
    val onClear: () -> Unit,
    val onPaste: () -> Unit,
    val onPickFile: () -> Unit,
    val onExport: (HaloTvQuizFile) -> Unit,
)