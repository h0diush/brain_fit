package app.brainfit.core.util

/**
 * Запись JSON без библиотек: игры и движок — чистый Kotlin, а org.json есть только на Android.
 * Нужна для paramsJson, answerJson и metricsJson. Умеет null, Boolean, числа, строки, Map и списки.
 */
object Json {
    fun obj(vararg pairs: Pair<String, Any?>): String = encode(linkedMapOf(*pairs))

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            // NaN и бесконечность в JSON записать нельзя.
            is Double -> if (value.isFinite()) out.append(value) else out.append("null")
            is Float -> if (value.isFinite()) out.append(value) else out.append("null")
            is CharSequence -> writeString(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> writeArray(out, value.iterator())
            is Array<*> -> writeArray(out, value.iterator())
            is IntArray -> writeArray(out, value.iterator())
            is LongArray -> writeArray(out, value.iterator())
            is Enum<*> -> writeString(out, value.name)
            else -> throw IllegalArgumentException("Тип ${value::class} нельзя записать в JSON")
        }
    }

    /**
     * Разбор JSON: объект — Map, массив — List, целое число — Long, дробное — Double.
     * Неверный текст — IllegalArgumentException с позицией ошибки.
     */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("лишние символы после значения")
        return value
    }

    fun parseObject(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("JSON: ожидался объект: $text")
    }

    fun int(map: Map<String, Any?>, key: String): Int? = (map[key] as? Number)?.toInt()

    private class Reader(private val s: String) {
        private var pos = 0

        fun fail(message: String): Nothing = throw IllegalArgumentException("JSON: $message (позиция $pos)")

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) fail("неожиданный конец")
            val c = s[pos]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> fail("неожиданный символ '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("ожидалось $word")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("ожидался ключ в кавычках")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("ожидалось ':'")
                pos++
                map[key] = readValue()
                skipWhitespace()
                val next = peek()
                pos++
                if (next == '}') return map
                if (next != ',') fail("ожидалось ',' или '}'")
            }
        }

        private fun readArray(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list += readValue()
                skipWhitespace()
                val next = peek()
                pos++
                if (next == ']') return list
                if (next != ',') fail("ожидалось ',' или ']'")
            }
        }

        private fun readString(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("строка не закрыта")
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (atEnd()) fail("обрыв после \\")
                when (val e = s[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) fail("короткая \\u-последовательность")
                        val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("неверная \\u-последовательность")
                        sb.append(code.toChar())
                        pos += 4
                    }
                    else -> fail("неизвестная последовательность \\$e")
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val token = s.substring(start, pos)
            val fractional = token.any { it == '.' || it == 'e' || it == 'E' }
            return (if (fractional) token.toDoubleOrNull() else token.toLongOrNull()) ?: fail("неверное число '$token'")
        }
    }

    private fun writeArray(out: StringBuilder, items: Iterator<*>) {
        out.append('[')
        var first = true
        for (item in items) {
            if (!first) out.append(',')
            first = false
            write(out, item)
        }
        out.append(']')
    }

    private fun writeString(out: StringBuilder, s: CharSequence) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        out.append('"')
    }
}
