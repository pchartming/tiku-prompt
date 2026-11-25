package io.github.pchartming.tikuprompt.prompt

import io.github.pchartming.tikuprompt.model.QuestionType

enum class DifficultyStyle {
    EASY_MEDIUM_HARD,
    STARS_1_TO_5,
    CONTENT_ONLY,
}

data class ProfileHelp(
    val summary: String,
    val notes: List<String>,
    val importSteps: List<String> = emptyList(),
)

/**
 * Format metadata. [spec] is loaded from assets at runtime, which keeps the
 * format contract out of Kotlin string literals and makes adding a new target a
 * data + metadata change rather than a compiler rewrite.
 */
data class PromptProfile(
    val id: String,
    val name: String,
    val formatBadge: String,
    val tagline: String,
    val specAssetName: String,
    val supportedTypes: List<QuestionType>,
    val defaultTypes: Set<QuestionType>,
    val countChoices: List<Int>,
    val defaultCount: Int,
    val maxCount: Int,
    val optionChoices: List<Int>,
    val defaultOptions: Int,
    val maxOptions: Int,
    val difficultyStyle: DifficultyStyle,
    val help: ProfileHelp,
    val emitTypeIds: Boolean = true,
    val exportHint: String = "",
    val spec: String = "",
) {
    fun supports(type: QuestionType): Boolean = type in supportedTypes

    val fixedOptionCount: Int? get() = optionChoices.singleOrNull()
}

object TargetProfiles {
    val planyourtv = PromptProfile(
        id = "planyourtv",
        name = "PlanYourTV",
        formatBadge = "v1",
        tagline = "学习机 · 单选 / 多选",
        specAssetName = "planyourtv_v1.txt",
        supportedTypes = listOf(QuestionType.SINGLE, QuestionType.MULTIPLE),
        defaultTypes = setOf(QuestionType.SINGLE),
        countChoices = listOf(10, 20, 30, 50),
        defaultCount = 20,
        maxCount = 200,
        optionChoices = listOf(4, 6),
        defaultOptions = 4,
        maxOptions = 6,
        difficultyStyle = DifficultyStyle.EASY_MEDIUM_HARD,
        help = ProfileHelp(
            summary = "输出 PlanYourTV 学习机可直接导入的 JSON：根对象为 version 1 与 items 数组，只支持单选题和多选题。",
            notes = listOf(
                "题干不超过 200 字；选项 2–6 个，每项不超过 60 字。",
                "解析不超过 600 字；难度只能写 easy、medium、hard。",
                "多选题至少 3 个选项、至少 2 个正确项，且不能把所有选项都标为正确。",
                "单次最多导入 200 题；答案下标从 0 开始。",
            ),
        ),
    )

    val studyontv = PromptProfile(
        id = "studyontv",
        name = "StudyOnTV",
        formatBadge = "v2",
        tagline = "题了个题 · 五种题型",
        specAssetName = "studyontv_v2.txt",
        supportedTypes = listOf(
            QuestionType.SINGLE,
            QuestionType.MULTIPLE,
            QuestionType.BOOLEAN,
            QuestionType.TEXT,
            QuestionType.SUBJECTIVE,
        ),
        defaultTypes = setOf(QuestionType.SINGLE),
        countChoices = listOf(10, 20, 30, 50),
        defaultCount = 20,
        maxCount = 50000,
        optionChoices = listOf(4, 6),
        defaultOptions = 4,
        maxOptions = 8,
        difficultyStyle = DifficultyStyle.STARS_1_TO_5,
        help = ProfileHelp(
            summary = "输出“题了个题”（StudyOnTV）题库导入格式：根对象为 version 2 与 items 数组，支持单选、多选、判断、填空和主观题。",
            notes = listOf(
                "题干最长 5000 字；单选、多选、判断需要 2–8 个非空选项。",
                "难度为 1–5 星；多选题至少 2 个正确项，且不能全选。",
                "填空答案按任意一项命中判对，应列出常见等价写法。",
                "导入是整批事务：任意一题不合格，整批回滚。单次 1–50000 题、单文件不超过 20 MB。",
                "本工具不生成图片题或音频题，也不会调用任何网络服务。",
            ),
        ),
    )

    val studylock = PromptProfile(
        id = "studylock",
        name = "学习锁",
        formatBadge = "v2.5+",
        tagline = "电视学习机 · 四选一",
        specAssetName = "studylock_v2.txt",
        supportedTypes = listOf(QuestionType.SINGLE),
        defaultTypes = setOf(QuestionType.SINGLE),
        countChoices = listOf(10, 20, 30, 50),
        defaultCount = 20,
        maxCount = 5000,
        optionChoices = listOf(4),
        defaultOptions = 4,
        maxOptions = 4,
        difficultyStyle = DifficultyStyle.CONTENT_ONLY,
        help = ProfileHelp(
            summary = "输出学习锁自定义题库 JSON：根对象只有 items，每题包含题干 s、四个选项 o、答案下标 a 和解析 e；只支持四选一单选题。",
            notes = listOf(
                "每题只写 s、o、a、e 四个字段；o 固定 4 个非空且互不重复的选项；a 为正确项下标，0=A、1=B、2=C、3=D。",
                "学习锁没有 difficulty、type 字段；年级、科目、教材只用于选题，不要写进题目 JSON。",
                "不要生成图片、表格、HTML、Markdown 或依赖插图的题目；需要换行时用 \\n。",
                "单包最多 5000 题；不合格的题会被逐题跳过，导入后仍需人工核对答案。",
                "本工具不生成 ps/po/pe 拼音字段；学习锁是第三方闭源软件，这里只提供非官方格式兼容。",
            ),
            importSteps = listOf(
                "把 AI 返回的内容保存为 .json 文件（UTF-8 无 BOM），不要保留 Markdown 代码围栏。",
                "直接压缩这个 .json 文件为 zip，不要把 JSON 再套一层文件夹；一个 zip 可放多个 JSON。",
                "打开学习锁局域网家长控制端 → 题库 → 上传 zip；或把 zip 放到电视“下载”目录，在题库管理中选择导入本机 zip。",
                "不写 index.json 也能导入；包名取文件名，导入后自动加 my_ 前缀。",
            ),
        ),
        emitTypeIds = false,
        exportHint = "保存为 UTF-8 无 BOM 的 .json，直接压成 zip 后在学习锁电视控制端题库页上传；不写 index.json 也能导入。",
    )

    val definitions: List<PromptProfile> = listOf(studyontv, planyourtv, studylock)

    val default: PromptProfile get() = definitions.first()

    fun byId(id: String): PromptProfile? = definitions.firstOrNull { it.id == id }

    /**
     * Loads each profile's prompt spec through [readSpec]. Tests pass a file
     * reader; the app passes an asset reader.
     */
    fun load(readSpec: (String) -> String): List<PromptProfile> =
        definitions.map { it.copy(spec = readSpec(it.specAssetName)) }
}