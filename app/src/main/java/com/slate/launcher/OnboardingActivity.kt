package com.slate.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var cardDark: LinearLayout
    private lateinit var cardLight: LinearLayout

    // 0 = dark selected, 1 = light selected
    private var selectedTheme = 0

    private data class Theme(
        val bgColor: String,
        val textColor: String,
        val cardFill: Int,
        val strokeSelected: Int,
        val strokeUnselected: Int
    )

    private val themes = listOf(
        Theme(
            bgColor = "#000000",
            textColor = "#808080",
            cardFill = Color.parseColor("#0D0D0D"),
            strokeSelected = Color.parseColor("#8888FF"),
            strokeUnselected = Color.parseColor("#333333")
        ),
        Theme(
            bgColor = "#FFFFFF",
            textColor = "#333333",
            cardFill = Color.parseColor("#F5F5F5"),
            strokeSelected = Color.parseColor("#333399"),
            strokeUnselected = Color.parseColor("#DDDDDD")
        )
    )

    // onResume detects acceptance; callback handles denial (user backed out without selecting).
    private val requestRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            // User dismissed the picker without selecting Slate — stay on onboarding
            // so they can try again or tap Skip.
            return@registerForActivityResult
        }
        // Accepted — onResume will confirm and advance.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        setContentView(R.layout.activity_onboarding)
        supportActionBar?.hide()

        cardDark = findViewById(R.id.cardDark)
        cardLight = findViewById(R.id.cardLight)

        updateCardStyles()

        cardDark.setOnClickListener {
            selectedTheme = 0
            updateCardStyles()
        }

        cardLight.setOnClickListener {
            selectedTheme = 1
            updateCardStyles()
        }

        findViewById<TextView>(R.id.btnSetDefault).setOnClickListener {
            applySelectedTheme()
            requestDefaultLauncher()
        }

        findViewById<TextView>(R.id.btnSkip).setOnClickListener {
            applySelectedTheme()
            finishOnboarding()
        }

        findViewById<TextView>(R.id.btnPrivacyPolicy).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/roufsyed/Slate-Minimal-Launcher/blob/master/PRIVACY_POLICY.md"))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // After returning from the role picker or Home Settings, check if Slate
        // is now the default launcher and auto-advance if so.
        if (isDefaultLauncher()) {
            finishOnboarding()
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

    private fun applySelectedTheme() {
        val theme = themes[selectedTheme]
        prefs.backgroundColor = theme.bgColor
        prefs.appTextColor = theme.textColor
    }

    private fun updateCardStyles() {
        val density = resources.displayMetrics.density
        listOf(cardDark, cardLight).forEachIndexed { index, card ->
            val theme = themes[index]
            val isSelected = index == selectedTheme
            val stroke = if (isSelected) theme.strokeSelected else theme.strokeUnselected
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(theme.cardFill)
                setStroke((if (isSelected) 2 else 1).times(density).toInt(), stroke)
            }
        }
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                requestRoleLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                )
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        // Don't finish here — onResume will detect acceptance when the user returns.
    }

    private fun finishOnboarding() {
        prefs.onboardingComplete = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
