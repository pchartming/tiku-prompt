package io.github.pchartming.tikuprompt.prompt

import io.github.pchartming.tikuprompt.model.QuestionType

enum class DifficultyStyle {
    EASY_MEDIUM_HARD,
    STARS_1_TO_5,
    CONTENT_ONLY,
}

enum class ExportKind {
    NONE,
    STUDY_LOCK_ZIP,
    HALO_TV_JSON,
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
    val exportKind: ExportKind = ExportKind.NONE,
    val exportHint: String = "",
    val lanUrlHint: String = "",
    val lanUrlPlaceholder: String = "",
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
        lanUrlHint = "在电视打开“家长中心”的局域网页面，按二维码或页面显示的完整地址填写；地址通常包含端口和配对码，以电视显示为准。",
        lanUrlPlaceholder = "http://192.168.x.x:<端口>/<路径>?<配对码>",
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
        lanUrlHint = "在电视打开家长控制页，地址通常为 http://电视IP:8099/；打开后按电视端设置输入家长密码。",
        lanUrlPlaceholder = "http://192.168.x.x:8099/",
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
                "在应用内把 AI 返回的 JSON 粘贴到“AI 结果 → 学习锁 zip”，可连续添加多个题库后一次生成 zip。",
                "没有使用应用内打包时，也可以把 JSON 保存为 UTF-8 无 BOM 的 .json，直接压缩为 zip，不要套文件夹。",
                "打开学习锁局域网家长控制端 → 题库 → 上传 zip；或把 zip 放到电视“下载”目录，在题库管理中选择导入本机 zip。",
                "应用生成的 zip 会附带 index.json 和每题库一个 JSON；导入后题库名称取 index.json 的 title。",
            ),
        ),
        emitTypeIds = false,
        exportKind = ExportKind.STUDY_LOCK_ZIP,
        exportHint = "",
        lanUrlHint = "在电视打开“局域网家长控制”，使用电视显示的完整地址；?t= 后是 8 位验证码，失效后重新复制并修改保存。",
        lanUrlPlaceholder = "http://192.168.x.x:8765/?t=12345678",
    )

    val halotv = PromptProfile(
        id = "halotv",
        name = "HaloTV",
        formatBadge = "3.6+",
        tagline = "家长出题 · 2–4 选一",
        specAssetName = "halotv_v1.txt",
        supportedTypes = listOf(QuestionType.SINGLE),
        defaultTypes = setOf(QuestionType.SINGLE),
        countChoices = listOf(10, 20, 30, 50, 80),
        defaultCount = 20,
        maxCount = 80,
        optionChoices = listOf(2, 3, 4),
        defaultOptions = 4,
        maxOptions = 4,
        difficultyStyle = DifficultyStyle.CONTENT_ONLY,
        help = ProfileHelp(
            summary = "输出 HaloTV 3.6+ 家长出题可导入的 JSON：根对象为 format 和 bank，只支持 2–4 选一单选题。",
            notes = listOf(
                "题干 1–56 个字符，选项 1–18 个字符；不要换行、控制字符或 emoji。",
                "每题 2–4 个选项，选项互不重复；题库名 1–20 个字符，同一台电视上不能重名。提示词默认写“AI 出题”，导入第二套前请修改 bank.name。",
                "只写 prompt、options、answer；answer 是 0 起始下标，只能有一个正确项。",
                "不要写 explanation、difficulty、type、id、version、items 等字段；HaloTV 没有解析或难度字段。",
                "单题库最多 80 题；所有题库合计最多 6 个、80 题；每局抽 5 题，不足 5 题时用内置题库补齐。",
                "文件保存为 UTF-8 无 BOM、不超过 256 KB；应用会在本机先校验格式，导入后仍请人工核对答案。",
            ),
            importSteps = listOf(
                "把 AI 返回的 JSON 粘贴到“AI 结果 → HaloTV 题库”，校验通过后导出 .json 文件。",
                "手机与电视连接同一局域网，打开 HaloTV 手机管理页 → 儿童与长辈 → 家长出题，按提示验证 PIN。",
                "选择“导入题库”，选中刚导出的 JSON 文件；每次导入会新建题库，不会追加到已有题库。",
                "在答题设置中选择该题库；每局 5 题，题库不足 5 题时会用内置题补齐。",
            ),
        ),
        emitTypeIds = false,
        exportKind = ExportKind.HALO_TV_JSON,
        exportHint = "",
        lanUrlHint = "在电视的“手机管理中心”按显示的地址或二维码进入；不同版本端口可能不同，以电视显示为准。",
        lanUrlPlaceholder = "http://192.168.x.x:<端口>/",
    )

    val definitions: List<PromptProfile> = listOf(studyontv, planyourtv, studylock, halotv)

    val default: PromptProfile get() = definitions.first()

    fun byId(id: String): PromptProfile? = definitions.firstOrNull { it.id == id }

    /**
     * Loads each profile's prompt spec through [readSpec]. Tests pass a file
     * reader; the app passes an asset reader.
     */
    fun load(readSpec: (String) -> String): List<PromptProfile> =
        definitions.map { it.copy(spec = readSpec(it.specAssetName)) }
}