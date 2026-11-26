package io.github.pchartming.tikuprompt

import io.github.pchartming.tikuprompt.export.StudyLockPackContent
import io.github.pchartming.tikuprompt.export.StudyLockPackMeta
import io.github.pchartming.tikuprompt.export.StudyLockPackMetadata
import io.github.pchartming.tikuprompt.export.StudyLockZipExporter
import io.github.pchartming.tikuprompt.model.PromptForm
import io.github.pchartming.tikuprompt.model.ScopeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class StudyLockExportTest {
    private val validJson = """
        {
          "items": [
            {
              "s": "1 + 1 等于几？",
              "o": ["1", "2", "3", "4"],
              "a": 1,
              "e": "因为 1 + 1 = 2，所以选 B。"
            },
            {
              "s": "2 + 2 等于几？",
              "o": ["4", "3", "2", "1"],
              "a": 0,
              "e": "2 + 2 = 4。"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `valid json can become two packs in one zip`() {
        val parsed = StudyLockZipExporter.parse(validJson)
        assertTrue(parsed.errors.toString(), parsed.isValid)
        assertEquals(2, parsed.questions.size)
        assertEquals("1", parsed.questions[0].options[0])
        assertEquals(1, parsed.questions[0].answerIndex)

        val math = StudyLockPackMeta(
            id = "tiku_math",
            title = "四年级数学",
            grade = 4,
            subject = 1,
        )
        val english = StudyLockPackMeta(
            id = "tiku_english",
            title = "四年级英语",
            grade = 4,
            subject = 2,
        )
        val result = StudyLockZipExporter.buildZip(
            listOf(
                StudyLockPackContent(math, parsed.questions),
                StudyLockPackContent(english, parsed.questions),
            ),
        )
        assertEquals(result.errors.toString(), 2, result.packCount)
        assertEquals(4, result.questionCount)
        val files = unzip(requireNotNull(result.bytes))
        assertEquals(
            setOf("index.json", "tiku_math.json", "tiku_english.json"),
            files.keys,
        )

        val index = Json.parseToJsonElement(files.getValue("index.json")).jsonObject
        val packs = index.getValue("packs").jsonArray
        assertEquals(2, packs.size)
        assertEquals("tiku_math", packs[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(1, packs[0].jsonObject.getValue("subject").jsonPrimitive.content.toInt())
        assertEquals("tiku_english", packs[1].jsonObject.getValue("id").jsonPrimitive.content)

        val mathItems = Json.parseToJsonElement(files.getValue("tiku_math.json"))
            .jsonObject.getValue("items").jsonArray
        assertEquals(2, mathItems.size)
        assertEquals(
            "因为 1 + 1 = 2，所以选 B。",
            mathItems[0].jsonObject.getValue("e").jsonPrimitive.content,
        )
        assertTrue(files.getValue("tiku_math.json").startsWith("{"))
        assertFalse(files.getValue("tiku_math.json").startsWith("\uFEFF"))
    }

    @Test
    fun `bom and markdown code fence are accepted`() {
        val parsed = StudyLockZipExporter.parse(
            "﻿```json\n$validJson\n```",
        )
        assertTrue(parsed.errors.toString(), parsed.isValid)
        assertEquals(2, parsed.questions.size)
    }

    @Test
    fun `invalid questions are reported and do not become exportable`() {
        val parsed = StudyLockZipExporter.parse(
            """
            {
              "items": [
                {"s": "", "o": ["A", "B", "C", "D"], "a": 0, "e": "x"},
                {"s": "题目", "o": ["A", "B", "C"], "a": 0, "e": "x"},
                {"s": "题目", "o": ["A", "B", "C", "D"], "a": 4, "e": "x"}
              ]
            }
            """.trimIndent(),
        )
        assertFalse(parsed.isValid)
        assertTrue(parsed.errors.any { it.contains("题干") })
        assertTrue(parsed.errors.any { it.contains("4 个") })
        assertTrue(parsed.errors.any { it.contains("0–3") })
    }

    @Test
    fun `missing explanation is a warning while duplicate options are kept`() {
        val parsed = StudyLockZipExporter.parse(
            """
            {
              "items": [
                {"s": "题目", "o": ["A", "A", "C", "D"], "a": 0}
              ]
            }
            """.trimIndent(),
        )
        assertTrue(parsed.errors.toString(), parsed.isValid)
        assertEquals(1, parsed.questions.size)
        assertNull(parsed.questions.single().explanation)
        assertTrue(parsed.warnings.any { it.contains("缺少解析") })
        assertTrue(parsed.warnings.any { it.contains("重复") })
    }

    @Test
    fun `unknown fields are ignored with a warning`() {
        val parsed = StudyLockZipExporter.parse(
            """
            {
              "items": [
                {"s": "题目", "o": ["A", "B", "C", "D"], "a": 0, "e": "解析", "ps": ["题", "目"]}
              ]
            }
            """.trimIndent(),
        )
        assertTrue(parsed.isValid)
        assertTrue(parsed.warnings.any { it.contains("未知字段") })
    }

    @Test
    fun `missing items and too many questions are rejected`() {
        val noItems = StudyLockZipExporter.parse("""{"foo":[]}""")
        assertFalse(noItems.isValid)
        assertTrue(noItems.errors.any { it.contains("items") })

        val many = buildString {
            append("""{"items":[""")
            repeat(StudyLockZipExporter.MAX_QUESTIONS + 1) { index ->
                if (index > 0) append(',')
                append(
                    """{"s":"题$index","o":["A","B","C","D"],"a":0,"e":"解析"}""",
                )
            }
            append("]}")
        }
        val tooMany = StudyLockZipExporter.parse(many)
        assertFalse(tooMany.isValid)
        assertTrue(tooMany.errors.single().contains("5000"))
    }

    @Test
    fun `duplicate metadata ids get unique file names`() {
        val parsed = StudyLockZipExporter.parse(validJson)
        val meta = StudyLockPackMeta("tiku_same", "同名题库", 4, 1)
        val result = StudyLockZipExporter.buildZip(
            listOf(
                StudyLockPackContent(meta, parsed.questions),
                StudyLockPackContent(meta, parsed.questions),
            ),
        )
        assertNotNull(result.bytes)
        val files = unzip(requireNotNull(result.bytes))
        assertTrue(files.containsKey("tiku_same.json"))
        assertTrue(files.containsKey("tiku_same_2.json"))
        assertTrue(result.warnings.any { it.contains("重复") })
    }

    @Test
    fun `StudyLock metadata maps subjects and builds stable ids`() {
        assertEquals(1, StudyLockPackMetadata.subjectCode("MATH"))
        assertEquals(8, StudyLockPackMetadata.subjectCode("POLITICS"))
        assertNull(StudyLockPackMetadata.subjectCode("SCIENCE"))

        val form = PromptForm(
            targetId = "studylock",
            grade = 4,
            subjectId = "MATH",
            bookKey = "4|MATH|RENJIAO|上册|",
            scopeMode = ScopeMode.UNIT,
            unitNo = 3,
            lessonIndex = null,
        )
        val first = StudyLockPackMetadata.packId(form, "四年级数学 · 第3单元")
        val second = StudyLockPackMetadata.packId(form, "四年级数学 · 第3单元")
        val renamed = StudyLockPackMetadata.packId(form, "另一个名字")
        assertEquals(first, second)
        assertTrue(first.startsWith("tiku_"))
        assertFalse(first == renamed)
        assertTrue(first.matches(Regex("tiku_[0-9a-f]{12}")))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val files = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return files
    }
}