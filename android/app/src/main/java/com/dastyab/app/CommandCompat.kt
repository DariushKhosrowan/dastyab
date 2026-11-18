package com.dastyab.app

/**
 * CommandCompat — Android compatibility for dastyab CLI commands.
 *
 * Intercepts commands that launch web-based Google services (`aistudio`, `flow`)
 * so Android handles them natively through DastYab's built-in Smart/Split VPN
 * tunnel and opens them cleanly in the system browser.
 */
object CommandCompat {

    const val AISTUDIO_URL = "https://aistudio.google.com/"
    const val FLOW_URL = "https://flow.google.com/"

    /** Result of checking whether a command can run on this device. */
    sealed class Verdict {
        /** Safe to execute the bundled binary normally. */
        object RunNative : Verdict()
        /** Open Google AI Studio via DastYab's built-in VPN tunnel + browser. */
        class OpenAistudio(val url: String) : Verdict()
        /** Open Google Flow via DastYab's built-in VPN tunnel + browser. */
        class OpenFlow(val url: String) : Verdict()
        /** Cannot run on Android; [reason] explains why, [hint] offers an alternative. */
        class Blocked(val reason: String, val hint: String?) : Verdict()
    }

    /** Check [args] (the CLI args typed by the user) before executing them. */
    fun check(args: List<String>): Verdict {
        if (args.isEmpty()) return Verdict.RunNative
        val head = args[0].lowercase()
        return when (head) {
            "aistudio" -> Verdict.OpenAistudio(AISTUDIO_URL)
            "flow" -> Verdict.OpenFlow(FLOW_URL)
            else -> Verdict.RunNative
        }
    }

    /** Title shown for a blocked command. */
    fun blockedTitle(v: Verdict.Blocked): String = "Command not available on Android"

    /** Friendly explanation shown for a blocked command. */
    fun blockedMessage(v: Verdict.Blocked): String = buildString {
        append(v.reason)
        v.hint?.let { append("\n\n$it") }
    }
}
