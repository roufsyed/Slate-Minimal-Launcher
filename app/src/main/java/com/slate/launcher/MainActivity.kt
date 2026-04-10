package com.slate.launcher

import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = PreferencesManager(this)
        super.onCreate(savedInstanceState)

        // Skip redirect if Slate is already set as the default launcher. This covers the
        // race window where Android fires the HOME intent immediately after the user
        // accepts the role, before OnboardingActivity's onResume has set the flag.
        if (!prefs.onboardingComplete && !isDefaultLauncher()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        applySystemBarColors()

        // Never exit — this is a launcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onResume() {
        super.onResume()
        if (prefs.followSystemTheme) {
            val nightMode = resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val targetBg = if (isDark) "#000000" else "#FFFFFF"
            val targetText = if (isDark) "#808080" else "#333333"
            if (prefs.backgroundColor != targetBg || prefs.appTextColor != targetText) {
                prefs.backgroundColor = targetBg
                prefs.appTextColor = targetText
            }
        }
        applySystemBarColors()
        requestedOrientation = if (prefs.lockOrientation)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun applySystemBarColors() {
        val color = parseColorSafe(prefs.backgroundColor)
        window.navigationBarColor = color

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (prefs.hideStatusBar) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.statusBars())
            window.statusBarColor = color
            val isLight = isColorLight(color)
            controller.isAppearanceLightStatusBars = isLight
            controller.isAppearanceLightNavigationBars = isLight
        }
    }

    private fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        return info?.activityInfo?.packageName == packageName
    }

    companion object {
        fun parseColorSafe(hex: String, fallback: Int = Color.BLACK): Int = try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }

        fun isColorLight(color: Int): Boolean {
            val r = Color.red(color) / 255.0
            val g = Color.green(color) / 255.0
            val b = Color.blue(color) / 255.0
            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return luminance > 0.5
        }

        fun applyColorToLockscreen(context: Context, colorInt: Int): Boolean {
            return try {
                val wm = WallpaperManager.getInstance(context)
                val size = wm.desiredMinimumWidth.coerceAtLeast(1) to
                        wm.desiredMinimumHeight.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawRect(
                    0f, 0f, size.first.toFloat(), size.second.toFloat(),
                    Paint().apply { color = colorInt }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                } else {
                    wm.setBitmap(bitmap)
                }
                bitmap.recycle()
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
