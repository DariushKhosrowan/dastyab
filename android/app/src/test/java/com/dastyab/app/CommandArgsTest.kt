package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandArgsTest {
    @Test fun whitespaceAndEmptyInput() {
        assertEquals(emptyList<String>(), CommandArgs.parse(" \t\n"))
        assertEquals(listOf("scan", "--help"), CommandArgs.parse(" scan\t--help\n"))
    }

    @Test fun quotedArgumentsAndEmptyValues() {
        assertEquals(listOf("profile-create", "my profile", "--description", "", "ab cd"),
            CommandArgs.parse("profile-create 'my profile' --description \"\" a\"b c\"d"))
    }

    @Test fun urlsPathsAndUnicodeArePreserved() {
        assertEquals(listOf("https://example.org/a/../b?q=سلام&x=1#part", "../my file.json", "دستیاب🌍"),
            CommandArgs.parse("https://example.org/a/../b?q=سلام&x=1#part '../my file.json' دستیاب🌍"))
        assertEquals(listOf("C:\\Users\\name", "C:\\my files\\", "/tmp/a b"),
            CommandArgs.parse("C:\\Users\\name 'C:\\my files\\' /tmp/a\\ b"))
    }

    @Test fun escapingAndLiteralSingleQuotes() {
        assertEquals(listOf("say \"hello\"", "it's", "a\\b", "trailing\\"),
            CommandArgs.parse("\"say \\\"hello\\\"\" it\\'s 'a\\b' trailing\\"))
    }

    @Test fun shellSyntaxIsOnlyLiteralText() {
        assertEquals(listOf("\$HOME", "\$(id)", "*", ";", "|", ">", "~"),
            CommandArgs.parse("\$HOME \$(id) * ; | > ~"))
    }

    @Test fun rejectsUnclosedQuotesAndNul() {
        listOf("'unfinished", "\"unfinished", "hello\u0000world").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { CommandArgs.parse(input) }
        }
    }
}
