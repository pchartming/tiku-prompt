package io.github.pchartming.tikuprompt.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.MoreVert
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
import io.github.pchartming.tikuprompt.export.HaloTvQuizExporter
import io.github.pchartming.tikuprompt.export.HaloTvQuizFile
import io.github.pchartming.tikuprompt.export.StudyLockZipExporter
import io.github.pchartming.tikuprompt.lan.LanAddress
import io.github.pchartming.tikuprompt.prompt.ExportKind
import io.github.pchartming.tikuprompt.ui.components.AboutDialog
import io.github.pchartming.tikuprompt.ui.components.HaloTvExportSection
import io.github.pchartming.tikuprompt.ui.components.LanAccessSection
import io.github.pchartming.tikuprompt.ui.components.MoreSheet
import io.github.pchartming.tikuprompt.ui.components.ProfileHelpDialog
import io.github.pchartming.tikuprompt.ui.components.PromptSection
import io.github.pchartming.tikuprompt.ui.components.ScopeSection
import io.github.pchartming.tikuprompt.ui.components.SettingsSection
import io.github.pchartming.tikuprompt.ui.components.StudyLockExportSection
import io.github.pchartming.tikuprompt.ui.components.TargetSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var pendingZip by remember { mutableStateOf<StudyLockExportFile?>(null) }
    var pendingJsonDraftKey by remember { mutableStateOf<Long?>(null) }
    var pendingHaloTvFile by remember { mutableStateOf<HaloTvQuizFile?>(null) }

    val profile = state.selectedProfile
    val catalog = state.catalog

    val createZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val file = pendingZip
        pendingZip = null
        if (uri == null) {
            scope.launch { snackbarHostState.showSnackbar("已取消保存") }
        } else if (file != null) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(file.bytes)
                        } ?: error("无法打开输出文件")
                    }.isSuccess
                }
                snackbarHostState.showSnackbar(
                    if (success) {
                        "学习锁 zip 已保存（${file.packCount} 个题库，${file.questionCount} 道题）"
                    } else {
                        "保存失败，请重试"
                    },
                )
            }
        }
    }

    val openJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val key = pendingJsonDraftKey
        pendingJsonDraftKey = null
        if (uri != null && key != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    readTextLimited(context, uri, StudyLockZipExporter.MAX_JSON_BYTES)
                }
                if (text == null) {
                    snackbarHostState.showSnackbar("文件读取失败，或超过 8 MB")
                } else {
                    viewModel.updateStudyLockJson(key, text)
                }
            }
        }
    }

    val createHaloTvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val file = pendingHaloTvFile
        pendingHaloTvFile = null
        if (uri == null) {
            scope.launch { snackbarHostState.showSnackbar("已取消保存") }
        } else if (file != null) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(file.bytes)
                        } ?: error("无法打开输出文件")
                    }.isSuccess
                }
                snackbarHostState.showSnackbar(
                    if (success) {
                        "HaloTV 题库已保存（${file.questionCount} 道题）"
                    } else {
                        "保存失败，请重试"
                    },
                )
            }
        }
    }

    val openHaloTvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    readTextLimited(context, uri, HaloTvQuizExporter.MAX_JSON_BYTES)
                }
                if (text == null) {
                    snackbarHostState.showSnackbar("文件读取失败，或超过 256 KB")
                } else {
                    viewModel.updateHaloTvJson(text)
                }
            }
        }
    }

    fun pasteStudyLockJson(key: Long) {
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        if (text.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("剪贴板里没有文本") }
        } else {
            viewModel.updateStudyLockJson(key, text)
        }
    }

    fun pickStudyLockJson(key: Long) {
        pendingJsonDraftKey = key
        openJsonLauncher.launch(
            arrayOf("application/json", "text/plain", "application/octet-stream"),
        )
    }

    fun pasteHaloTvJson() {
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        if (text.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("剪贴板里没有文本") }
        } else {
            viewModel.updateHaloTvJson(text)
        }
    }

    fun pickHaloTvJson() {
        openHaloTvLauncher.launch(
            arrayOf("application/json", "text/plain", "application/octet-stream"),
        )
    }

    fun readLanClipboard(): String? {
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("剪贴板里没有文本") }
            return null
        }
        return text
    }

    fun openLanUrl(raw: String) {
        val result = LanAddress.normalize(raw)
        if (!result.isValid) {
            scope.launch {
                snackbarHostState.showSnackbar(result.error ?: "地址格式不正确")
            }
            return
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(result.normalizedUrl))
        runCatching {
            context.startActivity(view)
        }.onFailure {
            scope.launch {
                snackbarHostState.showSnackbar("没有找到可打开网页的应用")
            }
        }
    }

    val studyLockCallbacks = StudyLockCallbacks(
        onAdd = viewModel::addStudyLockDraft,
        onSelect = viewModel::selectStudyLockDraft,
        onJsonChange = viewModel::updateStudyLockJson,
        onTitleChange = viewModel::updateStudyLockTitle,
        onClear = viewModel::clearStudyLockJson,
        onRemove = viewModel::removeStudyLockDraft,
        onPaste = { key -> pasteStudyLockJson(key) },
        onPickFile = { key -> pickStudyLockJson(key) },
        onExport = { file ->
            pendingZip = file
            createZipLauncher.launch(file.suggestedName)
        },
    )

    val haloTvCallbacks = HaloTvCallbacks(
        onJsonChange = viewModel::updateHaloTvJson,
        onClear = viewModel::clearHaloTvJson,
        onPaste = { pasteHaloTvJson() },
        onPickFile = { pickHaloTvJson() },
        onExport = { file ->
            pendingHaloTvFile = file
            createHaloTvLauncher.launch(file.suggestedName)
        },
    )

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
                    IconButton(onClick = { showMore = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多",
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
                    LanAccessSection(
                        profile = profile,
                        savedUrl = state.lanUrl,
                        onSave = { value -> viewModel.saveLanUrl(profile.id, value) },
                        onPaste = { readLanClipboard() },
                        onOpen = { url -> openLanUrl(url) },
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
                    PromptSection(
                        profile = profile,
                        prompt = state.prompt,
                        isEditing = state.isEditingPrompt,
                        isModified = state.isPromptModified,
                        errors = state.validationErrors,
                        callbacks = promptCallbacks,
                        extraContent = if (profile.exportKind == ExportKind.STUDY_LOCK_ZIP) {
                            {
                                StudyLockExportSection(
                                    drafts = state.studyLockExport.drafts,
                                    selectedKey = state.studyLockExport.selectedKey,
                                    catalog = catalog,
                                    callbacks = studyLockCallbacks,
                                )
                            }
                        } else if (profile.exportKind == ExportKind.HALO_TV_JSON) {
                            {
                                HaloTvExportSection(
                                    json = state.haloTvJson,
                                    callbacks = haloTvCallbacks,
                                )
                            }
                        } else {
                            null
                        },
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

    if (showMore) {
        MoreSheet(
            profile = profile,
            onDismiss = { showMore = false },
            onShowHelp = {
                showMore = false
                showHelp = true
            },
            onShowAbout = {
                showMore = false
                showAbout = true
            },
        )
    }

    if (showHelp && profile != null) {
        ProfileHelpDialog(
            profile = profile,
            onDismiss = { showHelp = false },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

private fun readTextLimited(context: Context, uri: Uri, maxBytes: Int): String? {
    return try {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        var overLimit = false
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    overLimit = true
                    break
                }
                output.write(buffer, 0, read)
            }
        }
        if (overLimit) null else output.toByteArray().toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
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