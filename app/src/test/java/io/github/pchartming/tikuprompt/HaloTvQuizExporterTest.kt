package io.github.pchartming.tikuprompt

import io.github.pchartming.tikuprompt.export.HaloTvQuizExporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloTvQuizExporterTest {
    private val validJson = """
        {
          "format": "halotv-parent-quiz-v1",
          "bank": {
            "name": "AI 出题",
            "questions": [
              {
                "prompt": "1 + 1 等于几？",
                "options": ["1", "2", "3", "4"],
                "answer": 1
              },
              {
                "prompt": "2 + 2 等于几？",
                "options": ["4", "3"],
                "answer": 0
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `valid json becomes a canonical single-bank file`() {
        val result = HaloTvQuizExporter.parse(validJson)
        assertTrue(result.errors.toString(), result.isValid)
        val quiz = requireNotNull(result.quiz)
        assertEquals("AI 出题", quiz.name)
        assertEquals(2, quiz.questions.size)
        assertEquals(listOf("1", "2", "3", "4"), quiz.questions[0].options)
        assertEquals(1, quiz.questions[0].answer)
        assertTrue(result.warnings.any { it.contains("AI 出题") })

        val bytes = HaloTvQuizExporter.build(quiz)
        assertTrue(bytes.isNotEmpty())
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(
            setOf("format", "bank"),
            root.keys,
        )
        assertEquals(
            HaloTvQuizExporter.FORMAT,
            root.getValue("format").jsonPrimitive.content,
        )
        val bank = root.getValue("bank").jsonObject
        assertEquals(setOf("name", "questions"), bank.keys)
        val questions = bank.getValue("questions").jsonArray
        assertEquals(2, questions.size)
        assertEquals(
            setOf("prompt", "options", "answer"),
            questions[0].jsonObject.keys,
        )
        assertEquals(1, questions[0].jsonObject.getValue("answer").jsonPrimitive.content.toInt())
    }

    @Test
    fun `markdown fences and byte order mark are stripped`() {
        val fenced = "﻿```json\n$validJson\n```\n"
        val result = HaloTvQuizExporter.parse(fenced)
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(2, result.quiz?.questions?.size)
    }

    @Test
    fun `unknown fields are warned about and removed on export`() {
        val raw = """
            {
              "format": "halotv-parent-quiz-v1",
              "version": 2,
              "bank": {
                "name": "AI 出题",
                "description": "extra",
                "questions": [
                  {
                    "prompt": "题目一",
                    "options": ["甲", "乙"],
                    "answer": 0,
                    "explanation": "extra"
                  }
                ]
              }
            }
        """.trimIndent()
        val result = HaloTvQuizExporter.parse(raw)
        assertTrue(result.errors.toString(), result.isValid)
        assertTrue(result.warnings.toString(), result.warnings.isNotEmpty())
        val output = HaloTvQuizExporter.build(requireNotNull(result.quiz))
            .toString(Charsets.UTF_8)
        assertFalse(output.contains("explanation"))
        assertFalse(output.contains("description"))
        assertFalse(output.contains("version"))
    }

    @Test
    fun `wrong format and missing bank are rejected`() {
        val wrongFormat = HaloTvQuizExporter.parse(
            validJson.replace("halotv-parent-quiz-v1", "other-format"),
        )
        assertFalse(wrongFormat.isValid)
        assertTrue(wrongFormat.errors.any { it.contains("format") })

        val missingBank = HaloTvQuizExporter.parse("""{"format":"halotv-parent-quiz-v1"}""")
        assertFalse(missingBank.isValid)
        assertTrue(missingBank.errors.any { it.contains("bank") })

        val missingQuestions = HaloTvQuizExporter.parse(
            """{"format":"halotv-parent-quiz-v1","bank":{"name":"AI 出题"}}""",
        )
        assertFalse(missingQuestions.isValid)
        assertTrue(missingQuestions.errors.any { it.contains("questions") })
    }

    @Test
    fun `name prompt and option length limits are inclusive`() {
        val maxName = "名".repeat(HaloTvQuizExporter.MAX_BANK_NAME_LENGTH)
        val maxPrompt = "题".repeat(HaloTvQuizExporter.MAX_PROMPT_LENGTH)
        val maxOption = "选".repeat(HaloTvQuizExporter.MAX_OPTION_LENGTH)
        val atLimit = HaloTvQuizExporter.parse(
            quizJson(
                name = maxName,
                question = """{"prompt":"$maxPrompt","options":["$maxOption","乙"],"answer":0}""",
            ),
        )
        assertTrue(atLimit.errors.toString(), atLimit.isValid)

        val longName = HaloTvQuizExporter.parse(
            quizJson(name = "名".repeat(HaloTvQuizExporter.MAX_BANK_NAME_LENGTH + 1)),
        )
        assertFalse(longName.isValid)
        assertTrue(longName.errors.any { it.contains("name") })

        val longPrompt = HaloTvQuizExporter.parse(
            quizJson(
                question = """{"prompt":"${"题".repeat(HaloTvQuizExporter.MAX_PROMPT_LENGTH + 1)}","options":["甲","乙"],"answer":0}""",
            ),
        )
        assertFalse(longPrompt.isValid)
        assertTrue(longPrompt.errors.any { it.contains("prompt") })

        val longOption = HaloTvQuizExporter.parse(
            quizJson(
                question = """{"prompt":"题目","options":["${"选".repeat(HaloTvQuizExporter.MAX_OPTION_LENGTH + 1)}","乙"],"answer":0}""",
            ),
        )
        assertFalse(longOption.isValid)
        assertTrue(longOption.errors.any { it.contains("选项") })
    }

    @Test
    fun `question count accepts one through eighty and rejects zero or more`() {
        val one = HaloTvQuizExporter.parse(quizJson())
        assertTrue(one.errors.toString(), one.isValid)
        assertEquals(1, one.quiz?.questions?.size)

        val eighty = HaloTvQuizExporter.parse(
            quizJson(question = manyQuestions(HaloTvQuizExporter.MAX_QUESTIONS)),
        )
        assertTrue(eighty.errors.toString(), eighty.isValid)
        assertEquals(HaloTvQuizExporter.MAX_QUESTIONS, eighty.quiz?.questions?.size)

        val eightyOne = HaloTvQuizExporter.parse(
            quizJson(question = manyQuestions(HaloTvQuizExporter.MAX_QUESTIONS + 1)),
        )
        assertFalse(eightyOne.isValid)
        assertTrue(eightyOne.errors.any { it.contains("80") })

        val empty = HaloTvQuizExporter.parse(
            """{"format":"halotv-parent-quiz-v1","bank":{"name":"AI 出题","questions":[]}}""",
        )
        assertFalse(empty.isValid)
        assertTrue(empty.errors.any { it.contains("至少") })
    }

    @Test
    fun `invalid options and answers are rejected`() {
        val oneOption = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲"],"answer":0}"""),
        )
        assertFalse(oneOption.isValid)

        val fiveOptions = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲","乙","丙","丁","戊"],"answer":0}"""),
        )
        assertFalse(fiveOptions.isValid)

        val duplicateOptions = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲","甲"],"answer":0}"""),
        )
        assertFalse(duplicateOptions.isValid)
        assertTrue(duplicateOptions.errors.any { it.contains("重复") })

        val blankOption = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲"," "],"answer":0}"""),
        )
        assertFalse(blankOption.isValid)

        val nonStringOption = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲",2],"answer":0}"""),
        )
        assertFalse(nonStringOption.isValid)

        val outOfRange = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲","乙"],"answer":2}"""),
        )
        assertFalse(outOfRange.isValid)
        assertTrue(outOfRange.errors.any { it.contains("下标") })

        val negative = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲","乙"],"answer":-1}"""),
        )
        assertFalse(negative.isValid)

        val fractional = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲","乙"],"answer":0.5}"""),
        )
        assertFalse(fractional.isValid)

        val stringAnswer = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"题目","options":["甲","乙"],"answer":"0"}"""),
        )
        assertFalse(stringAnswer.isValid)
    }

    @Test
    fun `control characters and duplicate prompts are rejected`() {
        val newline = HaloTvQuizExporter.parse(
            quizJson(question = """{"prompt":"第一行\n第二行","options":["甲","乙"],"answer":0}"""),
        )
        assertFalse(newline.isValid)
        assertTrue(newline.errors.any { it.contains("控制字符") })

        val duplicate = HaloTvQuizExporter.parse(
            quizJson(
                question = """
                    {"prompt":"同一道题","options":["甲","乙"],"answer":0},
                    {"prompt":"同一道题","options":["丙","丁"],"answer":1}
                """.trimIndent(),
            ),
        )
        assertFalse(duplicate.isValid)
        assertTrue(duplicate.errors.any { it.contains("重复") })
    }

    @Test
    fun `oversized and malformed json are rejected`() {
        val oversized = HaloTvQuizExporter.parse(
            """{"format":"halotv-parent-quiz-v1","bank":{"name":"AI 出题","questions":[]},"padding":"${"x".repeat(HaloTvQuizExporter.MAX_JSON_BYTES)}"}""",
        )
        assertFalse(oversized.isValid)
        assertTrue(oversized.errors.any { it.contains("256 KB") })

        val malformed = HaloTvQuizExporter.parse("{not json")
        assertFalse(malformed.isValid)
        assertTrue(malformed.errors.isNotEmpty())

        val arrayRoot = HaloTvQuizExporter.parse("[]")
        assertFalse(arrayRoot.isValid)

        assertTrue(HaloTvQuizExporter.parse("").let { !it.isValid && it.quiz == null })
        assertNull(HaloTvQuizExporter.parse("   ").quiz)
    }

    @Test
    fun `suggested file name is safe and keeps the halo prefix`() {
        val plain = HaloTvQuizExporter.suggestedFileName("数学 第1单元")
        assertEquals("HaloTV-数学 第1单元.json", plain)

        val unsafe = HaloTvQuizExporter.suggestedFileName("""a/b:c*?"<>|""")
        assertTrue(unsafe.startsWith("HaloTV-"))
        assertTrue(unsafe.endsWith(".json"))
        assertFalse(unsafe.substringAfter("HaloTV-").substringBeforeLast(".json")
            .any { it in "\\/:*?\"<>|" })

        assertEquals("HaloTV-AI 出题.json", HaloTvQuizExporter.suggestedFileName("   "))
    }

    private fun quizJson(
        name: String = "AI 出题",
        question: String = """{"prompt":"题目","options":["甲","乙"],"answer":0}""",
    ): String = """
        {"format":"halotv-parent-quiz-v1","bank":{"name":"$name","questions":[$question]}}
    """.trimIndent()

    private fun manyQuestions(count: Int): String = (1..count).joinToString(",") { index ->
        """{"prompt":"题目$index","options":["甲$index","乙$index"],"answer":0}"""
    }
}