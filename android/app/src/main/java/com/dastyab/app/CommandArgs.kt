package com.dastyab.app

/** Tokenizes arguments for ProcessBuilder, without shell expansion or execution.
 * Single/double quotes group text; backslashes escape quotes, backslashes and
 * whitespace outside single quotes. Other backslashes remain literal (paths/URLs).
 */
object CommandArgs {
    fun parse(input: String): List<String> {
        require('\u0000' !in input) { "Arguments cannot contain NUL characters" }
        val args = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        var index = 0
        while (index < input.length) {
            val char = input[index]
            when {
                char == '\\' && quote != '\'' -> {
                    val next = input.getOrNull(index + 1)
                    if (next != null && (next == '\\' || next == '"' ||
                            (quote == null && (next == '\'' || next.isWhitespace())))) {
                        token.append(next)
                        index++
                    } else token.append(char)
                    started = true
                }
                quote != null -> {
                    if (char == quote) quote = null else token.append(char)
                }
                char == '\'' || char == '"' -> {
                    quote = char
                    started = true
                }
                char.isWhitespace() -> {
                    if (quote == null && started) {
                        args.add(token.toString())
                        token.setLength(0)
                        started = false
                    }
                }
                else -> { token.append(char); started = true }
            }
            index++
        }
        require(quote == null) { "Unclosed quote in arguments" }
        if (started) args.add(token.toString())
        return args
    }
}
