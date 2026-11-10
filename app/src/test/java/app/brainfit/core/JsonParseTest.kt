package app.brainfit.core

import app.brainfit.core.util.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonParseTest {

    @Test
    fun parsesWhatWeWrite() {
        val text = Json.obj(
            "q" to "12 − 7",
            "a" to 5,
            "big" to 12_345_678_901L,
            "share" to 0.25,
            "ok" to true,
            "none" to null,
            "cells" to listOf(0, 4, 8),
            "nested" to mapOf("x" to listOf(mapOf("y" to "z"))),
            "tricky" to "кавычка \" слэш \\ перевод\nстроки\tтаб\u0001",
        )
        val map = Json.parseObject(text)
        assertEquals("12 − 7", map["q"])
        assertEquals(5L, map["a"])
        assertEquals(12_345_678_901L, map["big"])
        assertEquals(0.25, map["share"])
        assertEquals(true, map["ok"])
        assertTrue(map.containsKey("none"))
        assertNull(map["none"])
        assertEquals(listOf(0L, 4L, 8L), map["cells"])
        assertEquals(mapOf("x" to listOf(mapOf("y" to "z"))), map["nested"])
        assertEquals("кавычка \" слэш \\ перевод\nстроки\tтаб\u0001", map["tricky"])
        // Порядок ключей сохраняется.
        assertEquals(listOf("q", "a", "big", "share", "ok", "none", "cells", "nested", "tricky"), map.keys.toList())
    }

    @Test
    fun parsesWhitespaceNumbersAndEscapes() {
        assertEquals(mapOf<String, Any?>(), Json.parse(" { } "))
        assertEquals(listOf<Any?>(), Json.parse("[ ]"))
        assertEquals(-3L, Json.parse("-3"))
        assertEquals(1.5e3, Json.parse("1.5e3"))
        assertEquals(-0.5, Json.parse("-0.5"))
        assertEquals("é/", Json.parse("\"\\u00e9\\/\""))
        assertEquals(listOf(1L, listOf(2L, null), "a"), Json.parse("[1, [2, null], \"a\"]"))
    }

    @Test
    fun intHelperReadsNumbers() {
        val map = Json.parseObject("""{"len":5,"d":2.0,"s":"7"}""")
        assertEquals(5, Json.int(map, "len"))
        assertEquals(2, Json.int(map, "d"))
        assertNull(Json.int(map, "s"))
        assertNull(Json.int(map, "missing"))
    }

    @Test
    fun brokenTextFailsWithPosition() {
        listOf(
            "",
            "{",
            "{\"a\" 1}",
            "{\"a\":1,}",
            "{a:1}",
            "[1 2]",
            "\"unclosed",
            "tru",
            "1 2",
            "\"\\x\"",
            "\"\\u12\"",
            "-",
        ).forEach { text ->
            try {
                Json.parse(text)
                fail("Должно было упасть: $text")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!, e.message!!.startsWith("JSON: "))
            }
        }
    }

    @Test
    fun parseObjectRejectsNonObjects() {
        listOf("[1]", "1", "null", "\"x\"").forEach { text ->
            try {
                Json.parseObject(text)
                fail("Должно было упасть: $text")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("ожидался объект"))
            }
        }
    }
}
