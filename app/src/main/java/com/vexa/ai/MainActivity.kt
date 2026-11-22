package com.vexa.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private var speechLang = "fa-IR"
    private var activeUntil = 0L
    private val activeWindowMs = 25_000L
    private val handler = Handler(Looper.getMainLooper())

    private var pendingImage: Bitmap? = null

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerSessionsContainer: LinearLayout
    private lateinit var drawerPlanSummaryView: TextView
    private lateinit var statusBadge: TextView
    private lateinit var tierBadge: TextView
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
        speechLang = prefs.getString("speech_lang", "fa-IR") ?: "fa-IR"

        subs = SubscriptionManager(this)
        memoryStore = MemoryStore(this)
        historyStore = ChatHistoryStore(this)
        vexaCore = VexaCoreClient(prefs, memoryStore, subs)
        processor = CommandProcessor(this, PhoneActions(this), vexaCore, memoryStore, subs)

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

        buildGlobalUiWithDrawer()
        updateTierBadgeUi()
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

    private fun updateTierBadgeUi() {
        val tier = subs.getCurrentTier()
        when (tier) {
            SubscriptionManager.Tier.FREE -> {
                tierBadge.text = "FREE ⬆"
                tierBadge.setTextColor(Color.parseColor("#CBD5E1"))
                tierBadge.background = roundedBg("#1E293B", 16, "#475569", 1)
            }
            SubscriptionManager.Tier.PRO -> {
                tierBadge.text = "PRO ⭐"
                tierBadge.setTextColor(Color.parseColor("#38BDF8"))
                tierBadge.background = roundedBg("#0C2D48", 16, "#0284C7", 1)
            }
            SubscriptionManager.Tier.ALPHA -> {
                tierBadge.text = "ALPHA 👑"
                tierBadge.setTextColor(Color.parseColor("#FBBF24"))
                tierBadge.background = roundedBg("#422006", 16, "#D97706", 1)
            }
        }
        if (::drawerPlanSummaryView.isInitialized) {
            drawerPlanSummaryView.text = subs.getUsageSummaryFa()
        }
    }

    private fun buildGlobalUiWithDrawer() {
        drawerLayout = DrawerLayout(this).apply {
            setBackgroundColor(Color.parseColor("#050811"))
        }

        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050811"))
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
            setBackgroundColor(Color.parseColor("#0A101E"))
        }

        val menuBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu)
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Chat History Menu"
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                updateTierBadgeUi()
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
            text = "VEXA"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        tierBadge = TextView(this).apply {
            text = "FREE ⬆"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(2), dp(7), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            setOnClickListener { showSubscriptionDialog() }
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
                marginStart = dp(5)
            }
        }

        titleRow.addView(titleText)
        titleRow.addView(tierBadge)
        titleRow.addView(statusBadge)

        personaChip = TextView(this).apply {
            text = "${vexaCore.getPersona().titleEn} ▾"
            textSize = 10f
            setTextColor(Color.parseColor("#22D3EE"))
            setPadding(0, dp(2), 0, 0)
            setOnClickListener { showPersonaDialog() }
        }

        titleCol.addView(titleRow)
        titleCol.addView(personaChip)

        langToggleBtn = TextView(this).apply {
            text = if (speechLang == "fa-IR") "🌐 FA" else "🌐 EN"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(7), dp(8), dp(7), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                marginEnd = dp(4)
            }
            setOnClickListener {
                speechLang = if (speechLang == "en-US") "fa-IR" else "en-US"
                prefs.edit().putString("speech_lang", speechLang).apply()
                text = if (speechLang == "fa-IR") "🌐 FA" else "🌐 EN"
                micHintView.text = if (speechLang == "fa-IR") {
                    "برای صحبت به فارسی، گوی را لمس کنید"
                } else {
                    "Tap the orb to speak (English / Persian)"
                }
                Toast.makeText(
                    this@MainActivity,
                    if (speechLang == "fa-IR") "زبان تشخیص گفتار: فارسی" else "Voice recognition language: English",
                    Toast.LENGTH_SHORT
                ).show()
                if (listening) {
                    recognizer?.cancel()
                    startRecognizer()
                }
            }
        }

        voiceToggleBtn = TextView(this).apply {
            text = if (voiceReplyEnabled) "🔊" else "🔇"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#111827", 12, "#1E293B", 1)
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
                Toast.makeText(
                    this@MainActivity,
                    if (voiceReplyEnabled) "صدای پاسخ روشن شد" else "صدای پاسخ خاموش شد",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Settings & Training"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { showTrainVexaDialog() }
        }

        topBar.addView(menuBtn)
        topBar.addView(logoIcon)
        topBar.addView(titleCol)
        topBar.addView(langToggleBtn)
        topBar.addView(voiceToggleBtn)
        topBar.addView(settingsBtn)

        val topDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        // ==================== 2. Voice Orb Stage ====================
        val orbStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(6))
            setBackgroundColor(Color.parseColor("#070B16"))
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
            text = if (speechLang == "fa-IR") "برای صحبت به فارسی، گوی را لمس کنید" else "Tap the orb to speak (English / Persian)"
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(2))
        }

        liveTranscriptView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#22D3EE"))
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

        // ==================== 4. Quick Action Chips ====================
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val quickCommands = listOf(
            "🎬 ساخت ویدیو MP4" to "ویدیو بساز از یک فضانورد در سطح سیاره مریخ در غروب آفتاب",
            "📸 عکس واقع‌گرایانه" to "عکس واقع‌گرایانه بساز از یک خانه مدرن چوبی کنار دریاچه در مه صبحگاهی",
            "🧠 تربیت و آموزش وِکسا" to "__TRAIN__",
            "👑 ارتقا به پرو / آلفا" to "__UPGRADE__",
            "📈 تحلیل بیت‌کوین" to "تحلیل تکنیکال و روند لحظه‌ای بیت کوین را بگو",
            "📄 ساخت فایل PDF" to "یک فایل pdf بساز درباره اصول مدیریت سرمایه در ترید",
            "📊 تحلیل چارت از گالری" to "__GALLERY__"
        )

        for ((label, cmd) in quickCommands) {
            val chip = TextView(this).apply {
                text = label
                textSize = 11.5f
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedBg("#0F172A", 20, "#1E293B", 1)
                setPadding(dp(11), dp(6), dp(11), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    when (cmd) {
                        "__TRAIN__" -> showTrainVexaDialog()
                        "__UPGRADE__" -> showSubscriptionDialog()
                        "__CAMERA__" -> openCamera()
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
            setBackgroundColor(Color.parseColor("#0A101E"))
            setPadding(dp(10), dp(7), dp(10), dp(10))
        }

        imagePreviewBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#0F172A", 12, "#22D3EE", 1)
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
            text = "تصویر / چارت ضمیمه شد (بپرسید یا ارسال بزنید)"
            textSize = 12f
            setTextColor(Color.parseColor("#CFFAFE"))
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
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#0F172A", 24, "#1E293B", 1)
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
            hint = "پیام به فارسی یا انگلیسی… (ویدیو، عکس، کد، ترید)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
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
            background = roundedBg("#22D3EE", 20)
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
            text = "بیدارباش صوتی: فقط با گفتن «وِکسا» / \"Vexa\" پاسخ دهد"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
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

        // ==================== 6. Side Navigation Drawer ====================
        val drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A101E"))
            setPadding(dp(14), dp(18), dp(14), dp(14))
            layoutParams = DrawerLayout.LayoutParams(
                dp(300),
                ViewGroup.LayoutParams.MATCH_PARENT,
                GravityCompat.START
            )
        }

        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }

        val drawerLogo = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                marginEnd = dp(8)
            }
        }

        val drawerTitle = TextView(this).apply {
            text = "VEXA AI — تاریخچه و اشتراک"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        drawerHeader.addView(drawerLogo)
        drawerHeader.addView(drawerTitle)

        val newChatBtn = Button(this).apply {
            text = "➕  گفتگوی جدید / New Chat"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#050811"))
            background = roundedBg("#22D3EE", 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener {
                startNewConversation()
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }

        val trainBtn = Button(this).apply {
            text = "🧠  تربیت و آموزش شخصی وِکسا"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0F2FE"))
            background = roundedBg("#1E293B", 12, "#38BDF8", 1)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                showTrainVexaDialog()
            }
        }

        // Active Subscription Card inside Drawer
        val planCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#111827", 12, "#F59E0B", 1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        drawerPlanSummaryView = TextView(this).apply {
            text = subs.getUsageSummaryFa()
            textSize = 11f
            setTextColor(Color.parseColor("#FDE68A"))
        }

        val upgradeDrawerBtn = Button(this).apply {
            text = "👑 ارتقا به نسخه پرو (۸۰۰ هزار) یا آلفا (۱.۵ میلیون)"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#050811"))
            background = roundedBg("#FBBF24", 10)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                showSubscriptionDialog()
            }
        }

        planCard.addView(drawerPlanSummaryView)
        planCard.addView(upgradeDrawerBtn)

        val historySectionLabel = TextView(this).apply {
            text = "گفتگوهای اخیر (Recent Chats)"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(dp(4), 0, dp(4), dp(6))
        }

        val drawerScroll = ScrollView(this).apply {
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

        drawerPanel.addView(drawerHeader)
        drawerPanel.addView(newChatBtn)
        drawerPanel.addView(trainBtn)
        drawerPanel.addView(planCard)
        drawerPanel.addView(historySectionLabel)
        drawerPanel.addView(drawerScroll)

        drawerLayout.addView(mainContent)
        drawerLayout.addView(drawerPanel)

        setContentView(drawerLayout)
    }

    /**
     * پنجره خرید و مقایسه نسخه‌های رایگان، پرو (۸۰۰ هزار تومان) و آلفا (۱,۵۰۰ هزار تومان نامحدود)
     */
    private fun showSubscriptionDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val currentStatus = TextView(this).apply {
            text = subs.getUsageSummaryFa()
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#34D399"))
            background = roundedBg("#064E3B", 10, "#059669", 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        // Card 1: Free Tier
        val freeCard = TextView(this).apply {
            text = "🆓 نسخه رایگان (Free) — ۰ تومان\n" +
                "• روزانه ۱۲ پیام هوشمند (مدل استاندارد)\n" +
                "• روزانه ۲ تصویر واقع‌گرایانه\n" +
                "• روزانه ۱ فایل خروجی\n" +
                "• ساخت ویدیو MP4: غیرفعال\n" +
                "• حداکثر ۲ قانون در حافظه تربیت هوش مصنوعی"
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundedBg("#1E293B", 12, "#334155", 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        // Card 2: Pro Tier (800,000 Toman)
        val proCard = TextView(this).apply {
            text = "⭐ نسخه پرو (PRO) — ۸۰۰,۰۰۰ تومان\n" +
                "• دسترسی به مدل‌های استدلالی قوی (Qwen 235B & DeepSeek R1)\n" +
                "• روزانه ۱۲۰ پیام هوشمند\n" +
                "• روزانه ۲۵ عکس واقع‌گرایانه HD\n" +
                "• روزانه ۵ ویدیو سینمایی MP4\n" +
                "• روزانه ۲۰ فایل PDF / پایتون / اکسل\n" +
                "• تا ۱۵ قانون تربیت و یادگیری شخصی"
            textSize = 12f
            setTextColor(Color.parseColor("#E0F2FE"))
            background = roundedBg("#0C2D48", 12, "#0284C7", 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        // Card 3: Alpha Tier (1,500,000 Toman - Unlimited Everything)
        val alphaCard = TextView(this).apply {
            text = "👑 نسخه آلفا (ALPHA) — ۱,۵۰۰,۰۰۰ تومان (کاملاً نامحدود)\n" +
                "• بدون هیچ محدودیتی در تعداد پیام، عکس، ویدیو MP4 و فایل!\n" +
                "• بالاترین سطح هوش مصنوعی استدلالی و تحلیل ترید\n" +
                "• تربیت و یادگیری نامحدود وِکسا مثل دستیار شخصی اختصاصی\n" +
                "• اولویت حداکثری پردازش بدون محدودیت روزانه"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FEF3C7"))
            background = roundedBg("#422006", 12, "#F59E0B", 2)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val codeLabel = TextView(this).apply {
            text = "کد فعال‌سازی اشتراک (یا کد تست: VEXA-PRO-800K / VEXA-ALPHA-1500K):"
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(4))
        }

        val codeInput = EditText(this).apply {
            hint = "مثال: VEXA-ALPHA-1500K"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = roundedBg("#1E293B", 10, "#38BDF8", 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        container.addView(currentStatus)
        container.addView(freeCard)
        container.addView(proCard)
        container.addView(alphaCard)
        container.addView(codeLabel)
        container.addView(codeInput)
        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("ارتقا به نسخه پرو و آلفا Vexa AI")
            .setView(scroll)
            .setPositiveButton("فعال‌سازی کد") { _, _ ->
                val res = subs.activateWithCode(codeInput.text.toString())
                updateTierBadgeUi()
                Toast.makeText(this, res.second, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    /**
     * پنجره تربیت و آموزش شخصی وِکسا (مشابه Custom Instructions & Memory در ChatGPT)
     */
    private fun showTrainVexaDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val desc = TextView(this).apply {
            text = "در اینجا می‌توانید وِکسا را دقیقاً مثل ChatGPT تربیت کنید تا شخصیت، لحن، شغل و قوانین شما را برای همیشه یاد بگیرد:"
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, dp(8))
        }

        val customInstrInput = EditText(this).apply {
            hint = "مثال: من امیرمهدی هستم، تریدر و برنامه‌نویس. همیشه پاسخ‌هایت را کوتاه، حرفه‌ای و با مثال عملی بده..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            minLines = 3
            maxLines = 5
            background = roundedBg("#1E293B", 10, "#38BDF8", 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(memoryStore.getCustomInstructions())
        }

        val learnedTitle = TextView(this).apply {
            val facts = memoryStore.getAll()
            val maxR = subs.getCurrentTier().maxTrainingRules
            val maxStr = if (maxR == Int.MAX_VALUE) "نامحدود" else maxR.toString()
            text = "دانش و نکات یادگرفته‌شده (${facts.size} از $maxStr):"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#22D3EE"))
            setPadding(0, dp(12), 0, dp(6))
        }

        val learnedList = TextView(this).apply {
            val facts = memoryStore.getAll()
            text = if (facts.isEmpty()) "هنوز نکته‌ای ثبت نشده است. در چت بگویید: «یاد بگیر که...»" else facts.joinToString("\n") { "• $it" }
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(10))
        }

        val clearBtn = Button(this).apply {
            text = "پاک کردن تمام آموزش‌ها و حافظه"
            textSize = 12f
            setTextColor(Color.parseColor("#FCA5A5"))
            background = roundedBg("#3F1D2E", 10, "#991B1B", 1)
            setOnClickListener {
                memoryStore.clearAll(true)
                customInstrInput.setText("")
                learnedList.text = "تمام آموزش‌ها پاک شدند."
                Toast.makeText(this@MainActivity, "حافظه تربیت پاک شد", Toast.LENGTH_SHORT).show()
            }
        }

        container.addView(desc)
        container.addView(customInstrInput)
        container.addView(learnedTitle)
        container.addView(learnedList)
        container.addView(clearBtn)
        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("🧠 تربیت و آموزش شخصی Vexa AI")
            .setView(scroll)
            .setPositiveButton("ذخیره آموزش") { _, _ ->
                memoryStore.setCustomInstructions(customInstrInput.text.toString())
                Toast.makeText(this, "آموزش‌های جدید در ذهن وِکسا ثبت شد!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("بستن", null)
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
        currentSession = historyStore.createNewSession()
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
                    roundedBg("#1E293B", 10, "#22D3EE", 1)
                } else {
                    roundedBg("#0F172A", 10, "#1E293B", 1)
                }
                setPadding(dp(10), dp(9), dp(8), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(7)
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
                setTextColor(if (isSelected) Color.parseColor("#22D3EE") else Color.parseColor("#F1F5F9"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val dateTv = TextView(this).apply {
                text = s.updatedAt
                textSize = 10f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(2), 0, 0)
            }

            textCol.addView(titleTv)
            textCol.addView(dateTv)

            val delBtn = TextView(this).apply {
                text = "🗑"
                textSize = 13f
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener {
                    historyStore.deleteSession(s.id)
                    if (::currentSession.isInitialized && s.id == currentSession.id) {
                        loadInitialSession()
                    } else {
                        refreshDrawerHistoryList()
                    }
                    Toast.makeText(this@MainActivity, "گفتگو حذف شد", Toast.LENGTH_SHORT).show()
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
        commandInput.hint = "درباره این عکس یا چارت بپرسید…"
    }

    private fun clearAttachedImage() {
        pendingImage = null
        imagePreviewThumb.setImageDrawable(null)
        imagePreviewBar.visibility = View.GONE
        commandInput.hint = "پیام به فارسی یا انگلیسی… (ویدیو، عکس، کد، ترید)"
    }

    private fun addWelcomeMessage() {
        val msg = "سلام! به Vexa AI نسخه ۳ خوش آمدید:\n" +
            "• 🎙️ صدای فارسی عصبی طبیعی (DilaraNeural) + تشخیص گفتار دوزبانه\n" +
            "• 🎬 ساخت ویدیوی سینمایی MP4 («ویدیو بساز از...»)\n" +
            "• 🧠 تربیت و آموزش شخصی مثل ChatGPT (آیکون چرخ‌دنده بالا یا منو)\n" +
            "• 👑 پشتیبانی از نسخه‌های رایگان، پرو (۸۰۰ هزار تومان) و آلفا (۱,۵۰۰ هزار تومان کاملاً نامحدود)"
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
        updateTierBadgeUi()

        if (::currentSession.isInitialized && sender != ChatMessage.Sender.SYSTEM && text.isNotBlank()) {
            if (sender == ChatMessage.Sender.USER && currentSession.messages.none { it.senderName == "USER" }) {
                currentSession.title = text.replace("\n", " ").take(36)
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
        val prompt = txt.ifEmpty { "این تصویر را با جزئیات تحلیل کن." }
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
                micHintView.text = if (speechLang == "fa-IR") "برای صحبت به فارسی، گوی را لمس کنید" else "Tap the orb to speak (English / Persian)"
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.LISTENING -> {
                statusBadge.setTextColor(Color.parseColor("#22D3EE"))
                statusBadge.background = roundedBg("#083344", 20, "#0891B2", 1)
                micIconView.setImageResource(R.drawable.ic_stop)
                micHintView.text = if (speechLang == "fa-IR") "در حال گوش دادن…" else "Listening… (Tap to stop)"
            }
            VoiceOrbView.Mode.THINKING -> {
                statusBadge.setTextColor(Color.parseColor("#C084FC"))
                statusBadge.background = roundedBg("#2E1065", 20, "#7E22CE", 1)
                micHintView.text = if (speechLang == "fa-IR") "وِکسا در حال پردازش / رندر است…" else "Vexa is thinking / rendering…"
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.SPEAKING -> {
                statusBadge.setTextColor(Color.parseColor("#22D3EE"))
                statusBadge.background = roundedBg("#0C2D48", 20, "#1E40AF", 1)
                micHintView.text = if (speechLang == "fa-IR") "وِکسا در حال صحبت به فارسی است…" else "Vexa is speaking…"
                liveTranscriptView.visibility = View.GONE
            }
        }
    }

    private fun showPersonaDialog() {
        val personas = VexaCoreClient.Persona.values()
        val titles = personas.map { "${it.titleEn} | ${it.titleFa}" }.toTypedArray()
        val currentIdx = personas.indexOf(vexaCore.getPersona()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("انتخاب تخصص / Select Vexa Mode")
            .setSingleChoiceItems(titles, currentIdx) { dialog, which ->
                val selected = personas[which]
                vexaCore.setPersona(selected)
                personaChip.text = "${selected.titleEn} ▾"
                Toast.makeText(this, "Switched to ${selected.titleEn}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun speak(text: String) {
        if (!voiceReplyEnabled || text.isBlank()) {
            speaking = false
            if (listening) resumeListening() else setStatus("ONLINE", VoiceOrbView.Mode.IDLE)
            return
        }
        vexaVoice.speak(text)
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
            Toast.makeText(
                this,
                "Speech recognition service unavailable (Google App required)",
                Toast.LENGTH_LONG
            ).show()
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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

        val isFa = VexaCoreClient.isPersianText(text)
        appendMessage(text, ChatMessage.Sender.USER, img)
        if (body.isBlank() && img == null) {
            val greet = if (isFa) "بله؟ وِکسا در خدمت است." else "Yes? Vexa is listening."
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
