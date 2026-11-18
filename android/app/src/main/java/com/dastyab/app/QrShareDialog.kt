package com.dastyab.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * QrShareDialog — Renders any VPN config URI (`vless://`, `trojan://`, etc.) into a crisp
 * offline QR code bitmap with a 4-module quiet zone inside a Royal Gold dialog, with 1-tap
 * Copy and Share actions.
 */
object QrShareDialog {

    fun show(context: Context, title: String, payload: String) {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.qr_empty_config), Toast.LENGTH_SHORT).show()
            return
        }
        val matrix = QrCodeEncoder.encode(trimmed)
        if (matrix == null) {
            Toast.makeText(context, context.getString(R.string.qr_too_long), Toast.LENGTH_LONG).show()
            return
        }

        val bitmap = renderBitmap(matrix, targetSizePx = 640, quietZoneModules = 4)
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        val qrImage = ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val sizePx = (240 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        container.addView(qrImage)

        val preview = TextView(context).apply {
            text = if (trimmed.length > 90) trimmed.take(90) + "…" else trimmed
            textSize = 11f
            setTextColor(context.getColor(R.color.colorTextSecondary))
            gravity = Gravity.CENTER
        }
        container.addView(preview)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(context.getString(R.string.btn_copy)) { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DastYab VPN Config", trimmed))
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(context.getString(R.string.btn_share)) { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, trimmed)
                }
                context.startActivity(Intent.createChooser(shareIntent, title))
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    fun renderBitmap(
        matrix: QrCodeEncoder.QrMatrix,
        targetSizePx: Int = 512,
        quietZoneModules: Int = 4
    ): Bitmap {
        val totalModules = matrix.size + quietZoneModules * 2
        val scale = (targetSizePx / totalModules).coerceAtLeast(2)
        val imgSize = totalModules * scale
        val pixels = IntArray(imgSize * imgSize) { Color.WHITE }

        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.get(x, y)) {
                    val startX = (x + quietZoneModules) * scale
                    val startY = (y + quietZoneModules) * scale
                    for (dy in 0 until scale) {
                        val rowOffset = (startY + dy) * imgSize + startX
                        for (dx in 0 until scale) {
                            pixels[rowOffset + dx] = Color.BLACK
                        }
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, imgSize, imgSize, Bitmap.Config.ARGB_8888)
    }
}
