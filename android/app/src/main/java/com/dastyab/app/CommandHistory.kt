package com.dastyab.app

import java.io.File

/**
 * CommandHistory — persists the most recent CLI commands (with timestamps)
 * to a file inside the app's private filesDir.
 *
 * Format: one entry per line, "<epochMillis>\t<command>" — new entries are
 * appended at the end; on load we keep the newest [MAX_ENTRIES] entries.
 * All access is synchronized so it is safe to call from any thread.
 */
object CommandHistory {

    private const val FILE_NAME = "command_history.txt"
    private const val MAX_ENTRIES = 50

    data class Entry(val timestampMs: Long, val command: String)

    @Volatile
    private var file: File? = null

    /** Cache of entries, newest last. */
    private val entries = mutableListOf<Entry>()

    fun init(filesDir: File) {
        synchronized(this) {
            file = File(filesDir, FILE_NAME)
            entries.clear()
            try {
                val f = file!!
                if (f.exists()) {
                    for (line in f.readLines()) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        val idx = trimmed.indexOf('\t')
                        if (idx <= 0) continue
                        val ts = trimmed.substring(0, idx).toLongOrNull() ?: continue
                        val cmd = trimmed.substring(idx + 1)
                        if (cmd.isNotEmpty()) entries.add(Entry(ts, cmd))
                    }
                }
            } catch (e: Exception) {
                DastyabLogger.w("CommandHistory", e, "Failed to load command history")
            }
            // Trim to MAX_ENTRIES (keep newest at the end).
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
        }
    }

    /** Save a command with the current timestamp. Deduplicates consecutive repeats. */
    fun add(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        synchronized(this) {
            val last = entries.lastOrNull()
            if (last != null && last.command == cmd) {
                // Refresh timestamp of identical consecutive command.
                entries[entries.size - 1] = Entry(System.currentTimeMillis(), cmd)
            } else {
                entries.add(Entry(System.currentTimeMillis(), cmd))
                while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            }
            persist()
        }
    }

    /** All entries, newest first. */
    fun getAll(): List<Entry> {
        synchronized(this) { return entries.reversed() }
    }

    /** Just the command strings, newest first. */
    fun getCommands(): List<String> = getAll().map { it.command }

    fun clear() {
        synchronized(this) {
            entries.clear()
            persist()
        }
        DastyabLogger.i("CommandHistory", "Command history cleared")
    }

    fun size(): Int {
        synchronized(this) { return entries.size }
    }

    private fun persist() {
        val f = file ?: return
        try {
            val sb = StringBuilder()
            for (e in entries) {
                sb.append(e.timestampMs).append('\t').append(e.command).append('\n')
            }
            f.writeText(sb.toString())
        } catch (e: Exception) {
            DastyabLogger.w("CommandHistory", e, "Failed to persist command history")
        }
    }
}
