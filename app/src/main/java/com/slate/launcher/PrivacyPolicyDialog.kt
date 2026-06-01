package com.slate.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

/**
 * Shows the bundled privacy policy in a themed in-app dialog. The .md file is generated at
 * build time by the `copyPrivacyPolicy` Gradle task and loaded from assets at runtime.
 *
 * If the asset is missing (e.g., a corrupted install), falls back to opening the canonical
 * GitHub URL externally so the user can always read it.
 */
object PrivacyPolicyDialog {

    private const val ASSET_NAME = "PRIVACY_POLICY.md"
    private const val FALLBACK_URL =
        "https://github.com/roufsyed/Slate-Minimal-Launcher/blob/master/PRIVACY_POLICY.md"

    /**
     * Reference to the currently-showing dialog (if any) so the host Activity can dismiss it in
     * onDestroy() to avoid a WindowLeaked exception on configuration change.
     */
    private var activeDialog: Dialog? = null

    /** Dismiss any showing dialog. Safe to call from Activity.onDestroy(). */
    fun dismissActive() {
        activeDialog?.let { runCatching { it.dismiss() } }
        activeDialog = null
    }

    /** Show the dialog. [bgColor] / [primary] / [secondary] / [accent] let the caller theme it
     *  appropriately for the surface that triggered the dialog (Onboarding is dark-only). */
    fun show(
        activity: Activity,
        bgColor: Int = Color.BLACK,
        primary: Int = Color.WHITE,
        secondary: Int = Color.parseColor("#999999"),
        accent: Int = Color.parseColor("#8888FF")
    ) {
        val markdown = loadMarkdown(activity) ?: run {
            Toast.makeText(activity, "Opening privacy policy in browser…", Toast.LENGTH_SHORT).show()
            openExternal(activity)
            return
        }

        // Dismiss any prior dialog before creating a new one (defensive - also clears stale ref
        // if the activity that created it has since been destroyed).
        dismissActive()

        val dialog = Dialog(activity, R.style.SlateDialogTheme)
        dialog.setContentView(R.layout.dialog_privacy_policy)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = activity.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.9).toInt(),
            (dm.heightPixels * 0.85).toInt()
        )
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setCanceledOnTouchOutside(true)

        val density = activity.resources.displayMetrics.density
        val root = dialog.findViewById<TextView>(R.id.privacyDialogTitle).parent as ViewGroup
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = density * 12
        }

        dialog.findViewById<TextView>(R.id.privacyDialogTitle).setTextColor(accent)

        dialog.findViewById<TextView>(R.id.privacyDialogBody).apply {
            setTextColor(primary)
            setLinkTextColor(accent)
            text = renderMarkdown(markdown, accent = accent)
        }

        dialog.findViewById<TextView>(R.id.btnPrivacyClose).apply {
            setTextColor(accent)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
    }

    private fun loadMarkdown(ctx: Context): String? = try {
        ctx.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    } catch (_: IOException) {
        null
    }

    private fun openExternal(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(FALLBACK_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Last resort - nothing else we can do.
        }
    }

    // ── Tiny in-house Markdown renderer ───────────────────────────
    //
    // Supports only what PRIVACY_POLICY.md actually uses:
    //   `# H1`, `## H2`, `**bold**`, `` `code` ``, `- bullet`, GFM tables, blank-line paragraphs.
    // Anything fancier (links, images, nested lists) renders as plain text rather than crash.

    private fun renderMarkdown(text: String, accent: Int): Spanned {
        val sb = SpannableStringBuilder()
        var prevType: String? = null  // h1/h2/bullet/table/para; null = blank

        for (raw in text.lines()) {
            val line = raw.trim()
            val type = classify(line)

            // Blank lines just mark a paragraph break for the NEXT block.
            if (type == null) {
                prevType = null
                continue
            }
            // Skip table-header separator (e.g., |------|----|) entirely.
            if (type == "table-sep") continue

            if (sb.isNotEmpty()) {
                val sameGroup = (type == prevType) && (type == "bullet" || type == "table")
                sb.append(if (sameGroup) "\n" else "\n\n")
            }

            when (type) {
                "h1" -> appendStyled(sb, line.removePrefix("# "), 1.3f, accent)
                "h2" -> appendStyled(sb, line.removePrefix("## "), 1.1f, accent)
                "bullet" -> {
                    sb.append("•  ")
                    appendInline(sb, line.removePrefix("- "))
                }
                "table" -> {
                    val cells = line.trim('|').split("|").map { it.trim() }
                    sb.append(cells.joinToString("   →   "))
                }
                "para" -> appendInline(sb, line)
            }
            prevType = type
        }
        return sb
    }

    private fun classify(line: String): String? = when {
        line.isEmpty() -> null
        line.startsWith("# ") -> "h1"
        line.startsWith("## ") -> "h2"
        line.startsWith("- ") -> "bullet"
        // Header separator: only pipes, spaces, dashes, colons.
        line.startsWith("|") && line.endsWith("|") &&
                line.trim('|').all { it == '-' || it == ':' || it == '|' || it.isWhitespace() } -> "table-sep"
        line.startsWith("|") && line.endsWith("|") -> "table"
        else -> "para"
    }

    private fun appendStyled(
        sb: SpannableStringBuilder,
        text: String,
        sizeMultiplier: Float,
        color: Int
    ) {
        val start = sb.length
        appendInline(sb, text)
        val end = sb.length
        sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(sizeMultiplier), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Handles inline `**bold**` and `` `code` ``. Anything else copies through verbatim. */
    private fun appendInline(sb: SpannableStringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val close = text.indexOf("**", i + 2)
                if (close > 0) {
                    val start = sb.length
                    sb.append(text, i + 2, close)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 2
                    continue
                }
            }
            if (ch == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > 0) {
                    val start = sb.length
                    sb.append(text, i + 1, close)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 1
                    continue
                }
            }
            sb.append(ch)
            i++
        }
    }
}
