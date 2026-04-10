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
import android.widget.Toast
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

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
            BackupManager(prefs).fromJson(json)
            Toast.makeText(this, "Settings restored", Toast.LENGTH_SHORT).show()
            finishOnboarding()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // onResume detects acceptance; callback handles denial (user backed out without selecting).
    private val requestRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            return@registerForActivityResult
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        setContentView(R.layout.activity_onboarding)
        supportActionBar?.hide()

        cardDark = findViewById(R.id.cardDark)
        cardLight = findViewById(R.id.cardLight)

        updateCardStyles()
        styleActionButton()

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

        findViewById<TextView>(R.id.btnImportSettings).setOnClickListener {
            openBackupLauncher.launch(arrayOf("application/json", "*/*"))
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

    private fun styleActionButton() {
        val density = resources.displayMetrics.density
        findViewById<TextView>(R.id.btnSetDefault).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(Color.TRANSPARENT)
            setStroke((1.5f * density).toInt(), Color.parseColor("#8888FF"))
        }
    }

    private fun updateCardStyles() {
        val density = resources.displayMetrics.density
        listOf(cardDark, cardLight).forEachIndexed { index, card ->
            val theme = themes[index]
            val isSelected = index == selectedTheme
            val stroke = if (isSelected) theme.strokeSelected else theme.strokeUnselected
            val strokeWidth = if (isSelected) 2f else 1f
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(theme.cardFill)
                setStroke((strokeWidth * density).toInt(), stroke)
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
    }

    private fun finishOnboarding() {
        prefs.onboardingComplete = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
