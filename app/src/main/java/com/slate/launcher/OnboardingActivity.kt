package com.slate.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox

class OnboardingActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var cardDark: LinearLayout
    private lateinit var cardLight: LinearLayout
    private lateinit var checkPrivacy: MaterialCheckBox
    private lateinit var btnSetDefault: TextView
    private lateinit var btnSkip: TextView
    private lateinit var btnImport: TextView

    // 0 = dark selected, 1 = light selected
    private var selectedTheme = 0

    companion object {
        private const val STATE_PRIVACY_CHECKED = "privacy_checked"
        private const val LINK_COLOR = "#8888FF"
    }

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
        // Re-verify consent - the picker callback can fire after the user has unchecked the box
        // (e.g., they backgrounded onboarding while the picker was open).
        if (!hasAcceptedPrivacy()) {
            Toast.makeText(this, "Please accept the privacy policy first", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
            val mgr = BackupManager(prefs)
            val contents = mgr.parse(json)
            mgr.applyNonPrivate(contents)
            // Onboarding deliberately skips the private bundle (hidden apps + PIN + biometric).
            // The full PIN-verify dialog flow lives in Settings → Backup → Import; surfacing it
            // mid-onboarding would gate the welcome flow behind a PIN the user may not remember.
            // The user can re-import the same backup from Settings later to restore the
            // private bundle through the standard PIN-verify path.
            val skippedNote =
                if (contents.privateBundle != null)
                    "Settings restored. Re-import from Settings to restore hidden apps."
                else "Settings restored"
            Toast.makeText(this, skippedNote, Toast.LENGTH_LONG).show()
            finishOnboarding()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasAcceptedPrivacy(): Boolean =
        ::checkPrivacy.isInitialized && checkPrivacy.isChecked

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

        btnSetDefault = findViewById(R.id.btnSetDefault)
        btnSkip = findViewById(R.id.btnSkip)
        btnImport = findViewById(R.id.btnImportSettings)

        btnSetDefault.setOnClickListener {
            applySelectedTheme()
            requestDefaultLauncher()
        }

        btnSkip.setOnClickListener {
            applySelectedTheme()
            finishOnboarding()
        }

        btnImport.setOnClickListener {
            openBackupLauncher.launch(arrayOf("application/json", "*/*"))
        }

        setupPrivacyConsent(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::checkPrivacy.isInitialized) {
            outState.putBoolean(STATE_PRIVACY_CHECKED, checkPrivacy.isChecked)
        }
    }

    private fun setupPrivacyConsent(savedInstanceState: Bundle?) {
        checkPrivacy = findViewById(R.id.checkPrivacy)
        val label = findViewById<TextView>(R.id.labelPrivacyAcceptance)

        val text = "I've read the Privacy Policy"
        val linkStart = text.indexOf("Privacy Policy")
        val span = SpannableString(text)
        span.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                PrivacyPolicyDialog.show(this@OnboardingActivity)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = Color.parseColor(LINK_COLOR)
                ds.isUnderlineText = true
            }
        }, linkStart, linkStart + "Privacy Policy".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.text = span
        label.movementMethod = LinkMovementMethod.getInstance()

        val initialChecked = savedInstanceState?.getBoolean(STATE_PRIVACY_CHECKED, false) ?: false
        checkPrivacy.isChecked = initialChecked
        updateActionsEnabled(initialChecked)

        checkPrivacy.setOnCheckedChangeListener { _, isChecked ->
            updateActionsEnabled(isChecked)
        }
    }

    private fun updateActionsEnabled(enabled: Boolean) {
        val targets = listOf(btnSetDefault, btnSkip, btnImport)
        targets.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    override fun onResume() {
        super.onResume()
        // Gate auto-completion on the consent checkbox so users who set Slate as default outside
        // our flow (Android Settings) - or who back-out of the role picker after unchecking the
        // box - still have to accept the policy before onboarding completes.
        if (isDefaultLauncher() && hasAcceptedPrivacy()) {
            finishOnboarding()
        }
    }

    override fun onDestroy() {
        // Prevent android.view.WindowLeaked if the privacy dialog is open during rotation /
        // configuration change.
        PrivacyPolicyDialog.dismissActive()
        super.onDestroy()
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
