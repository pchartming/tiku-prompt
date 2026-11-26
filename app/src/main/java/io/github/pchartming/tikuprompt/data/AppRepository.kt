package io.github.pchartming.tikuprompt.data

import android.content.Context
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.prompt.PromptProfile
import io.github.pchartming.tikuprompt.prompt.TargetProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

data class AppData(
    val catalog: CourseCatalog,
    val profiles: List<PromptProfile>,
)

class AppRepository(context: Context) {
    private val assets = context.applicationContext.assets
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dataJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val formJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun loadData(): AppData = withContext(Dispatchers.IO) {
        val curriculum = dataJson.decodeFromString<CurriculumDto>(
            readAsset("quiz/curriculum.json"),
        )
        val catalogDto = dataJson.decodeFromString<UnitCatalogDto>(
            readAsset("quiz/unit_catalog.json"),
        )
        val catalog = CourseCatalog.from(curriculum, catalogDto)
        require(catalog.books.isNotEmpty()) { "课程目录为空" }
        val profiles = TargetProfiles.load { name ->
            readAsset("prompts/$name")
        }
        require(profiles.all { it.spec.isNotBlank() }) { "提示词规范为空" }
        AppData(catalog = catalog, profiles = profiles)
    }

    fun loadForm(): PromptForm? {
        val raw = prefs.getString(KEY_FORM, null) ?: return null
        return runCatching {
            formJson.decodeFromString<PromptForm>(raw)
        }.getOrNull()
    }

    fun saveForm(form: PromptForm) {
        val raw = runCatching { formJson.encodeToString(form) }.getOrNull() ?: return
        prefs.edit().putString(KEY_FORM, raw).apply()
    }

    fun loadLanUrls(profileIds: Collection<String>): Map<String, String> =
        profileIds.mapNotNull { id ->
            prefs.getString(lanUrlKey(id), null)
                ?.takeIf { it.isNotBlank() }
                ?.let { url -> id to url }
        }.toMap()

    fun saveLanUrl(profileId: String, url: String) {
        val editor = prefs.edit()
        if (url.isBlank()) {
            editor.remove(lanUrlKey(profileId))
        } else {
            editor.putString(lanUrlKey(profileId), url)
        }
        editor.apply()
    }

    fun clearLanUrl(profileId: String) {
        saveLanUrl(profileId, "")
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun lanUrlKey(profileId: String): String = "$KEY_LAN_URL_PREFIX$profileId"

    private companion object {
        const val PREFS_NAME = "tiku_prompt"
        const val KEY_FORM = "form_v1"
        const val KEY_LAN_URL_PREFIX = "lan_url_v1_"
    }
}