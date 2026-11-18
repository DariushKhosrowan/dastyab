package com.dastyab.app

import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * DomainDnsDialog — Interactive Per-Domain Custom DNS Rules & Iranian Site Bypass manager.
 *
 * Allows users to:
 *  • Toggle built-in `.ir` + Iranian banking/local services bypass
 *  • Add/remove custom Bypass domains (fast local DNS)
 *  • Add/remove custom Force-DoH domains (always encrypted DoH)
 *  • Live-test any domain to see how DastYab routes its DNS query
 */
object DomainDnsDialog {

    fun show(context: Context, onChanged: (() -> Unit)? = null) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        val rules = DomainDnsRules.load(context)

        val cbIran = CheckBox(context).apply {
            text = context.getString(R.string.dns_rules_iran_bypass)
            isChecked = rules.iranBypassEnabled
            setTextColor(context.getColor(R.color.colorTextPrimary))
            setOnCheckedChangeListener { _, isChecked ->
                DomainDnsRules.setIranBypassEnabled(context, isChecked)
                onChanged?.invoke()
            }
        }
        root.addView(cbIran)

        val tvHint = TextView(context).apply {
            text = context.getString(R.string.dns_rules_iran_hint, DomainDnsRules.BUILTIN_IRAN_DOMAINS.size)
            textSize = 12f
            setTextColor(context.getColor(R.color.colorTextSecondary))
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
        }
        root.addView(tvHint)

        val etDomain = EditText(context).apply {
            hint = context.getString(R.string.dns_rules_input_hint)
            setSingleLine(true)
        }
        root.addView(etDomain)

        val tvSummary = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.colorTextSecondary))
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
        }

        fun refreshSummary() {
            val r = DomainDnsRules.load(context)
            val bypassText = if (r.customBypassDomains.isEmpty()) "None" else r.customBypassDomains.joinToString(", ")
            val forceText = if (r.customForceDohDomains.isEmpty()) "None" else r.customForceDohDomains.joinToString(", ")
            tvSummary.text = "Custom Local Bypass (${r.customBypassDomains.size}): $bypassText\n" +
                "Custom Force-DoH (${r.customForceDohDomains.size}): $forceText"
        }
        refreshSummary()

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        val btnAddBypass = Button(context, null, 0, R.style.DastyabChip).apply {
            text = context.getString(R.string.dns_rules_btn_add_bypass)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val input = etDomain.text.toString()
                if (DomainDnsRules.addBypassDomain(context, input)) {
                    etDomain.setText("")
                    refreshSummary()
                    onChanged?.invoke()
                    Toast.makeText(context, "Added to Local Bypass", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Enter a valid domain (e.g. bank.ir)", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnAddForce = Button(context, null, 0, R.style.DastyabChip).apply {
            text = context.getString(R.string.dns_rules_btn_add_doh)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val input = etDomain.text.toString()
                if (DomainDnsRules.addForceDohDomain(context, input)) {
                    etDomain.setText("")
                    refreshSummary()
                    onChanged?.invoke()
                    Toast.makeText(context, "Added to Force-DoH", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Enter a valid domain (e.g. aistudio.google.com)", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnTest = Button(context, null, 0, R.style.DastyabChip).apply {
            text = context.getString(R.string.dns_rules_btn_test)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val input = etDomain.text.toString().trim()
                val decision = DomainDnsRules.evaluate(input, DomainDnsRules.load(context))
                Toast.makeText(context, "$input → ${decision.label}", Toast.LENGTH_LONG).show()
            }
        }
        btnRow.addView(btnAddBypass)
        btnRow.addView(btnAddForce)
        btnRow.addView(btnTest)
        root.addView(btnRow)
        root.addView(tvSummary)

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dns_rules_title))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(context.getString(R.string.dns_rules_btn_clear_custom)) { _, _ ->
                val cur = DomainDnsRules.load(context)
                DomainDnsRules.save(context, cur.copy(customBypassDomains = emptySet(), customForceDohDomains = emptySet()))
                onChanged?.invoke()
                Toast.makeText(context, "Cleared custom DNS rules", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
