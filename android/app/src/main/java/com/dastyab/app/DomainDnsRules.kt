package com.dastyab.app

import android.content.Context
import android.content.SharedPreferences

/**
 * DomainDnsRules — Per-domain custom DNS routing & built-in Iranian site bypass engine.
 *
 * Pure-Kotlin matching logic so every suffix/override rule is unit-testable on the plain JVM.
 *
 * Precedence (highest to lowest):
 *  1. User custom Force-DoH domains (always use encrypted DoH, even if `.ir`)
 *  2. User custom Bypass domains (always use fast local/ISP DNS)
 *  3. Built-in Iranian TLD (`.ir`) & curated banking/local service domains (when [Rules.iranBypassEnabled] is true)
 *  4. Default: Encrypted DoH failover chain
 */
object DomainDnsRules {

    const val PREFS_NAME = "dastyab_domain_dns_rules"
    private const val KEY_IRAN_BYPASS = "iran_bypass_enabled"
    private const val KEY_CUSTOM_BYPASS = "custom_bypass_domains"
    private const val KEY_CUSTOM_FORCE_DOH = "custom_force_doh_domains"

    enum class Decision(val label: String) {
        FORCE_DOH("Encrypted DoH (Forced)"),
        CUSTOM_BYPASS("Local DNS Bypass (Custom)"),
        IRAN_BYPASS("Local DNS Bypass (Iran / .ir)"),
        DEFAULT_DOH("Encrypted DoH (Default)")
    }

    data class Rules(
        val iranBypassEnabled: Boolean = true,
        val customBypassDomains: Set<String> = emptySet(),
        val customForceDohDomains: Set<String> = emptySet()
    )

    /** Curated non-.ir Iranian banking, e-commerce, messaging, and telecom domains. */
    val BUILTIN_IRAN_DOMAINS: Set<String> = setOf(
        // E-commerce & everyday services
        "digikala.com",
        "dkstatics-public.digikala.com",
        "snapp.cab",
        "snapp.market",
        "snappfood.ir",
        "tapsi.cab",
        "divar.ir",
        "sheypoor.com",
        "torob.com",
        "emalls.ir",
        "basalam.com",
        "alibaba.ir",
        "mrbilit.com",
        "flightio.com",
        "jabama.com",
        // Media & app stores
        "aparat.com",
        "filimo.com",
        "namava.ir",
        "telewebion.com",
        "cafebazaar.ir",
        "myket.ir",
        "varzesh3.com",
        // Local messengers & telecom
        "eitaa.com",
        "rubika.ir",
        "splus.ir",
        "ble.ir",
        "bale.ai",
        "igap.net",
        "irancell.ir",
        "mci.ir",
        "rightel.ir",
        "shatel.ir",
        "asiatech.ir",
        "parsonline.com",
        // Banking & payment gateways
        "shaparak.ir",
        "bmi.ir",
        "bankmellat.ir",
        "behpardakht.com",
        "sb24.ir",
        "bpi.ir",
        "tejaratbank.ir",
        "banksepah.ir",
        "bsi.ir",
        "refah-bank.ir",
        "bank-maskan.ir",
        "enbank.ir",
        "parsian-bank.ir",
        "sinabank.ir",
        "karafarinbank.ir",
        "ayandehbank.ir",
        "blu.bank",
        "blubank.com",
        "zarinpal.com",
        "payping.ir",
        "idpay.ir"
    )

    fun load(context: Context): Rules {
        val p = prefs(context)
        val bypass = p.getStringSet(KEY_CUSTOM_BYPASS, emptySet()) ?: emptySet()
        val forceDoh = p.getStringSet(KEY_CUSTOM_FORCE_DOH, emptySet()) ?: emptySet()
        return Rules(
            iranBypassEnabled = p.getBoolean(KEY_IRAN_BYPASS, true),
            customBypassDomains = normalizeDomainSet(bypass),
            customForceDohDomains = normalizeDomainSet(forceDoh)
        )
    }

    fun save(context: Context, rules: Rules) {
        prefs(context).edit()
            .putBoolean(KEY_IRAN_BYPASS, rules.iranBypassEnabled)
            .putStringSet(KEY_CUSTOM_BYPASS, normalizeDomainSet(rules.customBypassDomains))
            .putStringSet(KEY_CUSTOM_FORCE_DOH, normalizeDomainSet(rules.customForceDohDomains))
            .apply()
    }

    fun setIranBypassEnabled(context: Context, enabled: Boolean) {
        save(context, load(context).copy(iranBypassEnabled = enabled))
    }

    fun addBypassDomain(context: Context, raw: String): Boolean {
        val norm = normalizeDomain(raw) ?: return false
        val cur = load(context)
        save(
            context,
            cur.copy(
                customBypassDomains = cur.customBypassDomains + norm,
                customForceDohDomains = cur.customForceDohDomains - norm
            )
        )
        return true
    }

    fun removeBypassDomain(context: Context, raw: String) {
        val norm = normalizeDomain(raw) ?: return
        val cur = load(context)
        save(context, cur.copy(customBypassDomains = cur.customBypassDomains - norm))
    }

    fun addForceDohDomain(context: Context, raw: String): Boolean {
        val norm = normalizeDomain(raw) ?: return false
        val cur = load(context)
        save(
            context,
            cur.copy(
                customForceDohDomains = cur.customForceDohDomains + norm,
                customBypassDomains = cur.customBypassDomains - norm
            )
        )
        return true
    }

    fun removeForceDohDomain(context: Context, raw: String) {
        val norm = normalizeDomain(raw) ?: return
        val cur = load(context)
        save(context, cur.copy(customForceDohDomains = cur.customForceDohDomains - norm))
    }

    /**
     * Normalizes user-entered domains (strips scheme, path, leading `*.` or `.`, port, whitespace).
     * Returns null if invalid.
     */
    fun normalizeDomain(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().lowercase()
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)
        val slashIdx = s.indexOf('/')
        if (slashIdx >= 0) s = s.substring(0, slashIdx)
        val colonIdx = s.indexOf(':')
        if (colonIdx >= 0) s = s.substring(0, colonIdx)
        s = s.removePrefix("*.").trim('.')
        if (s.length < 2 || !s.contains('.')) return null
        if (s.any { !it.isLetterOrDigit() && it != '.' && it != '-' }) return null
        return s
    }

    fun normalizeDomainSet(raw: Collection<String>): Set<String> {
        return raw.mapNotNull { normalizeDomain(it) }.toSortedSet()
    }

    /** Evaluates the routing decision for [rawDomain] against [rules]. */
    fun evaluate(rawDomain: String?, rules: Rules): Decision {
        val domain = normalizeDomain(rawDomain) ?: return Decision.DEFAULT_DOH

        if (matchesAnySuffix(domain, rules.customForceDohDomains)) {
            return Decision.FORCE_DOH
        }
        if (matchesAnySuffix(domain, rules.customBypassDomains)) {
            return Decision.CUSTOM_BYPASS
        }
        if (rules.iranBypassEnabled) {
            if (domain.endsWith(".ir") || domain == "ir" || matchesAnySuffix(domain, BUILTIN_IRAN_DOMAINS)) {
                return Decision.IRAN_BYPASS
            }
        }
        return Decision.DEFAULT_DOH
    }

    /** Returns true if [rawDomain] should bypass encrypted DoH and resolve via local/ISP DNS. */
    fun shouldBypassToLocalDns(rawDomain: String?, rules: Rules): Boolean {
        return when (evaluate(rawDomain, rules)) {
            Decision.CUSTOM_BYPASS, Decision.IRAN_BYPASS -> true
            Decision.FORCE_DOH, Decision.DEFAULT_DOH -> false
        }
    }

    private fun matchesAnySuffix(domain: String, suffixes: Set<String>): Boolean {
        for (suffix in suffixes) {
            if (domain == suffix || domain.endsWith(".$suffix")) return true
        }
        return false
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
