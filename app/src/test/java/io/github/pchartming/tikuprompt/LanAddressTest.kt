package io.github.pchartming.tikuprompt

import io.github.pchartming.tikuprompt.lan.LanAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {

    @Test
    fun `full parent url keeps port path and token unchanged`() {
        val result = LanAddress.normalize(
            "  http://192.0.2.10:8765/?t=12345678  ",
        )

        assertNull(result.error)
        assertTrue(result.isValid)
        assertEquals(
            "http://192.0.2.10:8765/?t=12345678",
            result.normalizedUrl,
        )
    }

    @Test
    fun `missing scheme gets an http prefix`() {
        assertEquals(
            "http://192.0.2.10:8099/",
            LanAddress.normalize("192.0.2.10:8099/").normalizedUrl,
        )
        assertEquals(
            "http://localhost:8099",
            LanAddress.normalize("localhost:8099").normalizedUrl,
        )
    }

    @Test
    fun `https and uppercase schemes are normalized`() {
        assertEquals(
            "https://example.local:9443/?pair=abc",
            LanAddress.normalize("HTTPS://example.local:9443/?pair=abc").normalizedUrl,
        )
        assertEquals(
            "http://192.0.2.10:8765/?t=1",
            LanAddress.normalize("HTTP://192.0.2.10:8765/?t=1").normalizedUrl,
        )
    }

    @Test
    fun `ipv6 parent url with port is accepted`() {
        val result = LanAddress.normalize("http://[2001:db8::1]:8765/?t=12345678")

        assertTrue(result.error, result.isValid)
        assertEquals(
            "http://[2001:db8::1]:8765/?t=12345678",
            result.normalizedUrl,
        )
    }

    @Test
    fun `blank and embedded whitespace are rejected`() {
        assertFalse(LanAddress.normalize("").isValid)
        assertFalse(LanAddress.normalize("   ").isValid)
        assertFalse(LanAddress.normalize("http://192.0.2.10:8099/a b").isValid)
        assertFalse(LanAddress.normalize("http://192.0.2.10:8099/a\nb").isValid)
        assertFalse(LanAddress.normalize("http://192.0.2.10:8099/a\tb").isValid)
    }

    @Test
    fun `unsupported schemes and malformed authorities are rejected`() {
        val values = listOf(
            "ftp://192.0.2.10/",
            "javascript:alert(1)",
            "mailto:someone",
            "http://",
            "http:///path",
            "http://192.0.2.10:abc/",
            "http://192.0.2.10:0/",
            "http://192.0.2.10:70000/",
        )

        val withUserInfo = "http://" + "user" + ":" + "pass" + "@" + "192.0.2.10/"
        (values + withUserInfo).forEach { value ->
            val result = LanAddress.normalize(value)
            assertFalse(value, result.isValid)
            assertTrue(value, result.error != null)
        }
    }

    @Test
    fun `overlong url is rejected`() {
        val tooLong = "http://192.0.2.10/" + "a".repeat(LanAddress.MAX_LENGTH)
        val result = LanAddress.normalize(tooLong)

        assertFalse(result.isValid)
        assertTrue(result.error.orEmpty().contains("${LanAddress.MAX_LENGTH}"))
    }
}