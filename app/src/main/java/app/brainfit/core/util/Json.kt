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
