package io.github.pchartming.tikuprompt.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class HaloTvQuestion(
    val prompt: String,
    val options: List<String>,
    val answer: Int,
)

data class HaloTvQuiz(
    val name: String,
    val questions: List<HaloTvQuestion>,
)

data class HaloTvQuizParseResult(
    val quiz: HaloTvQuiz? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty() && quiz != null
}

data class HaloTvQuizFile(
    val bytes: ByteArray,
    val suggestedName: String,
    val questionCount: Int,
)

/**
 * Validates and writes the HaloTV 3.6+ parent-quiz import file.
 *
 * The rules mirror HaloTV's public parent-quiz importer: one bank per file,
 * at most 80 questions, short plain-text fields, and a 256 KB file ceiling.
 * Unknown fields are dropped instead of copied so the exported file contains
 * only the fields HaloTV actually reads. This code is independent of HaloTV's
 * source; the shared format is a public import contract.
 */
object HaloTvQuizExporter {
    const val FORMAT = "halotv-parent-quiz-v1"
    const val MAX_JSON_BYTES = 256 * 1024
    const val MAX_QUESTIONS = 80
    const val MAX_BANK_NAME_LENGTH = 20
    const val MAX_PROMPT_LENGTH = 56
    const val MAX_OPTION_LENGTH = 18
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 4

    private const val MAX_MESSAGES = 8
    private val json = Json { isLenient = false }
    private val controlChars = Regex("[\\x00-\\x1f\\x7f]")
    private val unsafeFileNameChars = Regex("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]")
    private val rootKeys = setOf("format", "bank")
    private val bankKeys = setOf("name", "questions")
    private val questionKeys = setOf("prompt", "options", "answer")

    fun parse(raw: String): HaloTvQuizParseResult {
        if (raw.isBlank()) return HaloTvQuizParseResult()
        val text = stripWrapping(raw)
        val byteSize = text.toByteArray(Charsets.UTF_8).size
        if (byteSize > MAX_JSON_BYTES) {
            return HaloTvQuizParseResult(
                errors = listOf(
                    "JSON 超过 256 KB（HaloTV 导入上限），当前 $byteSize 字节。",
                ),
            )
        }

        val root = runCatching { json.parseToJsonElement(text) }
            .getOrElse { error ->
                val detail = error.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(120)
                    ?.takeIf { it.isNotBlank() }
                return HaloTvQuizParseResult(
                    errors = listOf(
                        if (detail == null) {
                            "JSON 解析失败，请检查格式。"
                        } else {
                            "JSON 解析失败：$detail"
                        },
                    ),
                )
            }
        val rootObject = root as? JsonObject
            ?: return HaloTvQuizParseResult(
                errors = listOf("根节点必须是 JSON 对象。"),
            )

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val extraRoot = rootObject.keys - rootKeys
        if (extraRoot.isNotEmpty()) {
            warnings += "根对象含额外字段 ${extraRoot.sorted().joinToString("、")}，导出时会移除。"
        }
        val format = rootObject.stringValue("format")
        if (format != FORMAT) {
            errors += "format 必须固定为 \"$FORMAT\"。"
        }

        val bank = rootObject["bank"] as? JsonObject
        if (bank == null) {
            errors += "根对象缺少 bank 对象。"
            return HaloTvQuizParseResult(
                errors = limitMessages(errors, "处问题"),
                warnings = limitMessages(warnings, "条提示"),
            )
        }

        val extraBank = bank.keys - bankKeys
        if (extraBank.isNotEmpty()) {
            warnings += "bank 含额外字段 ${extraBank.sorted().joinToString("、")}，导出时会移除。"
        }
        val name = bank.stringValue("name")?.trim()
        if (name == null || name.isEmpty()) {
            errors += "bank.name 必须是非空字符串。"
        } else {
            if (name.length > MAX_BANK_NAME_LENGTH) {
                errors += "bank.name 超过 $MAX_BANK_NAME_LENGTH 个字符。"
            }
            if (controlChars.containsMatchIn(name)) {
                errors += "bank.name 不能包含换行、制表符等控制字符。"
            }
            if (name == "AI 出题") {
                warnings += "题库名是提示词默认的「AI 出题」；HaloTV 不允许与已有题库同名，导入第二套前请修改 bank.name。"
            }
        }

        val questionArray = bank["questions"] as? JsonArray
        if (questionArray == null) {
            errors += "bank.questions 必须是数组。"
            return HaloTvQuizParseResult(
                errors = limitMessages(errors, "处问题"),
                warnings = limitMessages(warnings, "条提示"),
            )
        }
        if (questionArray.isEmpty()) {
            errors += "bank.questions 不能为空，至少需要 1 道题。"
        }
        if (questionArray.size > MAX_QUESTIONS) {
            errors += "单个题库最多 $MAX_QUESTIONS 道题，当前有 ${questionArray.size} 道。"
        }

        val questions = mutableListOf<HaloTvQuestion>()
        val seenPrompts = mutableSetOf<String>()
        var unknownFieldQuestions = 0

        questionArray.forEachIndexed { index, element ->
            val number = index + 1
            val item = element as? JsonObject
            if (item == null) {
                errors += "第 $number 题不是 JSON 对象。"
                return@forEachIndexed
            }

            if ((item.keys - questionKeys).isNotEmpty()) unknownFieldQuestions += 1
            val itemErrors = mutableListOf<String>()
            val prompt = item.stringValue("prompt")?.trim()
            if (prompt == null || prompt.isEmpty()) {
                itemErrors += "prompt 必须是非空字符串。"
            } else {
                if (prompt.length > MAX_PROMPT_LENGTH) {
                    itemErrors += "prompt 超过 $MAX_PROMPT_LENGTH 个字符。"
                }
                if (controlChars.containsMatchIn(prompt)) {
                    itemErrors += "prompt 不能包含换行、制表符等控制字符。"
                }
                if (prompt in seenPrompts) {
                    itemErrors += "prompt 与前面的题目重复。"
                }
            }

            val optionArray = item["options"] as? JsonArray
            var options: List<String>? = null
            if (optionArray == null) {
                itemErrors += "options 必须是数组。"
            } else if (optionArray.size !in MIN_OPTIONS..MAX_OPTIONS) {
                itemErrors += "options 必须有 $MIN_OPTIONS–$MAX_OPTIONS 项。"
            } else {
                val rawOptions = optionArray.map { option ->
                    val primitive = option as? JsonPrimitive
                    if (primitive == null || !primitive.isString) null else primitive.content.trim()
                }
                if (rawOptions.any { it == null }) {
                    itemErrors += "options 的每一项都必须是字符串。"
                } else {
                    val values = rawOptions.filterNotNull()
                    when {
                        values.any { it.isEmpty() } ->
                            itemErrors += "options 不能包含空选项。"

                        values.any { it.length > MAX_OPTION_LENGTH } ->
                            itemErrors += "每个选项不能超过 $MAX_OPTION_LENGTH 个字符。"

                        values.any { controlChars.containsMatchIn(it) } ->
                            itemErrors += "options 不能包含换行、制表符等控制字符。"

                        values.distinct().size != values.size ->
                            itemErrors += "同一道题的选项不能重复。"

                        else -> options = values
                    }
                }
            }

            val answerValue = (item["answer"] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.doubleOrNull
            val answer = when {
                answerValue == null || answerValue % 1.0 != 0.0 -> {
                    itemErrors += "answer 必须是从 0 开始的整数下标。"
                    null
                }

                answerValue < 0 ||
                    answerValue >= (optionArray?.size ?: 0) -> {
                    itemErrors += "answer 必须落在 options 的下标范围内。"
                    null
                }

                else -> answerValue.toInt()
            }

            if (itemErrors.isEmpty() && prompt != null && options != null && answer != null) {
                seenPrompts += prompt
                questions += HaloTvQuestion(
                    prompt = prompt,
                    options = options,
                    answer = answer,
                )
            } else {
                errors += itemErrors.map { "第 $number 题：$it" }
            }
        }

        if (unknownFieldQuestions > 0) {
            warnings += "有 $unknownFieldQuestions 道题包含额外字段，导出时会移除。"
        }

        val quiz = if (errors.isEmpty() && name != null) {
            HaloTvQuiz(name = name, questions = questions)
        } else {
            null
        }
        return HaloTvQuizParseResult(
            quiz = quiz,
            errors = limitMessages(errors, "处问题"),
            warnings = limitMessages(warnings, "条提示"),
        )
    }

    fun build(quiz: HaloTvQuiz): ByteArray {
        val text = buildJsonObject {
            put("format", FORMAT)
            putJsonObject("bank") {
                put("name", quiz.name)
                putJsonArray("questions") {
                    quiz.questions.forEach { question ->
                        add(
                            buildJsonObject {
                                put("prompt", question.prompt)
                                putJsonArray("options") {
                                    question.options.forEach { option ->
                                        add(JsonPrimitive(option))
                                    }
                                }
                                put("answer", question.answer)
                            },
                        )
                    }
                }
            }
        }.toString()
        return text.toByteArray(Charsets.UTF_8)
    }

    fun suggestedFileName(name: String): String {
        val safe = name.trim()
            .replace(unsafeFileNameChars, "_")
            .trim(' ', '.')
            .take(40)
            .ifBlank { "AI 出题" }
        return "HaloTV-$safe.json"
    }

    private fun JsonObject.stringValue(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun stripWrapping(raw: String): String {
        var text = raw.trim().removePrefix("﻿").trim()
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
}