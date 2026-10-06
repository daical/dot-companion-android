package dev.dotcompanion.identity

import com.nimbusds.jose.util.JSONObjectUtils

/** Nimbus handles JSON values; this bounded syntax pass rejects duplicate members first. */
internal object SecurityJson {
    fun objectValue(value: String): Map<String, Any?> {
        require(value.length <= 1_048_576) { "Invalid JSON" }
        Syntax(value).validate()
        return JSONObjectUtils.parse(value)
    }

    private class Syntax(private val source: String) {
        private var offset = 0
        private var nodes = 0
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid JSON")

        fun validate() {
            whitespace()
            if (source.getOrNull(offset) != '{') invalid()
            value(0)
            whitespace()
            if (offset != source.length) invalid()
        }

        private fun value(depth: Int) {
            if (depth > 32 || ++nodes > 20_000) invalid()
            whitespace()
            when (source.getOrNull(offset)) {
                '{' -> {
                    offset++
                    whitespace()
                    if (consume('}')) return
                    val keys = hashSetOf<String>()
                    while (true) {
                        whitespace()
                        if (source.getOrNull(offset) != '"' || !keys.add(string())) invalid()
                        whitespace()
                        if (!consume(':')) invalid()
                        value(depth + 1)
                        whitespace()
                        if (consume('}')) return
                        if (!consume(',')) invalid()
                    }
                }
                '[' -> {
                    offset++
                    whitespace()
                    if (consume(']')) return
                    while (true) {
                        value(depth + 1)
                        whitespace()
                        if (consume(']')) return
                        if (!consume(',')) invalid()
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalid()
            }
        }

        private fun string(): String {
            if (!consume('"')) invalid()
            val decoded = StringBuilder()
            while (offset < source.length) {
                val character = source[offset++]
                when {
                    character == '"' -> return decoded.toString()
                    character == '\\' -> {
                        when (val escaped = source.getOrNull(offset++)) {
                            '"', '\\', '/' -> decoded.append(escaped)
                            'b' -> decoded.append('\b')
                            'f' -> decoded.append('\u000C')
                            'n' -> decoded.append('\n')
                            'r' -> decoded.append('\r')
                            't' -> decoded.append('\t')
                            'u' -> {
                                if (offset + 4 > source.length) invalid()
                                val hex = source.substring(offset, offset + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) invalid()
                                decoded.append(hex.toInt(16).toChar())
                                offset += 4
                            }
                            else -> invalid()
                        }
                    }
                    character.code < 0x20 -> invalid()
                    else -> decoded.append(character)
                }
            }
            invalid()
        }

        private fun number() {
            consume('-')
            if (!consume('0')) digits()
            if (consume('.')) digits()
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-')
                digits()
            }
        }

        private fun digits() {
            val start = offset
            while (source.getOrNull(offset) in '0'..'9') offset++
            if (start == offset) invalid()
        }

        private fun literal(expected: String) {
            if (!source.startsWith(expected, offset)) invalid()
            offset += expected.length
        }

        private fun whitespace() {
            while (true) {
                when (source.getOrNull(offset)) {
                    ' ', '\n', '\r', '\t' -> offset++
                    else -> return
                }
            }
        }

        private fun consume(character: Char): Boolean = if (source.getOrNull(offset) == character) {
            offset++
            true
        } else false
    }
}
