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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var memoryStore: MemoryStore
    private lateinit var vexaCore: VexaCoreClient
    private lateinit var processor: CommandProcessor
    private lateinit var vexaVoice: VexaVoiceEngine

    private var recognizer: SpeechRecognizer? = null

    private var listening = false
    private var speaking = false
    private var voiceReplyEnabled = true
    private var speechLang = "en-US"
    private var activeUntil = 0L
    private val activeWindowMs = 25_000L
    private val handler = Handler(Looper.getMainLooper())

    private var pendingImage: Bitmap? = null

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
        speechLang = prefs.getString("speech_lang", "en-US") ?: "en-US"

        memoryStore = MemoryStore(this)
        vexaCore = VexaCoreClient(prefs, memoryStore)
        processor = CommandProcessor(this, PhoneActions(this), vexaCore, memoryStore)

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

        buildGlobalUi()
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

    private fun buildGlobalUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050811"))
        }

        // ==================== 1. Top Header ====================
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#0A101E"))
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_vexa_logo)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(9)
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
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        statusBadge = TextView(this).apply {
            text = "ONLINE"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#34D399"))
            background = roundedBg("#064E3B", 20, "#059669", 1)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        titleRow.addView(titleText)
        titleRow.addView(statusBadge)

        personaChip = TextView(this).apply {
            text = "${vexaCore.getPersona().titleEn} ▾"
            textSize = 11f
            setTextColor(Color.parseColor("#22D3EE"))
            setPadding(0, dp(2), 0, 0)
            setOnClickListener { showPersonaDialog() }
        }

        titleCol.addView(titleRow)
        titleCol.addView(personaChip)

        langToggleBtn = TextView(this).apply {
            text = if (speechLang == "fa-IR") "🌐 FA" else "🌐 EN"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                marginEnd = dp(5)
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
            setPadding(dp(9), dp(7), dp(9), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                marginEnd = dp(5)
            }
            setOnClickListener {
                voiceReplyEnabled = !voiceReplyEnabled
                prefs.edit().putBoolean("voice_reply", voiceReplyEnabled).apply()
                text = if (voiceReplyEnabled) "🔊" else "🔇"
                if (!voiceReplyEnabled) vexaVoice.stop()
                Toast.makeText(
                    this@MainActivity,
                    if (voiceReplyEnabled) "Voice output ON" else "Voice output MUTED",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val clearBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_trash)
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Clear Chat"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(5)
            }
            setOnClickListener {
                vexaVoice.stop()
                chatAdapter.clearAll()
                addWelcomeMessage()
                Toast.makeText(this@MainActivity, "Chat cleared", Toast.LENGTH_SHORT).show()
            }
        }

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = roundedBg("#111827", 12, "#1E293B", 1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Settings & Memory"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { showSettingsDialog() }
        }

        topBar.addView(logoIcon)
        topBar.addView(titleCol)
        topBar.addView(langToggleBtn)
        topBar.addView(voiceToggleBtn)
        topBar.addView(clearBtn)
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
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
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
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
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

        // ==================== 4. Global Quick Action Chips ====================
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val quickCommands = listOf(
            "🎨 Generate Image" to "Generate an image of a futuristic glowing cyber wolf in Tokyo at night",
            "📈 Bitcoin Analysis" to "Analyze Bitcoin live price, RSI, and market trend",
            "🧮 Risk Calculator" to "Position size with balance 1000 risk 2 stop 5",
            "📄 Create PDF" to "Create a PDF file about AI trading risk management principles",
            "🐍 Python Script" to "Create a Python file for a crypto RSI trading bot",
            "📊 Chart Vision" to "__GALLERY__",
            "🎨 تولید عکس فارسی" to "عکس بساز از یک شهر مدرن آینده‌نگر در شب با نورهای نئونی",
            "🔋 Battery Status" to "What is my battery level?"
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
            text = "Image / Chart attached (Ask Vexa or tap Send)"
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
            hint = "Ask Vexa in English or Persian…"
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
            text = "Wake Word Mode: Respond only when saying \"Vexa\" / «وِکسا»"
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

        root.addView(topBar)
        root.addView(topDivider)
        root.addView(orbStage)
        root.addView(chatRecycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(chipScroll)
        root.addView(bottomDivider)
        root.addView(bottomPanel)

        setContentView(root)
        addWelcomeMessage()
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
        commandInput.hint = "Ask about this image or trading chart…"
    }

    private fun clearAttachedImage() {
        pendingImage = null
        imagePreviewThumb.setImageDrawable(null)
        imagePreviewBar.visibility = View.GONE
        commandInput.hint = "Ask Vexa in English or Persian…"
    }

    private fun addWelcomeMessage() {
        val msg = "Welcome to Vexa AI — Global Bilingual Assistant!\n" +
            "• 🎙️ Speak or type in English or Persian (Switch 🌐 EN/FA anytime)\n" +
            "• 🎨 Generate AI Images & save directly to Gallery\n" +
            "• 📂 Create real PDF, Python, CSV & HTML files\n" +
            "• 📈 Live Crypto/Gold analysis, RSI-14, Chart Vision & Risk Calculator\n" +
            "• 📱 Full Android control (Calls, SMS, WhatsApp, Maps, YouTube & more)"
        appendMessage(msg, ChatMessage.Sender.VEXA)
    }

    private fun appendMessage(
        text: String,
        sender: ChatMessage.Sender,
        image: Bitmap? = null,
        generatedFile: FileGenerator.GeneratedFile? = null
    ) {
        chatAdapter.addMessage(ChatMessage(text, sender, timeFmt.format(Date()), image, generatedFile))
        chatRecycler.post {
            chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
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
                micHintView.text = if (speechLang == "fa-IR") "وِکسا در حال پردازش است…" else "Vexa is thinking…"
                liveTranscriptView.visibility = View.GONE
            }
            VoiceOrbView.Mode.SPEAKING -> {
                statusBadge.setTextColor(Color.parseColor("#22D3EE"))
                statusBadge.background = roundedBg("#0C2D48", 20, "#1E40AF", 1)
                micHintView.text = if (speechLang == "fa-IR") "وِکسا در حال صحبت است…" else "Vexa is speaking…"
                liveTranscriptView.visibility = View.GONE
            }
        }
    }

    private fun showPersonaDialog() {
        val personas = VexaCoreClient.Persona.values()
        val titles = personas.map { "${it.titleEn} | ${it.titleFa}" }.toTypedArray()
        val currentIdx = personas.indexOf(vexaCore.getPersona()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Select Vexa AI Mode / انتخاب تخصص")
            .setSingleChoiceItems(titles, currentIdx) { dialog, which ->
                val selected = personas[which]
                vexaCore.setPersona(selected)
                personaChip.text = "${selected.titleEn} ▾"
                Toast.makeText(this, "Switched to ${selected.titleEn}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }

        val statusInfo = TextView(this).apply {
            text = "✅ Edge Gateway: Connected (No VPN / No API Key needed)\n" +
                "✅ Bilingual Voice Engine: Active (English & Persian)"
            textSize = 12.5f
            setTextColor(Color.parseColor("#34D399"))
            setPadding(0, 0, 0, dp(12))
        }

        val memTitle = TextView(this).apply {
            val count = memoryStore.getAll().size
            text = "Permanent Long-Term Memory ($count items):"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#22D3EE"))
            setPadding(0, dp(4), 0, dp(6))
        }

        val memList = TextView(this).apply {
            val facts = memoryStore.getAll()
            text = if (facts.isEmpty()) "No saved facts yet. Say \"Remember that...\"" else facts.joinToString("\n") { "• $it" }
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, dp(10))
        }

        val clearMemBtn = Button(this).apply {
            text = "Clear Permanent Memory"
            setTextColor(Color.parseColor("#FCA5A5"))
            background = roundedBg("#3F1D2E", 10, "#991B1B", 1)
            setOnClickListener {
                memoryStore.clearAll(false)
                memList.text = "Memory cleared."
                Toast.makeText(this@MainActivity, "Permanent memory cleared", Toast.LENGTH_SHORT).show()
            }
        }

        val gatewayLabel = TextView(this).apply {
            text = "Custom Edge Gateway URL (Advanced):"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(14), 0, dp(4))
        }

        val gatewayInput = EditText(this).apply {
            hint = VexaCoreClient.DEFAULT_GATEWAY_URL
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            background = roundedBg("#1E293B", 10, "#334155", 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setText(prefs.getString("gateway_url", ""))
        }

        container.addView(statusInfo)
        container.addView(memTitle)
        container.addView(memList)
        container.addView(clearMemBtn)
        container.addView(gatewayLabel)
        container.addView(gatewayInput)

        AlertDialog.Builder(this)
            .setTitle("Vexa AI Settings & Memory")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("gateway_url", gatewayInput.text.toString().trim()).apply()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
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
