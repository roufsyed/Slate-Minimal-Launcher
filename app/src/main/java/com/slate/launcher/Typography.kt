package com.slate.launcher

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import java.io.File

/**
 * Typeface and widget-styling helpers shared between the apps list ([AppDrawerFragment]) and
 * the Quick Toggles strip ([com.slate.launcher.widgets.QuickStripManager]). Both surfaces resolve
 * a (family, weight) pref pair to a [Typeface] via the same logic; centralising it here avoids
 * drift and gives the Settings preview a single styling source of truth.
 */
object Typography {

    /**
     * Resolve a (fontFamily, fontWeight) pref pair to a [Typeface].
     *
     * Returns `null` only when BOTH inputs are sentinels (empty family AND zero weight) - the
     * signal that the caller wants NO override and should let the theme default apply. A
     * partial override is still meaningful:
     *   - `("", 700)`               → theme default at bold weight (user picked Weight only)
     *   - `("gf:roboto", 0)`        → Roboto at the default 400 weight
     *   - `("gf:roboto", 700)`      → Roboto Bold
     *   - `("", 0)`                 → returns null, caller skips application entirely
     *
     * This matches the UI: the Settings Weight row shows "Bold" iff `widgetFontWeight=700`, so
     * a user who picks Bold expects their widgets to render bold even if they haven't picked a
     * font family. Apps' rendering never hits the null branch (its `fontFamily` default is
     * non-empty); the null path is exclusively for the widget strip's legacy-look preservation.
     *
     * @param family String pref in one of these forms:
     *               - empty            → use theme default as the base typeface
     *               - `"/abs/path"`    → user-imported font file in app-private storage
     *               - `"gf:<name>"`    → bundled Google downloadable font (R.font.<name>)
     *               - anything else    → system family ("sans-serif", "serif", …)
     * @param weight 0 = no weight override (treated as 400 / regular when the family is set);
     *               otherwise standard 100–900 weight value.
     */
    fun buildTypeface(context: Context, family: String, weight: Int): Typeface? {
        if (family.isEmpty() && weight == 0) return null

        val base: Typeface = when {
            family.isEmpty() -> Typeface.DEFAULT
            family.startsWith("/") ->
                runCatching { Typeface.createFromFile(File(family)) }.getOrNull()
                    ?: Typeface.DEFAULT
            family.startsWith("gf:") -> {
                val resId = googleFontResId(family.removePrefix("gf:"))
                if (resId == 0) Typeface.DEFAULT
                else runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull()
                    ?: Typeface.DEFAULT
            }
            else -> Typeface.create(family, Typeface.NORMAL)
        }

        val effectiveWeight = if (weight == 0) 400 else weight
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, effectiveWeight, false)
        } else {
            Typeface.create(base, if (effectiveWeight >= 700) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * Apply every widget-strip typography pref to a [TextView]. Used by
     * `QuickStripManager.createWidgetView()` AND by the Settings preview so the two renderings
     * are byte-for-byte identical.
     *
     * Does NOT set [TextView.setTextColor] / gravity / click behaviour - those are caller
     * responsibilities (the home strip needs taps; the preview does not).
     */
    fun applyWidgetStyle(
        view: TextView,
        prefs: PreferencesManager,
        context: Context,
        density: Float
    ) {
        view.textSize = prefs.widgetTextSize.toFloat()
        val pad = (prefs.widgetWordGap * density).toInt()
        val vPad = (prefs.widgetLineGap * density).toInt()
        view.setPadding(pad, vPad, pad, vPad)
        buildTypeface(context, prefs.widgetFontFamily, prefs.widgetFontWeight)?.let {
            view.typeface = it
        }
    }

    /**
     * Map the bundled Google Font key (the part after `"gf:"`) to the R.font resource id.
     * Returns 0 for unknown keys - caller falls back to [Typeface.DEFAULT].
     */
    fun googleFontResId(name: String): Int = when (name) {
        "tex_gyre_adventor_bold" -> R.font.tex_gyre_adventor_bold
        "roboto"                 -> R.font.roboto
        "noto_sans"              -> R.font.noto_sans
        "coming_soon"            -> R.font.coming_soon
        "cutive_mono"            -> R.font.cutive_mono
        else                     -> 0
    }
}
