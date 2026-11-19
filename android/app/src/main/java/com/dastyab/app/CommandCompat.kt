package com.dastyab.app

/**
 * CommandCompat — Android compatibility for dastyab CLI commands.
 *
 * - Intercepts commands that launch web-based Google services (`aistudio`, `flow`)
 *   so Android handles them natively through DastYab's built-in Smart/Split VPN
 *   tunnel and opens them cleanly in the system/in-app browser.
 * - Flags Linux-OS-only subcommands (`platform`, `subscribe`, `update-apply`,
 *   `daemon install-systemd`, `dns-tunnel deploy|provision`) with exact Android
 *   equivalents instead of failing obscurely on missing systemd/ssh/root/tmp.
 * - Routes all other Rust CLI commands (`deploy`, `find`, `scan`, `verify`,
 *   `accounts`, `configs`, `export`, `zones`, `dns`, `routes`, `multi-worker`,
 *   etc.) natively to `libdastyab.so`.
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

    /** Check [args] (the CLI args typed by the user, optionally including global flags) before executing them. */
    fun check(args: List<String>): Verdict {
        if (args.isEmpty()) return Verdict.RunNative
        val subcommandArgs = stripLeadingGlobalFlags(args)
        if (subcommandArgs.isEmpty()) return Verdict.RunNative
        val head = subcommandArgs[0].lowercase()
        val second = subcommandArgs.getOrNull(1)?.lowercase()
        return when (head) {
            "aistudio" -> Verdict.OpenAistudio(AISTUDIO_URL)
            "flow" -> Verdict.OpenFlow(FLOW_URL)
            "platform" -> Verdict.Blocked(
                reason = "Linux `ip` / `iptables` fwmark rules require Linux root privileges and are not used on Android.",
                hint = "Use DastYab's built-in Android VpnService (Smart / Global / Split / DNS Blackout modes) from the main screen or Tunnel Settings."
            )
            "subscribe" -> Verdict.Blocked(
                reason = "The local subscription HTTP daemon writes Linux /tmp lock files that are read-only in Android app sandboxes.",
                hint = "Open Cloudflare Worker & Config Studio → Export (VLESS link, v2rayN JSON, sing-box JSON, Clash YAML) or Share QR instead."
            )
            "update-apply" -> Verdict.Blocked(
                reason = "On Android, libdastyab.so lives inside the read-only APK native library directory and cannot self-overwrite.",
                hint = "Use `update-check` to inspect the latest version or tap Releases on the main screen to download an updated APK."
            )
            "daemon" -> when (second) {
                "install-systemd" -> Verdict.Blocked(
                    reason = "systemd is a Linux init service not available on Android.",
                    hint = "Enable '5-min Smart Auto-Failover' and 'Auto-reconnect on device boot' inside Tunnel Settings instead."
                )
                else -> Verdict.RunNative
            }
            "dns-tunnel" -> when (second) {
                "deploy", "provision" -> Verdict.Blocked(
                    reason = "Remote VPS deployment uses desktop OpenSSH (`ssh`, `scp`, `ssh-keygen`) which is unavailable in the Android sandbox.",
                    hint = "Use `dns-tunnel worker`, `dns-tunnel resolvers`, `dns-tunnel status`, or Emergency DNS Blackout VPN on Android."
                )
                else -> Verdict.RunNative
            }
            else -> Verdict.RunNative
        }
    }

    private fun stripLeadingGlobalFlags(args: List<String>): List<String> {
        var i = 0
        while (i < args.size) {
            val tok = args[i]
            if (!tok.startsWith("-")) break
            if ((tok == "--profile" || tok == "--lang") && i + 1 < args.size) {
                i += 2
            } else {
                i += 1
            }
        }
        return if (i < args.size) args.subList(i, args.size) else emptyList()
    }

    /** Title shown for a blocked command. */
    fun blockedTitle(v: Verdict.Blocked): String = "Command not available on Android"

    /** Friendly explanation shown for a blocked command. */
    fun blockedMessage(v: Verdict.Blocked): String = buildString {
        append(v.reason)
        v.hint?.let { append("\n\n$it") }
    }
}
