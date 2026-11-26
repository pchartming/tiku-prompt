package io.github.pchartming.tikuprompt.lan

import java.net.URI
import java.util.Locale

data class LanAddressResult(
    val normalizedUrl: String = "",
    val error: String? = null,
) {
    val isValid: Boolean get() = error == null && normalizedUrl.isNotEmpty()
}

/**
 * Normalizes the full address shown by a TV's parent web page.
 *
 * The address is kept opaque: path, port, query and pairing code must survive
 * unchanged. Only the scheme is validated and, when the user omits it, an
 * http:// prefix is added. This deliberately does not probe the address; the
 * app has no INTERNET permission and hands the URL to the system browser.
 */
object LanAddress {
    const val MAX_LENGTH = 2048

    private val schemePrefix = Regex(
        pattern = "^[A-Za-z][A-Za-z0-9+.-]*://",
    )

    fun normalize(raw: String): LanAddressResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return LanAddressResult(error = "请输入电视上显示的局域网地址。")
        }
        if (trimmed.length > MAX_LENGTH) {
            return LanAddressResult(error = "地址过长，最多 $MAX_LENGTH 个字符。")
        }
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return LanAddressResult(error = "地址不能包含空格、换行或控制字符。")
        }

        val matchedScheme = schemePrefix.find(trimmed)
        val suppliedScheme = matchedScheme?.value
            ?.removeSuffix("://")
            ?.lowercase(Locale.ROOT)
        if (suppliedScheme != null && suppliedScheme != "http" && suppliedScheme != "https") {
            return LanAddressResult(error = "只支持 http:// 或 https:// 地址。")
        }

        val candidate = if (matchedScheme == null) {
            "http://$trimmed"
        } else {
            val schemeEnd = matchedScheme.range.last + 1
            "$suppliedScheme://${trimmed.substring(schemeEnd)}"
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return LanAddressResult(error = "地址格式不正确，请复制电视上显示的完整地址。")

        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
            return LanAddressResult(error = "只支持 http:// 或 https:// 地址。")
        }
        if (uri.host.isNullOrBlank()) {
            return LanAddressResult(error = "地址缺少有效的电视 IP 或主机名。")
        }
        if (uri.rawUserInfo != null) {
            return LanAddressResult(error = "地址不能包含用户名或密码。")
        }
        val port = runCatching { uri.port }.getOrNull() ?: -1
        if (port != -1 && port !in 1..65535) {
            return LanAddressResult(error = "端口必须是 1–65535 的数字。")
        }

        return LanAddressResult(normalizedUrl = candidate)
    }
}