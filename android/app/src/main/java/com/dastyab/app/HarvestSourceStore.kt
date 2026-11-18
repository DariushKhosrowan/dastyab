package com.dastyab.app

import android.content.Context
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HarvestSourceStore — full user-editable GitHub URLs and Telegram channels manager
 * for DastYab's Smart Harvester, plus a parallel TCP latency benchmarker for harvested configs.
 *
 * Smart URL/channel normalizers automatically handle:
 *  • `https://github.com/owner/repo/blob/branch/path` → `https://raw.githubusercontent.com/owner/repo/branch/path`
 *  • `@channel`, `t.me/channel`, `https://t.me/s/channel` → `channel`
 *  • Bulk multi-line paste (comma / newline / whitespace separated)
 */
object HarvestSourceStore {

    private const val PREFS_NAME = "dastyab_harvest_sources"
    private const val KEY_GITHUB_URLS = "github_urls"
    private const val KEY_TELEGRAM_CHANNELS = "telegram_channels"
    private const val KEY_DISABLED_DEFAULTS = "disabled_defaults"

    data class ConfigProbeResult(
        val uri: String,
        val protocol: String,
        val host: String,
        val port: Int,
        val latencyMs: Long,
        val reachable: Boolean
    )

    /** Converts standard GitHub web URLs (`blob`) to raw URLs so users can paste any GitHub link. */
    fun normalizeGithubUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) return null

        val githubBlobRegex = Regex(
            """^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val match = githubBlobRegex.matchEntire(trimmed)
        if (match != null) {
            val (owner, repo, branch, path) = match.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        }
        return trimmed
    }

    /** Normalizes `@channel`, `t.me/channel`, `https://t.me/s/channel`, or bare username to `channel`. */
    fun normalizeTelegramChannel(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.removePrefix("www.")
        s = s.removePrefix("t.me/s/").removePrefix("t.me/").removePrefix("telegram.me/s/").removePrefix("telegram.me/")
        s = s.removePrefix("@")
        s = s.substringBefore('/').substringBefore('?').trim()
        if (s.length < 3 || !s.all { it.isLetterOrDigit() || it == '_' }) return null
        return s
    }

    /** Parses bulk multi-line / comma-separated GitHub URLs and normalizes them. */
    fun parseBulkGithubUrls(bulkText: String): List<String> {
        return bulkText
            .split('\n', '\r', ',', ' ', '\t')
            .mapNotNull { normalizeGithubUrl(it) }
            .distinct()
    }

    /** Parses bulk multi-line / comma-separated Telegram channels and normalizes them. */
    fun parseBulkTelegramChannels(bulkText: String): List<String> {
        return bulkText
            .split('\n', '\r', ',', ' ', '\t')
            .mapNotNull { normalizeTelegramChannel(it) }
            .distinct()
    }

    fun getCustomGithubUrls(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_GITHUB_URLS, "") ?: ""
        return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    fun saveCustomGithubUrls(context: Context, urls: List<String>) {
        val clean = urls.mapNotNull { normalizeGithubUrl(it) }.distinct()
        prefs(context).edit().putString(KEY_GITHUB_URLS, clean.joinToString("\n")).apply()
    }

    fun addGithubUrls(context: Context, bulkText: String): Int {
        val incoming = parseBulkGithubUrls(bulkText)
        if (incoming.isEmpty()) return 0
        val merged = (incoming + getCustomGithubUrls(context)).distinct()
        saveCustomGithubUrls(context, merged)
        return incoming.size
    }

    fun removeGithubUrl(context: Context, url: String) {
        val updated = getCustomGithubUrls(context).filterNot { it == url }
        saveCustomGithubUrls(context, updated)
    }

    fun getCustomTelegramChannels(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_TELEGRAM_CHANNELS, "") ?: ""
        return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    fun saveCustomTelegramChannels(context: Context, channels: List<String>) {
        val clean = channels.mapNotNull { normalizeTelegramChannel(it) }.distinct()
        prefs(context).edit().putString(KEY_TELEGRAM_CHANNELS, clean.joinToString("\n")).apply()
    }

    fun addTelegramChannels(context: Context, bulkText: String): Int {
        val incoming = parseBulkTelegramChannels(bulkText)
        if (incoming.isEmpty()) return 0
        val merged = (incoming + getCustomTelegramChannels(context)).distinct()
        saveCustomTelegramChannels(context, merged)
        return incoming.size
    }

    fun removeTelegramChannel(context: Context, channel: String) {
        val updated = getCustomTelegramChannels(context).filterNot { it.equals(channel, ignoreCase = true) }
        saveCustomTelegramChannels(context, updated)
    }

    fun resetToDefaults(context: Context) {
        prefs(context).edit()
            .remove(KEY_GITHUB_URLS)
            .remove(KEY_TELEGRAM_CHANNELS)
            .remove(KEY_DISABLED_DEFAULTS)
            .apply()
    }

    /** Extracts `(host, port)` from any proxy URI (`vless://`, `trojan://`, `hy2://`, `tuic://`, `ss://`) for TCP ping. */
    fun extractHostAndPort(uri: String): Pair<String, Int>? {
        val vless = TunnelConfig.parseVlessUri(uri)
        if (vless != null) return vless.host to vless.port
        return try {
            val parsed = URI(uri.trim())
            val host = parsed.host?.trim().orEmpty()
            if (host.isEmpty()) return null
            val port = if (parsed.port in 1..65535) parsed.port else 443
            host to port
        } catch (_: Exception) {
            null
        }
    }

    /** Benchmarks up to [maxToTest] harvested configs in parallel over TCP and sorts reachable ones fastest-first. */
    fun benchmarkHarvestedConfigs(
        configs: List<String>,
        maxToTest: Int = 25,
        timeoutMs: Int = 1800
    ): List<ConfigProbeResult> {
        val candidates = configs.take(maxToTest).mapNotNull { uri ->
            extractHostAndPort(uri)?.let { (host, port) -> Triple(uri, host, port) }
        }
        if (candidates.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(8)
        return try {
            val tasks = candidates.map { (uri, host, port) ->
                Callable {
                    val ms = CleanEndpointScout.probeTcpLatency(host, port, timeoutMs)
                    ConfigProbeResult(
                        uri = uri,
                        protocol = ConfigHarvester.protocolOf(uri),
                        host = host,
                        port = port,
                        latencyMs = ms,
                        reachable = ms >= 0
                    )
                }
            }
            pool.invokeAll(tasks, 12, TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .filter { it.reachable }
                .sortedBy { it.latencyMs }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
