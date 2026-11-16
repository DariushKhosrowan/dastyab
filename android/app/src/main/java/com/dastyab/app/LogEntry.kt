package com.dastyab.app

/**
 * A single structured log entry. Every log line written to file is a
 * self-contained record with timestamp, thread, tag, level, message,
 * and optional throwable stack trace. This makes it trivial to parse
 * logs externally (e.g. `grep`, `jq`, log aggregation tools).
 */
data class LogEntry(
    val timestampMs: Long,
    val timestampIso: String,
    val thread: String,
    val tag: String,
    val level: String,
    val message: String,
    val throwable: String? = null,
    val appVersion: String? = null,
    val deviceModel: String? = null,
    val sdkInt: Int? = null,
    val abi: String? = null,
    val sessionId: String? = null
) {
    fun toLogLine(): String {
        val sb = StringBuilder()
        sb.append("[$timestampIso]")
        sb.append(" [$thread]")
        sb.append(" [$tag]")
        sb.append(" [$level]")
        if (sessionId != null) sb.append(" [session=$sessionId]")
        sb.append(" $message")
        throwable?.let { sb.append("\n").append(it) }
        return sb.toString()
    }

    fun toJson(): String {
        return buildString {
            append("{")
            append("\"timestampMs\":$timestampMs,")
            append("\"timestampIso\":\"$timestampIso\",")
            append("\"thread\":\"$thread\",")
            append("\"tag\":\"$tag\",")
            append("\"level\":\"$level\",")
            append("\"message\":\"${message.replace("\"", "\\\"")}\"")
            throwable?.let { append(",\"throwable\":\"${it.replace("\"", "\\\"").replace("\n", "\\n")}\"") }
            appVersion?.let { append(",\"appVersion\":\"$it\"") }
            deviceModel?.let { append(",\"deviceModel\":\"$it\"") }
            sdkInt?.let { append(",\"sdkInt\":$sdkInt") }
            abi?.let { append(",\"abi\":\"$it\"") }
            sessionId?.let { append(",\"sessionId\":\"$sessionId\"") }
            append("}")
        }
    }
}