package com.slate.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
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

    private val requestRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { finishOnboarding() }

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
            // Mark onboarding complete BEFORE opening the role picker.
            // When the user accepts, Android immediately fires a HOME intent which
            // starts MainActivity. If the flag isn't set here first, MainActivity
            // sees onboarding incomplete and redirects back, forcing the user to
            // tap "Set as Default" a second time.
            prefs.onboardingComplete = true
            requestDefaultLauncher()
        }

        findViewById<TextView>(R.id.btnSkip).setOnClickListener {
            applySelectedTheme()
            finishOnboarding()
        }
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
        finishOnboarding()
    }

    private fun finishOnboarding() {
        prefs.onboardingComplete = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
