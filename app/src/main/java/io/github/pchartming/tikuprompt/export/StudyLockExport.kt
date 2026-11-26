package io.github.pchartming.tikuprompt.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class StudyLockQuestion(
    val stem: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String?,
)

data class StudyLockParseResult(
    val questions: List<StudyLockQuestion> = emptyList(),
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty() && questions.isNotEmpty()
}

data class StudyLockPackMeta(
    val id: String,
    val title: String,
    val grade: Int,
    val subject: Int?,
    val enabled: Boolean = true,
)

data class StudyLockPackContent(
    val meta: StudyLockPackMeta,
    val questions: List<StudyLockQuestion>,
)

data class StudyLockZipResult(
    val bytes: ByteArray? = null,
    val packCount: Int = 0,
    val questionCount: Int = 0,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Converts AI-returned StudyLock JSON into one or more zip pack files.
 *
 * This is intentionally plain Kotlin so the format rules can be tested on the
 * JVM without Android. It does not copy StudyLock code or resources; it only
 * writes the publicly documented import shape.
 */
object StudyLockZipExporter {
    const val MAX_JSON_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    const val MAX_QUESTIONS = 5000
    const val MAX_PACKS = 127

    private const val MAX_MESSAGES = 8
    private val allowedQuestionKeys = setOf("s", "o", "a", "e")
    private val json = Json { isLenient = false }

    fun parse(raw: String): StudyLockParseResult {
        if (raw.isBlank()) return StudyLockParseResult()
        val text = stripWrapping(raw)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            return StudyLockParseResult(
                errors = listOf("JSON 超过 $MAX_JSON_BYTES 字节上限，请拆分成多个题库。"),
            )
        }

        val root = runCatching { json.parseToJsonElement(text) }
            .getOrElse { error ->
                val detail = error.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(120)
                    ?.takeIf { it.isNotBlank() }
                return StudyLockParseResult(
                    errors = listOf(
                        if (detail == null) "JSON 解析失败，请检查格式。" else "JSON 解析失败：$detail",
                    ),
                )
            }
        val rootObject = root as? JsonObject
            ?: return StudyLockParseResult(errors = listOf("根节点必须是 JSON 对象。"))
        val items = rootObject["items"] as? JsonArray
            ?: return StudyLockParseResult(errors = listOf("根对象缺少 items 数组。"))
        if (items.isEmpty()) {
            return StudyLockParseResult(errors = listOf("items 不能为空，至少需要 1 道题。"))
        }
        if (items.size > MAX_QUESTIONS) {
            return StudyLockParseResult(
                errors = listOf("单个题库最多 $MAX_QUESTIONS 道题，当前有 ${items.size} 道。"),
            )
        }

        val questions = mutableListOf<StudyLockQuestion>()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var missingExplanation = 0
        var duplicateOptionSets = 0
        var unknownFieldItems = 0

        items.forEachIndexed { index, element ->
            val number = index + 1
            val item = element as? JsonObject
            if (item == null) {
                errors += "第 $number 题不是 JSON 对象。"
                return@forEachIndexed
            }

            val itemErrors = mutableListOf<String>()
            if ((item.keys - allowedQuestionKeys).isNotEmpty()) unknownFieldItems += 1

            val stem = item.stringValue("s")
            if (stem == null || stem.isBlank()) {
                itemErrors += "题干 s 必须是非空字符串。"
            }

            val optionArray = item["o"] as? JsonArray
            val options = optionArray?.mapNotNull { option ->
                val primitive = option as? JsonPrimitive
                if (primitive == null || !primitive.isString) null else primitive.content
            }
            if (optionArray == null || optionArray.size != 4) {
                itemErrors += "选项 o 必须恰好有 4 个字符串。"
            } else if (options == null || options.size != 4) {
                itemErrors += "选项 o 的每一项都必须是字符串。"
            } else if (options.any { it.isBlank() }) {
                itemErrors += "选项 o 不能包含空选项。"
            } else if (options.distinct().size != options.size) {
                duplicateOptionSets += 1
            }

            val answer = item["a"]
                ?.let { it as? JsonPrimitive }
                ?.takeUnless { it.isString }
                ?.intOrNull
            if (answer == null || answer !in 0..3) {
                itemErrors += "答案 a 必须是 0–3 的整数。"
            }

            val explanation = when (val value = item["e"]) {
                null, is JsonNull -> {
                    missingExplanation += 1
                    null
                }

                is JsonPrimitive -> {
                    if (!value.isString) {
                        itemErrors += "解析 e 必须是字符串。"
                        null
                    } else if (value.content.isBlank()) {
                        missingExplanation += 1
                        null
                    } else {
                        value.content
                    }
                }

                else -> {
                    itemErrors += "解析 e 必须是字符串。"
                    null
                }
            }

            if (itemErrors.isEmpty() && stem != null && options != null && answer != null) {
                questions += StudyLockQuestion(
                    stem = stem,
                    options = options,
                    answerIndex = answer,
                    explanation = explanation,
                )
            } else {
                errors += itemErrors.map { "第 $number 题：$it" }
            }
        }

        if (unknownFieldItems > 0) {
            warnings += "有 $unknownFieldItems 道题包含未知字段，已忽略。"
        }
        if (missingExplanation > 0) {
            warnings += "有 $missingExplanation 道题缺少解析，仍可导入。"
        }
        if (duplicateOptionSets > 0) {
            warnings += "有 $duplicateOptionSets 道题的选项有重复，请核对。"
        }

        return StudyLockParseResult(
            questions = questions,
            errors = limitMessages(errors, "处问题"),
            warnings = limitMessages(warnings, "条提示"),
        )
    }

    fun buildZip(contents: List<StudyLockPackContent>): StudyLockZipResult {
        if (contents.isEmpty()) {
            return StudyLockZipResult(errors = listOf("至少需要 1 个题库。"))
        }
        if (contents.size > MAX_PACKS) {
            return StudyLockZipResult(
                errors = listOf("一个 zip 最多包含 $MAX_PACKS 个题库，当前有 ${contents.size} 个。"),
            )
        }

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val usedIds = mutableSetOf<String>()
        var questionCount = 0

        val normalized = contents.mapIndexed { index, content ->
            val fallbackId = "tiku_pack_${index + 1}"
            val rawId = content.meta.id
                .replace(Regex("[^A-Za-z0-9_]"), "_")
                .trim('_')
                .ifBlank { fallbackId }
            if (rawId != content.meta.id) {
                warnings += "题库「${content.meta.title}」的 id 含非法字符，已自动修正。"
            }
            var id = rawId
            var duplicate = 2
            while (!usedIds.add(id)) {
                id = "${rawId}_$duplicate"
                duplicate += 1
            }
            if (id != rawId) {
                warnings += "题库「${content.meta.title}」的 id 重复，已改为 $id。"
            }
            if (content.questions.isEmpty()) {
                errors += "题库「${content.meta.title}」没有可导出的题目。"
            }
            if (content.questions.size > MAX_QUESTIONS) {
                errors += "题库「${content.meta.title}」超过 $MAX_QUESTIONS 道题。"
            }
            questionCount += content.questions.size
            NormalizedPack(content = content, id = id)
        }

        if (errors.isNotEmpty()) {
            return StudyLockZipResult(errors = limitMessages(errors, "处问题"))
        }

        val packFiles = normalized.map { pack ->
            val title = pack.content.meta.title.ifBlank { "学习锁题库" }
            val subject = pack.content.meta.subject?.takeIf { it in 0..8 }
            if (pack.content.meta.subject != null && subject == null) {
                warnings += "题库「$title」的学科编号无效，已省略 subject 字段。"
            }
            val packObject = buildJsonObject {
                putJsonArray("items") {
                    pack.content.questions.forEach { question ->
                        add(
                            buildJsonObject {
                                put("s", question.stem)
                                putJsonArray("o") {
                                    question.options.forEach { option -> add(JsonPrimitive(option)) }
                                }
                                put("a", question.answerIndex)
                                question.explanation
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { explanation -> put("e", explanation) }
                            },
                        )
                    }
                }
            }.toString()
            pack to packObject
        }

        val indexJson = buildJsonObject {
            putJsonArray("packs") {
                normalized.forEach { pack ->
                    add(
                        buildJsonObject {
                            put("id", pack.id)
                            put("title", pack.content.meta.title.ifBlank { "学习锁题库" })
                            put("grade", pack.content.meta.grade)
                            pack.content.meta.subject
                                ?.takeIf { it in 0..8 }
                                ?.let { put("subject", it) }
                            put("enabled", pack.content.meta.enabled)
                        },
                    )
                }
            }
        }.toString()

        var totalBytes = indexJson.toByteArray(Charsets.UTF_8).size
        val fileEntries = mutableListOf("index.json" to indexJson)
        packFiles.forEach { (pack, text) ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_JSON_BYTES) {
                errors += "题库「${pack.id}」的 JSON 超过 $MAX_JSON_BYTES 字节上限。"
            }
            totalBytes += bytes.size
            fileEntries += "${pack.id}.json" to text
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            errors += "题库总大小超过 $MAX_TOTAL_BYTES 字节上限。"
        }
        if (errors.isNotEmpty()) {
            return StudyLockZipResult(errors = limitMessages(errors, "处问题"))
        }

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fileEntries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        return StudyLockZipResult(
            bytes = output.toByteArray(),
            packCount = contents.size,
            questionCount = questionCount,
            warnings = limitMessages(warnings, "条提示"),
        )
    }

    private fun JsonObject.stringValue(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun stripWrapping(raw: String): String {
        var text = raw.trim().removePrefix("\uFEFF").trim()
        if (!text.startsWith("```")) return text
        val firstLineEnd = text.indexOf('\n')
        if (firstLineEnd < 0) return text
        val closing = text.lastIndexOf("```")
        if (closing <= firstLineEnd) return text
        text = text.substring(firstLineEnd + 1, closing).trim()
        return text
    }

    private fun limitMessages(messages: List<String>, unit: String): List<String> {
        if (messages.size <= MAX_MESSAGES) return messages
        val shown = messages.take(MAX_MESSAGES)
        return shown + "还有 ${messages.size - MAX_MESSAGES} ${unit}未显示。"
    }

    private data class NormalizedPack(
        val content: StudyLockPackContent,
        val id: String,
    )
}