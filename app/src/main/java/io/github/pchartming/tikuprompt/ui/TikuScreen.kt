package io.github.pchartming.tikuprompt.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pchartming.tikuprompt.ui.components.NotesSection
import io.github.pchartming.tikuprompt.ui.components.ProfileHelpDialog
import io.github.pchartming.tikuprompt.ui.components.PromptSection
import io.github.pchartming.tikuprompt.ui.components.ScopeSection
import io.github.pchartming.tikuprompt.ui.components.SettingsSection
import io.github.pchartming.tikuprompt.ui.components.TargetSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TikuScreen(viewModel: TikuViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val profile = state.selectedProfile
    val catalog = state.catalog

    fun copyPrompt() {
        val value = state.prompt
        if (value.isBlank()) return
        clipboard.setPrimaryClip(ClipData.newPlainText("题库提示词", value))
        scope.launch { snackbarHostState.showSnackbar("提示词已复制到剪贴板") }
    }

    fun sharePrompt() {
        val value = state.prompt
        if (value.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "题库生成提示词")
            putExtra(Intent.EXTRA_TEXT, value)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "分享提示词"))
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("没有找到可用的分享应用") }
        }
    }

    val formCallbacks = FormCallbacks(
        onGrade = viewModel::setGrade,
        onSemester = viewModel::setSemester,
        onSubject = viewModel::setSubject,
        onBook = viewModel::setBook,
        onScopeMode = viewModel::setScopeMode,
        onUnit = viewModel::setUnit,
        onLesson = viewModel::setLesson,
        onToggleType = viewModel::toggleQuestionType,
        onCount = viewModel::setCount,
        onDifficulty = viewModel::setDifficulty,
        onOptions = viewModel::setOptions,
        onLanguage = viewModel::setOutputLanguage,
        onExtraRequirement = viewModel::setExtraRequirement,
        onReferenceNotes = viewModel::setReferenceNotes,
    )
    val promptCallbacks = PromptCallbacks(
        onBeginEdit = viewModel::beginEditPrompt,
        onEditText = viewModel::updatePromptEdit,
        onFinishEdit = viewModel::finishEditPrompt,
        onReset = viewModel::resetPrompt,
        onCopy = { copyPrompt() },
        onShare = { sharePrompt() },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "题库提示词",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "多格式 AI 出题提示词 · 完全离线",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showHelp = true },
                        enabled = profile != null,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "格式说明",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        when {
            state.loading -> LoadingContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            state.loadError != null -> ErrorContent(
                message = state.loadError.orEmpty(),
                onRetry = viewModel::reload,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            catalog != null && profile != null -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 14.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    IntroCard()
                }
                item {
                    TargetSection(
                        profiles = state.profiles,
                        selectedId = profile.id,
                        onSelect = viewModel::selectTarget,
                        onShowHelp = { showHelp = true },
                    )
                }
                item {
                    ScopeSection(
                        catalog = catalog,
                        form = state.form,
                        callbacks = formCallbacks,
                    )
                }
                item {
                    SettingsSection(
                        profile = profile,
                        form = state.form,
                        callbacks = formCallbacks,
                    )
                }
                item {
                    NotesSection(
                        form = state.form,
                        callbacks = formCallbacks,
                    )
                }
                item {
                    PromptSection(
                        profile = profile,
                        prompt = state.prompt,
                        isEditing = state.isEditingPrompt,
                        isModified = state.isPromptModified,
                        errors = state.validationErrors,
                        callbacks = promptCallbacks,
                    )
                }
                item {
                    Text(
                        text = "课程目录随应用内置；提示词只在本机生成，不联网、不调用模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    )
                }
            }

            else -> ErrorContent(
                message = "应用数据不完整，请重试。",
                onRetry = viewModel::reload,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (showHelp && profile != null) {
        ProfileHelpDialog(
            profile = profile,
            onDismiss = { showHelp = false },
        )
    }
}

@Composable
private fun IntroCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "选范围 · 复制提示词 · 粘贴给 AI",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "出题范围在各目标 App 之间共享；切换格式只会更换提示词里的 JSON 规范。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "完全离线 · 无需 API Key · 不调用模型",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "正在读取课程目录…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "数据加载失败",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}