package com.dastyab.app

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogEntryTest {

    @Test
    fun `toLogLine includes timestamp tag level and message`() {
        val entry = LogEntry(
            timestampMs = 1699000000000,
            timestampIso = "2023-11-03T12:00:00.000Z",
            thread = "main",
            tag = "Download",
            level = "ERROR",
            message = "Something went wrong",
            throwable = "java.lang.Exception: boom\n  at Foo.bar(Foo.kt:42)",
            appVersion = "1.1.0",
            deviceModel = "Pixel 7",
            sdkInt = 34,
            abi = "arm64-v8a",
            sessionId = "abc123ef"
        )

        val line = entry.toLogLine()
        assertTrue(line.contains("[2023-11-03T12:00:00.000Z]"))
        assertTrue(line.contains("[main]"))
        assertTrue(line.contains("[Download]"))
        assertTrue(line.contains("[ERROR]"))
        assertTrue(line.contains("[session=abc123ef]"))
        assertTrue(line.contains("Something went wrong"))
        assertTrue(line.contains("java.lang.Exception: boom"))
        assertTrue(line.contains("Foo.bar(Foo.kt:42)"))
    }

    @Test
    fun `toLogLine without throwable works`() {
        val entry = LogEntry(
            timestampMs = 0,
            timestampIso = "2023-01-01T00:00:00.000Z",
            thread = "main",
            tag = "Test",
            level = "INFO",
            message = "hello world"
        )
        val line = entry.toLogLine()
        assertTrue(line.contains("hello world"))
        assertTrue(!line.contains("throwable"))
    }

    @Test
    fun `toJson produces valid JSON`() {
        val entry = LogEntry(
            timestampMs = 1234567890,
            timestampIso = "2023-01-01T00:00:00.000Z",
            thread = "main",
            tag = "Test",
            level = "DEBUG",
            message = "test msg",
            sessionId = "sess123"
        )
        val json = entry.toJson()
        assertTrue(json.contains("\"timestampMs\":1234567890"))
        assertTrue(json.contains("\"sessionId\":\"sess123\""))
        assertTrue(json.contains("\"level\":\"DEBUG\""))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun `toJson escapes special characters in message`() {
        val entry = LogEntry(
            timestampMs = 0,
            timestampIso = "2023-01-01T00:00:00.000Z",
            thread = "main",
            tag = "Test",
            level = "INFO",
            message = "say \"hello\""
        )
        val json = entry.toJson()
        assertTrue(json.contains("\\\"hello\\\""))
    }

    @Test
    fun `all fields are non-null when provided`() {
        val entry = LogEntry(
            timestampMs = 1L,
            timestampIso = "iso",
            thread = "t",
            tag = "tag",
            level = "INFO",
            message = "msg",
            throwable = "trace",
            appVersion = "1.0",
            deviceModel = "model",
            sdkInt = 30,
            abi = "abi",
            sessionId = "sess"
        )
        assertEquals(1L, entry.timestampMs)
        assertEquals("iso", entry.timestampIso)
        assertEquals("t", entry.thread)
        assertEquals("tag", entry.tag)
        assertEquals("INFO", entry.level)
        assertEquals("msg", entry.message)
        assertEquals("trace", entry.throwable)
        assertEquals("1.0", entry.appVersion)
        assertEquals("model", entry.deviceModel)
        assertEquals(30, entry.sdkInt)
        assertEquals("abi", entry.abi)
        assertEquals("sess", entry.sessionId)
    }

    @Test
    fun `optional fields default to null`() {
        val entry = LogEntry(
            timestampMs = 0,
            timestampIso = "iso",
            thread = "t",
            tag = "tag",
            level = "INFO",
            message = "msg"
        )
        assertNotNull(entry.timestampMs)
        assertNotNull(entry.timestampIso)
        assertNotNull(entry.thread)
        assertNotNull(entry.tag)
        assertNotNull(entry.level)
        assertNotNull(entry.message)
        assertEquals(null, entry.throwable)
        assertEquals(null, entry.appVersion)
        assertEquals(null, entry.deviceModel)
        assertEquals(null, entry.sdkInt)
        assertEquals(null, entry.abi)
        assertEquals(null, entry.sessionId)
    }
}