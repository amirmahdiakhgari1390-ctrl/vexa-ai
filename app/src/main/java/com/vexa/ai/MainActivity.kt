package com.vexa.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var accounts: AccountManager
    private lateinit var subs: SubscriptionManager
    private lateinit var memoryStore: MemoryStore
    private lateinit var historyStore: ChatHistoryStore
    private lateinit var currentSession: ChatHistoryStore.ChatSession
    private lateinit var vexaCore: VexaCoreClient
    private lateinit var processor: CommandProcessor
    private lateinit var vexaVoice: VexaVoiceEngine

    private var recognizer: SpeechRecognizer? = null

    private var listening = false
    private var speaking = false
    private var voiceReplyEnabled = true
    private var uiLangCode = "fa-IR"
    private var currentTheme = AppStringsAndThemes.AppTheme.OBSIDIAN_CYAN
    private var activeUntil = 0L
    private val activeWindowMs = 25_000L
    private val handler = Handler(Looper.getMainLooper())

    private var pendingImage: Bitmap? = null

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerSessionsContainer: LinearLayout
    private lateinit var statusBadge: TextView
    private lateinit var personaChip: TextView
    private lateinit var langToggleBtn: TextView
    private lateinit var voiceToggleBtn: TextView
    private lateinit var liveTranscriptView: TextView
    private lateinit var voiceOrb: VoiceOrbView
    private lateinit var micIconView: ImageView
    private lateinit var micHintView: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var commandInput: EditText
    private lateinit var wakeSwitch: Switch
    private lateinit var imagePreviewBar: LinearLayout
    private lateinit var imagePreviewThumb: ImageView

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val bmp = result.data?.extras?.get("data") as? Bitmap
            if (bmp != null) attachImage(bmp)
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) attachImage(bmp)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vexa_prefs", Context.MODE_PRIVATE)
        voiceReplyEnabled = prefs.getBoolean("voice_reply", true)
        uiLangCode = prefs.getString("ui_lang_code", "fa-IR") ?: "fa-IR"
        val savedThemeId = prefs.getString("app_theme_id", AppStringsAndThemes.AppTheme.OBSIDIAN_CYAN.id)
        currentTheme = AppStringsAndThemes.AppTheme.values().firstOrNull { it.id == savedThemeId }
            ?: AppStringsAndThemes.AppTheme.OBSIDIAN_CYAN

        accounts = AccountManager(this)
        subs = SubscriptionManager(this)
        memoryStore = MemoryStore(this)
        historyStore = ChatHistoryStore(this)
        vexaCore = VexaCoreClient(prefs, memoryStore, subs)
        processor = CommandProcessor(this, PhoneActions(this), vexaCore, memoryStore, subs, accounts)

        vexaVoice = VexaVoiceEngine(
            ctx = this,
            onStateChange = { isSpeaking ->
                speaking = isSpeaking
                if (isSpeaking) {
                    setStatus("SPEAKING", VoiceOrbView.Mode.SPEAKING)
                } else {
                    if (listening) resumeListening() else setStatus("ONLINE", VoiceOrbView.Mode.IDLE)
                }
            }
        )

        if (!accounts.isLoggedIn()) {
            showAuthScreen()
        } else {
            launchMainAssistant()
        }
    }

    private fun t(key: String): String = AppStringsAndThemes.tr(uiLangCode, key)

    private fun currentLangObj(): AppStringsAndThemes.WorldLang {
        return AppStringsAndThemes.SUPPORTED_UI_LANGUAGES.firstOrNull { it.code == uiLangCode }
            ?: AppStringsAndThemes.SUPPORTED_UI_LANGUAGES.first()
    }

    private fun launchMainAssistant() {
        buildGlobalUiWithDrawer()
        loadInitialSession()
        if (!granted(Manifest.permission.RECORD_AUDIO)) askPermissions()
    }

    override fun onStop() {
        super.onStop()
        if (listening) stopListening("PAUSED")
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        vexaVoice.shutdown()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CAMERA
            ),
            1
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(
        fillHex: String,
        radiusDp: Int,
        strokeHex: String? = null,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
            }
        }
    }

    /**
     * صفحه ثبت‌نام و ورود با تایید پیامکی کد ۵ رقمی با نام VEXA AI
     */
    private fun showAuthScreen() {
        if (!granted(Manifest.permission.SEND_SMS)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 10)
        }

        var isRegisterMode = true
        var awaitingOtpStep = false

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(34), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                bottomMargin = dp(10)
            }
        }

        val title = TextView(this).apply {
            text = "VEXA AI"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
        }

        val subtitle = TextView(this).apply {
            text = "دستیار هوش مصنوعی جهانی — پشتیبانی از تمام زبان‌های دنیا"
            textSize = 13f
            setTextColor(Color.parseColor(currentTheme.accent))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(20))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(currentTheme.bgHeader, 18, currentTheme.border, 1)
            setPadding(dp(18), dp(20), dp(18), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val modeTitle = TextView(this).apply {
            text = "ثبت‌نام و تایید شماره تلفن"
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            setPadding(0, 0, 0, dp(14))
        }

        val nameLabel = TextView(this).apply {
            text = "نام و نام خانوادگی:"
            textSize = 12f
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            setPadding(0, 0, 0, dp(4))
        }

        val nameInput = EditText(this).apply {
            hint = "نام خود را وارد کنید"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val phoneLabel = TextView(this).apply {
            text = "شماره تلفن همراه:"
            textSize = 12f
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            setPadding(0, 0, 0, dp(4))
        }

        val phoneInput = EditText(this).apply {
            hint = "09123456789"
            inputType = InputType.TYPE_CLASS_PHONE
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val passLabel = TextView(this).apply {
            text = "رمز عبور:"
            textSize = 12f
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            setPadding(0, 0, 0, dp(4))
        }

        val passRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val passInput = EditText(this).apply {
            hint = "حداقل ۶ کاراکتر"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val autoPassBtn = Button(this).apply {
            text = "🔐 ساخت رمز"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#050811"))
            background = roundedBg(currentTheme.accent, 12)
            setPadding(dp(10), dp(11), dp(10), dp(11))
            setOnClickListener {
                val generated = accounts.generateStrongPassword()
                passInput.setText(generated)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Vexa Password", generated))
                Toast.makeText(
                    this@MainActivity,
                    "رمز عبور امن ساخته و کپی شد: $generated",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        passRow.addView(passInput)
        passRow.addView(autoPassBtn)

        val otpBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBg(currentTheme.bgCard, 12, currentTheme.accent, 1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val otpNoticeText = TextView(this).apply {
            text = "کد تایید ۵ رقمی از طرف VEXA AI ارسال شد:"
            textSize = 12f
            setTextColor(Color.parseColor(currentTheme.accent))
            setPadding(0, 0, 0, dp(8))
        }

        val otpInput = EditText(this).apply {
            hint = "کد ۵ رقمی"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#34D399"))
            background = roundedBg(currentTheme.bgInput, 10, "#059669", 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        otpBox.addView(otpNoticeText)
        otpBox.addView(otpInput)

        val submitBtn = Button(this).apply {
            text = "📩 ارسال کد تایید ۵ رقمی"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#050811"))
            background = roundedBg(currentTheme.accent, 14)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val switchModeBtn = TextView(this).apply {
            text = "قبلاً ثبت‌نام کرده‌اید؟ ورود به حساب"
            textSize = 12.5f
            setTextColor(Color.parseColor(currentTheme.accent))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }

        switchModeBtn.setOnClickListener {
            isRegisterMode = !isRegisterMode
            awaitingOtpStep = false
            otpBox.visibility = View.GONE
            if (isRegisterMode) {
                modeTitle.text = "ثبت‌نام و تایید شماره تلفن"
                nameLabel.visibility = View.VISIBLE
                nameInput.visibility = View.VISIBLE
                autoPassBtn.visibility = View.VISIBLE
                submitBtn.text = "📩 ارسال کد تایید ۵ رقمی"
                switchModeBtn.text = "قبلاً ثبت‌نام کرده‌اید؟ ورود به حساب"
            } else {
                modeTitle.text = "ورود به حساب کاربری"
                nameLabel.visibility = View.GONE
                nameInput.visibility = View.GONE
                autoPassBtn.visibility = View.GONE
                submitBtn.text = "🔓 ورود به Vexa AI"
                switchModeBtn.text = "حساب ندارید؟ ثبت‌نام با شماره تلفن"
            }
        }

        submitBtn.setOnClickListener {
            if (!isRegisterMode) {
                val res = accounts.login(phoneInput.text.toString(), passInput.text.toString())
                Toast.makeText(this, res.second, Toast.LENGTH_LONG).show()
                if (res.first) launchMainAssistant()
                return@setOnClickListener
            }

            if (!awaitingOtpStep) {
                val (ok, msg, _) = accounts.sendVerificationOtp(
                    nameInput.text.toString(),
                    phoneInput.text.toString(),
                    passInput.text.toString()
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) {
                    awaitingOtpStep = true
                    otpNoticeText.text = msg
                    otpBox.visibility = View.VISIBLE
                    submitBtn.text = "✅ تایید کد ۵ رقمی و تکمیل ثبت‌نام"
                }
            } else {
                val (verified, msg) = accounts.verifyOtpAndCompleteRegistration(otpInput.text.toString())
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (verified) {
                    accounts.getCurrentUser()?.let { u ->
                        memoryStore.setUserNickname(u.fullName)
                    }
                    launchMainAssistant()
                }
            }
        }

        card.addView(modeTitle)
        card.addView(nameLabel)
        card.addView(nameInput)
        card.addView(phoneLabel)
        card.addView(phoneInput)
        card.addView(passLabel)
        card.addView(passRow)
        card.addView(otpBox)
        card.addView(submitBtn)
        card.addView(switchModeBtn)

        root.addView(logo)
        root.addView(title)
        root.addView(subtitle)
        root.addView(card)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun buildGlobalUiWithDrawer() {
        val langObj = currentLangObj()
        val dir = if (langObj.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        drawerLayout = DrawerLayout(this).apply {
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            layoutDirection = dir
        }

        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            layoutDirection = dir
            layoutParams = DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ==================== 1. Top Header ====================
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(10))
            setBackgroundColor(Color.parseColor(currentTheme.bgHeader))
        }

        val menuBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu)
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Menu"
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                refreshDrawerHistoryList()
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                marginEnd = dp(7)
            }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "VEXA AI"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
        }

        statusBadge = TextView(this).apply {
            text = "ONLINE"
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#34D399"))
            background = roundedBg("#064E3B", 20, "#059669", 1)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(7)
            }
        }

        titleRow.addView(titleText)
        titleRow.addView(statusBadge)

        personaChip = TextView(this).apply {
            text = "${vexaCore.getPersona().titleEn} ▾"
            textSize = 10f
            setTextColor(Color.parseColor(currentTheme.accent))
            setPadding(0, dp(2), 0, 0)
            setOnClickListener { showPersonaDialog() }
        }

        titleCol.addView(titleRow)
        titleCol.addView(personaChip)

        // Global Language Selector Pill (All World Languages)
        langToggleBtn = TextView(this).apply {
            text = "🌐 ${langObj.shortLabel}"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { showWorldLanguagePickerDialog() }
        }

        voiceToggleBtn = TextView(this).apply {
            text = if (voiceReplyEnabled) "🔊" else "🔇"
            textSize = 13f
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                marginEnd = dp(4)
            }
            setOnClickListener {
                voiceReplyEnabled = !voiceReplyEnabled
                prefs.edit().putBoolean("voice_reply", voiceReplyEnabled).apply()
                text = if (voiceReplyEnabled) "🔊" else "🔇"
                if (!voiceReplyEnabled) vexaVoice.stop()
            }
        }

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = roundedBg(currentTheme.bgInput, 12, currentTheme.border, 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Advanced Settings"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { showComprehensiveSettingsDialog(0) }
        }

        topBar.addView(menuBtn)
        topBar.addView(logoIcon)
        topBar.addView(titleCol)
        topBar.addView(langToggleBtn)
        topBar.addView(voiceToggleBtn)
        topBar.addView(settingsBtn)

        val topDivider = View(this).apply {
            setBackgroundColor(Color.parseColor(currentTheme.border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        // ==================== 2. Voice Orb Stage ====================
        val orbStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(6))
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
        }

        val orbContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
            setOnClickListener { toggleListening() }
        }

        voiceOrb = VoiceOrbView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        micIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        }

        orbContainer.addView(voiceOrb)
        orbContainer.addView(micIconView)

        micHintView = TextView(this).apply {
            text = t("tap_to_speak")
            textSize = 11.5f
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(2))
        }

        liveTranscriptView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor(currentTheme.accent))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            visibility = View.GONE
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }

        orbStage.addView(orbContainer)
        orbStage.addView(micHintView)
        orbStage.addView(liveTranscriptView)

        // ==================== 3. Chat Stream ====================
        chatAdapter = ChatAdapter(mutableListOf())
        chatRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            setPadding(dp(14), dp(8), dp(14), dp(8))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        // ==================== 4. Localized Quick Action Chips ====================
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val quickCommands = listOf(
            t("chip_video") to t("chip_video_cmd"),
            t("chip_photo") to t("chip_photo_cmd"),
            t("chip_train") to "__TRAIN__",
            t("chip_theme") to "__THEME__",
            t("chip_btc") to t("chip_btc_cmd"),
            t("chip_pdf") to t("chip_pdf_cmd"),
            t("chip_chart") to "__GALLERY__"
        )

        for ((label, cmd) in quickCommands) {
            val chip = TextView(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(Color.parseColor(currentTheme.textPrimary))
                background = roundedBg(currentTheme.bgCard, 20, currentTheme.border, 1)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    when (cmd) {
                        "__TRAIN__" -> showComprehensiveSettingsDialog(0)
                        "__THEME__" -> showComprehensiveSettingsDialog(1)
                        "__GALLERY__" -> openGallery()
                        else -> submitUserCommand(cmd, speakOut = voiceReplyEnabled)
                    }
                }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)

        // ==================== 5. Bottom Input & Wake Switch ====================
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgHeader))
            setPadding(dp(10), dp(7), dp(10), dp(10))
        }

        imagePreviewBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(currentTheme.bgCard, 12, currentTheme.accent, 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }

        imagePreviewThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(10)
            }
        }

        val imagePreviewLabel = TextView(this).apply {
            text = "Image attached"
            textSize = 12f
            setTextColor(Color.parseColor(currentTheme.accent))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val removeImgBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F87171"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { clearAttachedImage() }
        }

        imagePreviewBar.addView(imagePreviewThumb)
        imagePreviewBar.addView(imagePreviewLabel)
        imagePreviewBar.addView(removeImgBtn)

        val bottomDivider = View(this).apply {
            setBackgroundColor(Color.parseColor(currentTheme.border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(currentTheme.bgCard, 24, currentTheme.border, 1)
            setPadding(dp(8), dp(4), dp(6), dp(4))
        }

        val cameraBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_camera)
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = "Camera"
            setOnClickListener { openCamera() }
        }

        val galleryBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_gallery)
            background = null
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = "Gallery"
            setOnClickListener { openGallery() }
        }

        commandInput = EditText(this).apply {
            hint = t("input_hint")
            setHintTextColor(Color.parseColor(currentTheme.textSecondary))
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            textSize = 14f
            background = null
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTypedText()
                    true
                } else false
            }
        }

        val sendBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            background = roundedBg(currentTheme.accent, 20)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = "Send"
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { sendTypedText() }
        }

        inputRow.addView(cameraBtn)
        inputRow.addView(galleryBtn)
        inputRow.addView(commandInput)
        inputRow.addView(sendBtn)

        val wakeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), 0)
        }

        val wakeLabel = TextView(this).apply {
            text = t("wake_label")
            textSize = 11f
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        wakeSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("need_wake", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("need_wake", checked).apply()
            }
        }

        wakeRow.addView(wakeLabel)
        wakeRow.addView(wakeSwitch)

        bottomPanel.addView(imagePreviewBar)
        bottomPanel.addView(inputRow)
        bottomPanel.addView(wakeRow)

        mainContent.addView(topBar)
        mainContent.addView(topDivider)
        mainContent.addView(orbStage)
        mainContent.addView(chatRecycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mainContent.addView(chipScroll)
        mainContent.addView(bottomDivider)
        mainContent.addView(bottomPanel)

        // ==================== 6. Professional Navigation Drawer ====================
        val drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgHeader))
            layoutDirection = dir
            layoutParams = DrawerLayout.LayoutParams(
                dp(305),
                ViewGroup.LayoutParams.MATCH_PARENT,
                GravityCompat.START
            )
        }

        val drawerTopBrand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }

        val drawerLogo = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(10)
            }
        }

        val drawerBrandTitle = TextView(this).apply {
            text = "VEXA AI"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val newChatPill = TextView(this).apply {
            text = t("new_chat")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#050811"))
            background = roundedBg(currentTheme.accent, 18)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                startNewConversation()
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }

        drawerTopBrand.addView(drawerLogo)
        drawerTopBrand.addView(drawerBrandTitle)
        drawerTopBrand.addView(newChatPill)

        val drawerDivider1 = View(this).apply {
            setBackgroundColor(Color.parseColor(currentTheme.border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        val menuSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }

        fun buildMenuRow(iconEmoji: String, labelText: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(currentTheme.bgCard, 10, currentTheme.border, 1)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
                val ico = TextView(this@MainActivity).apply {
                    text = iconEmoji
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val lbl = TextView(this@MainActivity).apply {
                    text = labelText
                    textSize = 12.5f
                    setTextColor(Color.parseColor(currentTheme.textPrimary))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val arrow = TextView(this@MainActivity).apply {
                    text = "›"
                    textSize = 16f
                    setTextColor(Color.parseColor(currentTheme.textSecondary))
                }
                addView(ico)
                addView(lbl)
                addView(arrow)
                setOnClickListener {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    onClick()
                }
            }
        }

        menuSection.addView(buildMenuRow("🧠", t("menu_custom_train")) { showComprehensiveSettingsDialog(0) })
        menuSection.addView(buildMenuRow("🎨", t("menu_theme_lang")) { showComprehensiveSettingsDialog(1) })
        menuSection.addView(buildMenuRow("🌐", t("menu_server")) { showComprehensiveSettingsDialog(2) })

        val historyHeaderLabel = TextView(this).apply {
            text = t("history_title")
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textSecondary))
            setPadding(dp(16), dp(8), dp(16), dp(6))
        }

        val drawerScroll = ScrollView(this).apply {
            setPadding(dp(12), 0, dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        drawerSessionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        drawerScroll.addView(drawerSessionsContainer)

        val drawerDivider2 = View(this).apply {
            setBackgroundColor(Color.parseColor(currentTheme.border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        val u = accounts.getCurrentUser()
        val userNameStr = u?.fullName ?: "Vexa User"
        val userPhoneStr = u?.phone ?: ""
        val initialChar = userNameStr.firstOrNull()?.toString() ?: "V"

        val userFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(currentTheme.bgRoot))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val avatarCircle = TextView(this).apply {
            text = initialChar
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#050811"))
            background = roundedBg(currentTheme.accent, 20)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(10)
            }
        }

        val userMetaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val footerNameTv = TextView(this).apply {
            text = userNameStr
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.textPrimary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val footerPhoneTv = TextView(this).apply {
            text = "$userPhoneStr  •  ${t("verified_user")}"
            textSize = 10.5f
            setTextColor(Color.parseColor("#34D399"))
        }

        userMetaCol.addView(footerNameTv)
        userMetaCol.addView(footerPhoneTv)

        val logoutIconBtn = TextView(this).apply {
            text = t("logout")
            textSize = 11.5f
            setTextColor(Color.parseColor("#F87171"))
            background = roundedBg("#2B141A", 8, "#7F1D1D", 1)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            setOnClickListener {
                accounts.logout()
                drawerLayout.closeDrawer(GravityCompat.START)
                showAuthScreen()
            }
        }

        userFooter.addView(avatarCircle)
        userFooter.addView(userMetaCol)
        userFooter.addView(logoutIconBtn)

        drawerPanel.addView(drawerTopBrand)
        drawerPanel.addView(drawerDivider1)
        drawerPanel.addView(menuSection)
        drawerPanel.addView(historyHeaderLabel)
        drawerPanel.addView(drawerScroll)
        drawerPanel.addView(drawerDivider2)
        drawerPanel.addView(userFooter)

        drawerLayout.addView(mainContent)
        drawerLayout.addView(drawerPanel)

        setContentView(drawerLayout)
    }

    /**
     * انتخاب زبان جهانی (تغییر کامل زبان رابط کاربری + تشخیص گفتار + صدای پاسخ در تمام زبان‌های دنیا)
     */
    private fun showWorldLanguagePickerDialog() {
        val langs = AppStringsAndThemes.SUPPORTED_UI_LANGUAGES
        val names = langs.map { it.displayName }.toTypedArray()
        val currentIdx = langs.indexOfFirst { it.code == uiLangCode }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("🌍 Select Language / انتخاب زبان")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                val chosen = langs[which]
                uiLangCode = chosen.code
                prefs.edit().putString("ui_lang_code", uiLangCode).apply()
                dialog.dismiss()
                if (listening) {
                    recognizer?.cancel()
                    startRecognizer()
                }
                // Rebuild entire UI immediately so EVERY single text changes to the chosen language!
                val savedMsgs = if (::currentSession.isInitialized) currentSession.messages.toList() else emptyList()
                buildGlobalUiWithDrawer()
                if (savedMsgs.isEmpty()) {
                    addWelcomeMessage()
                } else {
                    for (m in savedMsgs) {
                        val s = try { ChatMessage.Sender.valueOf(m.senderName) } catch (_: Exception) { ChatMessage.Sender.VEXA }
                        chatAdapter.addMessage(ChatMessage(m.text, s, m.time))
                    }
                    chatRecycler.post {
                        if (chatAdapter.itemCount > 0) chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }
            .setNegativeButton(t("close"), null)
            .show()
    }

    /**
     * پنجره تنظیمات فوق‌پیشرفته Vexa AI:
     * - بخش ۱: شخصی‌سازی و آموزش نامحدود (پروفایل کاربر، لحن پاسخ‌گویی، قوانین سفارشی نامحدود و مدیریت تک‌تک حافظه‌ها)
     * - بخش ۲: انتخاب تم ظاهری (۵ تم حرفه‌ای تاریک و روشن) و زبان جهانی
     * - بخش ۳: تنظیمات اتصال سرور ابری و صدای هوشمند
     */
    private fun showComprehensiveSettingsDialog(@Suppress("UNUSED_PARAMETER") initialTab: Int) {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            layoutDirection = if (currentLangObj().isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }

        // --- Section 1: Theme & Appearance ---
        val themeHeader = TextView(this).apply {
            text = "🎨 تم و ظاهر برنامه (App Theme)"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.accent))
            setPadding(0, 0, 0, dp(6))
        }

        val themes = AppStringsAndThemes.AppTheme.values()
        val themeSpinner = Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                themes.map { "${it.titleFa} — ${it.titleEn}" }
            )
            this.adapter = adapter
            setSelection(themes.indexOf(currentTheme).coerceAtLeast(0))
        }

        val langBtnInSettings = Button(this).apply {
            text = "🌍 تغییر زبان کامل برنامه (${currentLangObj().displayName})"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg("#1E293B", 10, currentTheme.accent, 1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(14)
            }
            setOnClickListener {
                showWorldLanguagePickerDialog()
            }
        }

        // --- Section 2: Unlimited Personalization & Training ---
        val trainHeader = TextView(this).apply {
            text = "🧠 شخصی‌سازی و آموزش نامحدود وِکسا"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(currentTheme.accent))
            setPadding(0, dp(4), 0, dp(6))
        }

        val nickInput = EditText(this).apply {
            hint = "نام مستعار شما (وِکسا شما را چه صدا بزند؟)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = roundedBg("#1E293B", 10, "#334155", 1)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setText(memoryStore.getUserNickname())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val profInput = EditText(this).apply {
            hint = "شغل، تخصص و علایق شما (مثلاً: تریدر ارز دیجیتال و توسعه‌دهنده)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = roundedBg("#1E293B", 10, "#334155", 1)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setText(memoryStore.getUserProfession())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val styleCodes = listOf("balanced", "friendly", "formal", "concise", "detailed")
        val styleLabels = listOf(
            "لحن متعادل و هوشمند (Balanced)",
            "صمیمی و دوستانه (Warm & Friendly)",
            "رسمی و مدیریتی (Executive Formal)",
            "کوتاه، سریع و مستقیم (Concise)",
            "عمیق، جامع و آموزشی (Deep & Detailed)"
        )
        val styleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, styleLabels)
            val idx = styleCodes.indexOf(memoryStore.getResponseStyle()).coerceAtLeast(0)
            setSelection(idx)
        }

        val customInstrInput = EditText(this).apply {
            hint = "دستورالعمل‌های نامحدود شما (هر قانونی بنویسید وِکسا همیشه اجرا می‌کند)..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            minLines = 3
            maxLines = 6
            background = roundedBg("#1E293B", 10, "#38BDF8", 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(memoryStore.getCustomInstructions())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }

        // Add new training fact directly
        val addFactRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val newFactInput = EditText(this).apply {
            hint = "افزودن نکته آموزشی جدید (نامحدود)..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            background = roundedBg("#1E293B", 10, "#334155", 1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        val learnedListView = TextView(this).apply {
            val facts = memoryStore.getAll()
            text = if (facts.isEmpty()) "لیست حافظه خالی است." else facts.mapIndexed { idx: Int, s: String -> "${idx + 1}. $s" }.joinToString(separator = "\n")
            textSize = 11.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(4), 0, dp(10))
        }

        val addFactBtn = Button(this).apply {
            text = "+ افزودن"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#050811"))
            background = roundedBg(currentTheme.accent, 10)
            setOnClickListener {
                val txt = newFactInput.text.toString().trim()
                if (txt.isNotEmpty()) {
                    memoryStore.remember(txt, isPersian = true)
                    newFactInput.setText("")
                    val updated = memoryStore.getAll()
                    learnedListView.text = updated.mapIndexed { idx: Int, s: String -> "${idx + 1}. $s" }.joinToString(separator = "\n")
                }
            }
        }

        addFactRow.addView(newFactInput)
        addFactRow.addView(addFactBtn)

        // --- Section 3: Server Gateway ---
        val gatewayLabel = TextView(this).apply {
            text = "🌐 آدرس سرور ابری اختصاصی:"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(6), 0, dp(4))
        }

        val gatewayInput = EditText(this).apply {
            hint = VexaCoreClient.DEFAULT_GATEWAY_URL
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            background = roundedBg("#1E293B", 10, "#334155", 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setText(prefs.getString("gateway_url", ""))
        }

        val clearBtn = Button(this).apply {
            text = "پاک کردن کل حافظه و آموزش‌ها"
            textSize = 12f
            setTextColor(Color.parseColor("#FCA5A5"))
            background = roundedBg("#3F1D2E", 10, "#991B1B", 1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            setOnClickListener {
                memoryStore.clearAll(true)
                customInstrInput.setText("")
                nickInput.setText("")
                profInput.setText("")
                learnedListView.text = "لیست حافظه خالی است."
            }
        }

        container.addView(themeHeader)
        container.addView(themeSpinner)
        container.addView(langBtnInSettings)
        container.addView(trainHeader)
        container.addView(nickInput)
        container.addView(profInput)
        container.addView(styleSpinner)
        container.addView(customInstrInput)
        container.addView(addFactRow)
        container.addView(learnedListView)
        container.addView(gatewayLabel)
        container.addView(gatewayInput)
        container.addView(clearBtn)
        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle(t("settings_title"))
            .setView(scroll)
            .setPositiveButton(t("save")) { _, _ ->
                memoryStore.setUserNickname(nickInput.text.toString())
                memoryStore.setUserProfession(profInput.text.toString())
                memoryStore.setResponseStyle(styleCodes[styleSpinner.selectedItemPosition])
                memoryStore.setCustomInstructions(customInstrInput.text.toString())
                prefs.edit().putString("gateway_url", gatewayInput.text.toString().trim()).apply()

                val chosenTheme = themes[themeSpinner.selectedItemPosition]
                if (chosenTheme != currentTheme) {
                    currentTheme = chosenTheme
                    prefs.edit().putString("app_theme_id", currentTheme.id).apply()
                    val savedMsgs = if (::currentSession.isInitialized) currentSession.messages.toList() else emptyList()
                    buildGlobalUiWithDrawer()
                    for (m in savedMsgs) {
                        val s = try { ChatMessage.Sender.valueOf(m.senderName) } catch (_: Exception) { ChatMessage.Sender.VEXA }
                        chatAdapter.addMessage(ChatMessage(m.text, s, m.time))
                    }
                }
                Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(t("close"), null)
            .show()
    }

    private fun loadInitialSession() {
        val sessions = historyStore.getAllSessions()
        if (sessions.isNotEmpty()) {
            openSession(sessions.first())
        } else {
            startNewConversation()
        }
    }

    private fun startNewConversation() {
        vexaVoice.stop()
        vexaCore.clear()
        currentSession = historyStore.createNewSession(t("new_chat").removePrefix("+ "))
        chatAdapter.clearAll()
        addWelcomeMessage()
        refreshDrawerHistoryList()
    }

    private fun openSession(session: ChatHistoryStore.ChatSession) {
        vexaVoice.stop()
        currentSession = session
        chatAdapter.clearAll()
        vexaCore.restoreHistory(session.messages)
        if (session.messages.isEmpty()) {
            addWelcomeMessage()
        } else {
            for (m in session.messages) {
                val sender = try {
                    ChatMessage.Sender.valueOf(m.senderName)
                } catch (_: Exception) {
                    ChatMessage.Sender.VEXA
                }
                chatAdapter.addMessage(ChatMessage(m.text, sender, m.time))
            }
            chatRecycler.post {
                if (chatAdapter.itemCount > 0) {
                    chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
                }
            }
        }
        refreshDrawerHistoryList()
    }

    private fun refreshDrawerHistoryList() {
        drawerSessionsContainer.removeAllViews()
        val sessions = historyStore.getAllSessions()
        for (s in sessions) {
            val isSelected = ::currentSession.isInitialized && s.id == currentSession.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isSelected) {
                    roundedBg(currentTheme.bgCard, 10, currentTheme.accent, 1)
                } else {
                    roundedBg(currentTheme.bgRoot, 10)
                }
                setPadding(dp(11), dp(9), dp(8), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    openSession(s)
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }

            val titleTv = TextView(this).apply {
                text = s.title
                textSize = 12.5f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(Color.parseColor(if (isSelected) currentTheme.accent else currentTheme.textPrimary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val dateTv = TextView(this).apply {
                text = s.updatedAt
                textSize = 10f
                setTextColor(Color.parseColor(currentTheme.textSecondary))
                setPadding(0, dp(2), 0, 0)
            }

            textCol.addView(titleTv)
            textCol.addView(dateTv)

            val delBtn = TextView(this).apply {
                text = "✕"
                textSize = 13f
                setTextColor(Color.parseColor(currentTheme.textSecondary))
                setPadding(dp(8), dp(4), dp(6), dp(4))
                setOnClickListener {
                    historyStore.deleteSession(s.id)
                    if (::currentSession.isInitialized && s.id == currentSession.id) {
                        loadInitialSession()
                    } else {
                        refreshDrawerHistoryList()
                    }
                }
            }

            row.addView(textCol)
            row.addView(delBtn)
            drawerSessionsContainer.addView(row)
        }
    }

    private fun openCamera() {
        if (!granted(Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            cameraLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        try {
            galleryLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Gallery unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun attachImage(bmp: Bitmap) {
        pendingImage = bmp
        imagePreviewThumb.setImageBitmap(bmp)
        imagePreviewBar.visibility = View.VISIBLE
    }

    private fun clearAttachedImage() {
        pendingImage = null
        imagePreviewThumb.setImageDrawable(null)
        imagePreviewBar.visibility = View.GONE
    }

    private fun addWelcomeMessage() {
        val userName = accounts.getCurrentUser()?.fullName ?: "Friend"
        val msg = String.format(t("welcome_body"), userName)
        chatAdapter.addMessage(ChatMessage(msg, ChatMessage.Sender.VEXA, timeFmt.format(Date())))
    }

    private fun appendMessage(
        text: String,
        sender: ChatMessage.Sender,
        image: Bitmap? = null,
        generatedFile: FileGenerator.GeneratedFile? = null
    ) {
        val timeStr = timeFmt.format(Date())
        chatAdapter.addMessage(ChatMessage(text, sender, timeStr, image, generatedFile))
        chatRecycler.post {
            chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
        }

        if (::currentSession.isInitialized && sender != ChatMessage.Sender.SYSTEM && text.isNotBlank()) {
            if (sender == ChatMessage.Sender.USER && currentSession.messages.none { it.senderName == "USER" }) {
                currentSession.title = text.replace("\n", " ").take(34)
            }
            currentSession.messages.add(ChatHistoryStore.StoredMessage(text, sender.name, timeStr))
            historyStore.saveSession(currentSession)
        }
    }

    private fun sendTypedText() {
        val txt = commandInput.text.toString().trim()
        val img = pendingImage
        if (txt.isEmpty() && img == null) return
        commandInput.setText("")
        clearAttachedImage()
        val prompt = txt.ifEmpty { "Analyze this image in detail." }
        submitUserCommand(prompt, speakOut = voiceReplyEnabled, image = img)
    }

    private fun setStatus(status: String, orbMode: VoiceOrbView.Mode) {
        statusBadge.text = status
        voiceOrb.mode = orbMode
        when (orbMode) {
            VoiceOrbView.Mode.IDLE -> {
                statusBadge.setTextColor(Color.parseColor("#34D399"))
                statusBadge.background = roundedBg("#064E3B", 20, "#059669", 1)
                micIconView.setImageResource(R.drawable.ic_mic)
                micHintView.text = t("tap_to_speak")
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.LISTENING -> {
                statusBadge.setTextColor(Color.parseColor("#22D3EE"))
                statusBadge.background = roundedBg("#083344", 20, "#0891B2", 1)
                micIconView.setImageResource(R.drawable.ic_stop)
                micHintView.text = t("listening")
            }
            VoiceOrbView.Mode.THINKING -> {
                statusBadge.setTextColor(Color.parseColor("#C084FC"))
                statusBadge.background = roundedBg("#2E1065", 20, "#7E22CE", 1)
                micHintView.text = t("thinking")
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.SPEAKING -> {
                statusBadge.setTextColor(Color.parseColor("#22D3EE"))
                statusBadge.background = roundedBg("#0C2D48", 20, "#1E40AF", 1)
                micHintView.text = t("speaking")
                liveTranscriptView.visibility = View.GONE
            }
        }
    }

    private fun showPersonaDialog() {
        val personas = VexaCoreClient.Persona.values()
        val titles = personas.map { "${it.titleEn} | ${it.titleFa}" }.toTypedArray()
        val currentIdx = personas.indexOf(vexaCore.getPersona()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Vexa AI Mode")
            .setSingleChoiceItems(titles, currentIdx) { dialog, which ->
                val selected = personas[which]
                vexaCore.setPersona(selected)
                personaChip.text = "${selected.titleEn} ▾"
                dialog.dismiss()
            }
            .setNegativeButton(t("close"), null)
            .show()
    }

    private fun speak(text: String) {
        if (!voiceReplyEnabled || text.isBlank()) {
            speaking = false
            if (listening) resumeListening() else setStatus("ONLINE", VoiceOrbView.Mode.IDLE)
            return
        }
        vexaVoice.speak(text, preferredLangTag = uiLangCode)
    }

    private fun toggleListening() {
        if (listening) {
            stopListening("ONLINE")
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            askPermissions()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition unavailable", Toast.LENGTH_LONG).show()
            return
        }
        listening = true
        setStatus("LISTENING", VoiceOrbView.Mode.LISTENING)
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        }
        startRecognizer()
    }

    private fun stopListening(msg: String) {
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        vexaVoice.stop()
        speaking = false
        voiceOrb.rmsLevel = 0f
        setStatus(msg, VoiceOrbView.Mode.IDLE)
    }

    private fun startRecognizer() {
        if (!listening || speaking) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, uiLangCode)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, uiLangCode)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
        }
        recognizer?.startListening(i)
    }

    private fun resumeListening(delayMs: Long = 300) {
        if (!listening || speaking) return
        handler.postDelayed({ startRecognizer() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            setStatus("LISTENING", VoiceOrbView.Mode.LISTENING)
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            voiceOrb.rmsLevel = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            voiceOrb.rmsLevel = 0f
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) {
                    liveTranscriptView.visibility = View.VISIBLE
                    liveTranscriptView.text = "« $it … »"
                }
            }
        }

        override fun onResults(results: Bundle?) {
            liveTranscriptView.visibility = View.GONE
            voiceOrb.rmsLevel = 0f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                resumeListening()
                return
            }
            handleSpeech(text)
        }

        override fun onError(error: Int) {
            voiceOrb.rmsLevel = 0f
            if (!listening) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopListening("NO MIC PERMISSION")
                    askPermissions()
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> resumeListening(150)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.cancel()
                    resumeListening(800)
                }
                else -> resumeListening(1000)
            }
        }
    }

    private fun handleSpeech(text: String) {
        val (body, heard) = processor.stripWake(processor.norm(text))
        val now = SystemClock.elapsedRealtime()
        val needWake = prefs.getBoolean("need_wake", false)
        if (needWake && !heard && now > activeUntil) {
            resumeListening()
            return
        }
        if (heard) activeUntil = now + activeWindowMs

        val img = pendingImage
        if (img != null) clearAttachedImage()

        appendMessage(text, ChatMessage.Sender.USER, img)
        if (body.isBlank() && img == null) {
            val greet = if (uiLangCode.startsWith("fa")) "جانم؟ در خدمتم!" else "Yes? Vexa is listening!"
            respond(CommandProcessor.ProcessResponse(greet), speakOut = true)
            return
        }
        setStatus("THINKING", VoiceOrbView.Mode.THINKING)
        processor.process(body.ifBlank { text }, image = img) { res -> respond(res, speakOut = true) }
    }

    private fun submitUserCommand(text: String, speakOut: Boolean, image: Bitmap? = null) {
        val (body, _) = processor.stripWake(processor.norm(text))
        appendMessage(text, ChatMessage.Sender.USER, image)
        val cleanBody = if (image != null) text else body.ifBlank { text }
        setStatus("THINKING", VoiceOrbView.Mode.THINKING)
        processor.process(cleanBody, image = image) { res -> respond(res, speakOut = speakOut) }
    }

    private fun respond(res: CommandProcessor.ProcessResponse, speakOut: Boolean) {
        if (res.text.isNotBlank() || res.generatedImage != null || res.generatedFile != null) {
            appendMessage(
                text = res.text,
                sender = ChatMessage.Sender.VEXA,
                image = res.generatedImage,
                generatedFile = res.generatedFile
            )
        }
        val toSpeak = res.speakText ?: res.text
        if (speakOut && voiceReplyEnabled) {
            speak(toSpeak)
        } else {
            setStatus("ONLINE", VoiceOrbView.Mode.IDLE)
        }
    }
}
