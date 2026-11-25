package io.github.pchartming.tikuprompt

import io.github.pchartming.tikuprompt.data.CourseCatalog
import io.github.pchartming.tikuprompt.data.CurriculumDto
import io.github.pchartming.tikuprompt.data.UnitCatalogDto
import io.github.pchartming.tikuprompt.model.DifficultyIntent
import io.github.pchartming.tikuprompt.model.OutputLanguage
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.model.QuestionType
import io.github.pchartming.tikuprompt.model.ScopeMode
import io.github.pchartming.tikuprompt.prompt.DifficultyStyle
import io.github.pchartming.tikuprompt.prompt.FormPlanner
import io.github.pchartming.tikuprompt.prompt.PromptCompiler
import io.github.pchartming.tikuprompt.prompt.PromptProfile
import io.github.pchartming.tikuprompt.prompt.TargetProfiles
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private val testJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun assetText(path: String): String {
    val direct = File("src/main/assets/$path")
    if (direct.isFile) return direct.readText()
    val module = File("app/src/main/assets/$path")
    if (module.isFile) return module.readText()
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        val candidate = File(dir, "app/src/main/assets/$path")
        if (candidate.isFile) return candidate.readText()
        dir = dir.parentFile
    }
    error("Cannot find test asset $path from ${File(".").absolutePath}")
}

private val testCatalog: CourseCatalog by lazy {
    val curriculum = testJson.decodeFromString<CurriculumDto>(
        assetText("quiz/curriculum.json"),
    )
    val catalog = testJson.decodeFromString<UnitCatalogDto>(
        assetText("quiz/unit_catalog.json"),
    )
    CourseCatalog.from(curriculum, catalog)
}

private val testProfiles: List<PromptProfile> by lazy {
    TargetProfiles.load { name -> assetText("prompts/$name") }
}

private fun profile(id: String): PromptProfile =
    testProfiles.first { it.id == id }

private val plan: PromptProfile get() = profile("planyourtv")
private val study: PromptProfile get() = profile("studyontv")
private val studylock: PromptProfile get() = profile("studylock")

class PromptPipelineTest {

    @Test
    fun `bundled course data and format specs are complete`() {
        assertEquals(410, testCatalog.books.size)
        assertEquals(2870, testCatalog.books.sumOf { it.units.size })
        assertEquals(8973, testCatalog.books.sumOf { book ->
            book.units.sumOf { it.lessons.size }
        })
        assertEquals(
            testCatalog.books.size,
            testCatalog.books.map { it.key }.distinct().size,
        )
        assertEquals(9, testCatalog.grades.size)

        assertEquals(3, testProfiles.size)
        assertEquals(
            listOf("studyontv", "planyourtv", "studylock"),
            testProfiles.map { it.id },
        )
        testProfiles.forEach { target ->
            assertTrue(target.id, target.spec.isNotBlank())
            assertTrue(target.id, target.spec.trimEnd().endsWith("出题要求："))
        }
        assertEquals(
            listOf(QuestionType.SINGLE, QuestionType.MULTIPLE),
            plan.supportedTypes,
        )
        assertEquals(5, study.supportedTypes.size)
        assertEquals(listOf(QuestionType.SINGLE), studylock.supportedTypes)
        assertEquals(4, studylock.fixedOptionCount)
        assertEquals(5000, studylock.maxCount)
        assertFalse(studylock.emitTypeIds)
        assertEquals(DifficultyStyle.CONTENT_ONLY, studylock.difficultyStyle)
        assertTrue(studylock.spec.contains("{\"items\""))
        assertTrue(studylock.help.importSteps.isNotEmpty())
    }

    @Test
    fun `default PlanYourTV form normalizes and compiles`() {
        val form = FormPlanner.normalize(
            PromptForm(targetId = plan.id),
            plan,
            testCatalog,
        )

        assertEquals(plan.id, form.targetId)
        assertEquals(QuestionType.SINGLE, form.questionTypes.single())
        assertEquals(20, form.count)
        assertTrue(form.bookKey.isNotBlank())
        assertNotNull(testCatalog.findBook(form.bookKey))

        val result = PromptCompiler.build(form, plan, testCatalog)
        assertEquals(emptyList<String>(), result.errors)
        val prompt = requireNotNull(result.prompt)
        assertTrue(prompt.startsWith(plan.spec.trim()))
        assertTrue(prompt.contains("学段/年级："))
        assertTrue(prompt.contains("学期："))
        assertTrue(prompt.contains("科目：数学"))
        assertTrue(prompt.contains("教材："))
        assertTrue(prompt.contains("范围：第"))
        assertTrue(prompt.contains("题型：单选（single）"))
        assertTrue(prompt.contains("题量：20 道"))
        assertTrue(prompt.contains("每题选项数：4 个"))
        assertTrue(prompt.contains("输出语言：中文（简体）"))
        assertTrue(prompt.contains("额外要求：无"))
    }

    @Test
    fun `switching target keeps shared scope and drops unsupported types`() {
        val planForm = FormPlanner.normalize(
            PromptForm(
                targetId = plan.id,
                questionTypes = setOf(QuestionType.SINGLE, QuestionType.MULTIPLE),
                count = 50,
                scopeMode = ScopeMode.FINAL,
            ),
            plan,
            testCatalog,
        )
        val studyForm = FormPlanner.changeTarget(
            planForm.copy(
                questionTypes = setOf(
                    QuestionType.SINGLE,
                    QuestionType.MULTIPLE,
                    QuestionType.BOOLEAN,
                ),
            ),
            study,
            testCatalog,
        )

        assertEquals(study.id, studyForm.targetId)
        assertEquals(planForm.grade, studyForm.grade)
        assertEquals(planForm.semester, studyForm.semester)
        assertEquals(planForm.subjectId, studyForm.subjectId)
        assertEquals(planForm.bookKey, studyForm.bookKey)
        assertEquals(ScopeMode.FINAL, studyForm.scopeMode)
        assertEquals(50, studyForm.count)
        assertTrue(QuestionType.BOOLEAN in studyForm.questionTypes)

        val backToPlan = FormPlanner.changeTarget(studyForm, plan, testCatalog)
        assertEquals(plan.id, backToPlan.targetId)
        assertEquals(
            setOf(QuestionType.SINGLE, QuestionType.MULTIPLE),
            backToPlan.questionTypes,
        )
        assertEquals(50, backToPlan.count)
        assertEquals(planForm.bookKey, backToPlan.bookKey)

        val toStudyLock = FormPlanner.changeTarget(studyForm, studylock, testCatalog)
        assertEquals(studylock.id, toStudyLock.targetId)
        assertEquals(ScopeMode.FINAL, toStudyLock.scopeMode)
        assertEquals(50, toStudyLock.count)
        assertEquals(setOf(QuestionType.SINGLE), toStudyLock.questionTypes)
        assertEquals(4, toStudyLock.optionsPerQuestion)
        assertEquals(planForm.bookKey, toStudyLock.bookKey)
    }

    @Test
    fun `StudyOnTV form carries all selected types notes and difficulty`() {
        val types = setOf(
            QuestionType.SINGLE,
            QuestionType.MULTIPLE,
            QuestionType.BOOLEAN,
            QuestionType.TEXT,
            QuestionType.SUBJECTIVE,
        )
        val form = FormPlanner.normalize(
            PromptForm(
                targetId = study.id,
                questionTypes = types,
                count = 10,
                difficulty = DifficultyIntent.AUTO,
                optionsPerQuestion = 6,
                extraRequirement = "只考本学期学过的内容",
                referenceNotes = "三角形内角和是 180 度。",
            ),
            study,
            testCatalog,
        )

        assertEquals(types, form.questionTypes)
        val result = PromptCompiler.build(form, study, testCatalog)
        assertEquals(emptyList<String>(), result.errors)
        val prompt = requireNotNull(result.prompt)

        assertTrue(prompt.startsWith(study.spec.trim()))
        assertTrue(prompt.contains("题型：单选（single）、多选（multiple）、判断（boolean）、填空（text）、主观（subjective）；题量允许时每种题型至少 1 道"))
        assertTrue(prompt.contains("难度：由你按题目实际难度判断（difficulty 使用 1–5 的整数）"))
        assertTrue(prompt.contains("每题选项数：6 个"))
        assertTrue(prompt.contains("额外要求：只考本学期学过的内容"))
        assertTrue(prompt.contains("【补充资料开始】"))
        assertTrue(prompt.contains("三角形内角和是 180 度。"))
        assertTrue(prompt.contains("【补充资料结束】"))
    }

    @Test
    fun `StudyLock normalizes to fixed four-option single choice without format fields`() {
        val form = FormPlanner.normalize(
            PromptForm(
                targetId = studylock.id,
                questionTypes = setOf(
                    QuestionType.SINGLE,
                    QuestionType.MULTIPLE,
                    QuestionType.BOOLEAN,
                    QuestionType.TEXT,
                    QuestionType.SUBJECTIVE,
                ),
                count = 30,
                difficulty = DifficultyIntent.HARD,
                optionsPerQuestion = 6,
                extraRequirement = "只考本单元",
            ),
            studylock,
            testCatalog,
        )

        assertEquals(setOf(QuestionType.SINGLE), form.questionTypes)
        assertEquals(4, form.optionsPerQuestion)

        val result = PromptCompiler.build(form, studylock, testCatalog)
        assertEquals(emptyList<String>(), result.errors)
        val prompt = requireNotNull(result.prompt)
        assertTrue(prompt.startsWith(studylock.spec.trim()))
        assertTrue(prompt.contains("题型：单选"))
        assertFalse(prompt.contains("（single）"))
        assertTrue(prompt.contains("题量：30 道"))
        assertTrue(prompt.contains("难度：较难（只影响题目内容，不输出 difficulty 字段）"))
        assertTrue(prompt.contains("每题选项数：4 个"))
        assertTrue(prompt.contains("额外要求：只考本单元"))
        assertFalse(prompt.contains("difficulty 使用"))

        val invalidOptions = PromptCompiler.build(
            form.copy(optionsPerQuestion = 3),
            studylock,
            testCatalog,
        )
        assertNull(invalidOptions.prompt)
        assertTrue(invalidOptions.errors.any { it.contains("固定") })
    }

    @Test
    fun `final and review include the book unit list while midterm does not`() {
        val base = FormPlanner.normalize(
            PromptForm(targetId = plan.id),
            plan,
            testCatalog,
        )
        val finalPrompt = requireNotNull(
            PromptCompiler.build(
                FormPlanner.normalize(
                    base.copy(scopeMode = ScopeMode.FINAL),
                    plan,
                    testCatalog,
                ),
                plan,
                testCatalog,
            ).prompt,
        )
        assertTrue(finalPrompt.contains("范围：本学期期末考试范围"))
        assertTrue(finalPrompt.contains("本册单元目录"))
        assertTrue(finalPrompt.contains("第1单元"))

        val midtermPrompt = requireNotNull(
            PromptCompiler.build(
                FormPlanner.normalize(
                    base.copy(scopeMode = ScopeMode.MIDTERM),
                    plan,
                    testCatalog,
                ),
                plan,
                testCatalog,
            ).prompt,
        )
        assertTrue(midtermPrompt.contains("范围：本学期期中考试范围"))
        assertFalse(midtermPrompt.contains("本册单元目录"))
    }

    @Test
    fun `selecting a lesson focuses the prompt on that lesson`() {
        val base = FormPlanner.normalize(
            PromptForm(targetId = plan.id),
            plan,
            testCatalog,
        )
        val book = requireNotNull(testCatalog.findBook(base.bookKey))
        val unit = book.units.first { it.lessons.isNotEmpty() }
        val withUnit = FormPlanner.normalize(
            base.copy(unitNo = unit.no),
            plan,
            testCatalog,
        )
        val withLesson = FormPlanner.normalize(
            withUnit.copy(lessonIndex = 0),
            plan,
            testCatalog,
        )
        assertEquals(0, withLesson.lessonIndex)

        val prompt = requireNotNull(
            PromptCompiler.build(withLesson, plan, testCatalog).prompt,
        )
        assertTrue(prompt.contains("本课重点：${unit.lessons[0]}"))
        assertFalse(prompt.contains("本单元课时："))
    }

    @Test
    fun `compiler rejects stale scope and invalid settings`() {
        val base = FormPlanner.normalize(
            PromptForm(targetId = plan.id),
            plan,
            testCatalog,
        )

        val missingBook = PromptCompiler.build(
            base.copy(bookKey = "missing-book"),
            plan,
            testCatalog,
        )
        assertNull(missingBook.prompt)
        assertTrue(missingBook.errors.any { it.contains("教材") })

        val badCount = PromptCompiler.build(
            base.copy(count = 0),
            plan,
            testCatalog,
        )
        assertNull(badCount.prompt)
        assertTrue(badCount.errors.any { it.contains("题量") })

        val badOptions = PromptCompiler.build(
            base.copy(optionsPerQuestion = 7),
            plan,
            testCatalog,
        )
        assertNull(badOptions.prompt)
        assertTrue(badOptions.errors.any { it.contains("选项数") })

        val noTypes = PromptCompiler.build(
            base.copy(questionTypes = emptySet()),
            plan,
            testCatalog,
        )
        assertNull(noTypes.prompt)
        assertTrue(noTypes.errors.any { it.contains("题型") })
    }

    @Test
    fun `form planner never leaves zero question types`() {
        val base = FormPlanner.normalize(
            PromptForm(targetId = plan.id),
            plan,
            testCatalog,
        )
        val toggled = FormPlanner.toggleType(base, QuestionType.SINGLE, plan, testCatalog)
        assertEquals(setOf(QuestionType.SINGLE), toggled.questionTypes)

        val multi = FormPlanner.toggleType(toggled, QuestionType.MULTIPLE, plan, testCatalog)
        assertEquals(
            setOf(QuestionType.SINGLE, QuestionType.MULTIPLE),
            multi.questionTypes,
        )
    }

    @Test
    fun `prompt form survives JSON persistence`() {
        val form = PromptForm(
            targetId = study.id,
            grade = 7,
            semester = 2,
            subjectId = "ENGLISH",
            bookKey = "7|ENGLISH|PEP|下册|",
            scopeMode = ScopeMode.REVIEW,
            unitNo = 2,
            lessonIndex = 1,
            questionTypes = setOf(
                QuestionType.SINGLE,
                QuestionType.MULTIPLE,
                QuestionType.BOOLEAN,
                QuestionType.TEXT,
                QuestionType.SUBJECTIVE,
            ),
            count = 30,
            difficulty = DifficultyIntent.HARD,
            optionsPerQuestion = 6,
            outputLanguage = OutputLanguage.EN,
            extraRequirement = "Mixed practice",
            referenceNotes = "Unit notes",
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(form)
        val decoded = json.decodeFromString<PromptForm>(encoded)
        assertEquals(form, decoded)
    }
}