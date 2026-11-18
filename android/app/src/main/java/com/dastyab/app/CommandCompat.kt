package com.dastyab.app

/**
 * CommandCompat — Android compatibility for dastyab CLI commands.
 *
 * The bundled Rust CLI is a single static binary (no Python, no Node), but some
 * CLI subcommands shell out to external helpers that simply do not exist on
 * Android:
 *   • `aistudio` → needs `python3` + `dastyab-aistudio.py` + `xray` + `curl`
 *   • `flow`     → needs `xray` + `curl`
 *
 * Running them on Android produces cryptic "Permission denied" / "command not
 * found" errors. This object intercepts those commands *before* they reach the
 * binary and either handles them natively (aistudio opens in a browser) or
 * shows a friendly, actionable message instead of a raw traceback.
 *
 * Note on `aistudio`: Google AI Studio is reachable directly from most
 * connections (app + login + API all work, no Tor). So on Android we open
 * https://aistudio.google.com/ in the system browser — no Python, no xray, no
 * tunnel required.
 */
object CommandCompat {

    /** Result of checking whether a command can run on this device. */
    sealed class Verdict {
        /** Safe to execute the bundled binary normally. */
        object RunNative : Verdict()
        /** Open Google AI Studio in the system browser instead of the binary. */
        class OpenAistudio(val url: String) : Verdict()
        /** Cannot run on Android; [reason] explains why, [hint] offers an alternative. */
        class Blocked(val reason: String, val hint: String?) : Verdict()
    }

    /** Check [args] (the CLI args typed by the user) before executing them. */
    fun check(args: List<String>): Verdict {
        if (args.isEmpty()) return Verdict.RunNative
        val head = args[0].lowercase()
        return when {
            head == "aistudio" -> {
                // Handled natively: open AI Studio in the browser. Works on
                // Android without Python / xray / curl.
                Verdict.OpenAistudio("https://aistudio.google.com/")
            }
            head == "flow" -> {
                Verdict.Blocked(
                    reason = "The 'flow' command needs xray and curl, which aren't available on Android.",
                    hint = "Try 'verify', 'accounts', 'profiles', or 'token-list' instead."
                )
            }
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
