package com.vexa.ai

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class VexaCoreClient(
    private val prefs: SharedPreferences,
    private val memory: MemoryStore,
    private val subs: SubscriptionManager
) {

    companion object {
        const val DEFAULT_GATEWAY_URL = "https://wolf-ai.amirmahdi-akhgari1390.workers.dev"
        const val FALLBACK_CLOUD_URL = "https://text.pollinations.ai/openai"

        fun isPersianText(text: String): Boolean {
            return Regex("[\u0600-\u06FF]").containsMatchIn(text)
        }

        private val SEXUAL_KEYWORDS = listOf(
            "سکس", "پورن", "جنسی", "لخت", "برهنه", "عریان", "شهوانی", "اروتیک", "ممه", "کص", "کیر", "کس",
            "sex", "porn", "nude", "naked", "nsfw", "erotic", "xxx", "hentai", "fetish", "onlyfans"
        )

        fun containsSexualContent(prompt: String): Boolean {
            val lower = prompt.lowercase(Locale.ROOT)
            return SEXUAL_KEYWORDS.any { lower.contains(it) }
        }

        fun isLogoRequest(prompt: String): Boolean {
            val lower = prompt.lowercase(Locale.ROOT)
            return Regex("(لوگو|آرم|نشان تجاری|برندینگ|logo|brand mark|emblem|icon logo)").containsMatchIn(lower)
        }
    }

    private val frontierModels = listOf(
        "google/gemma-3-27b-it:free",
        "qwen/qwen3-235b-a22b:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "openrouter/free",
        "openai"
    )

    private val visionModels = listOf(
        "google/gemma-3-27b-it:free",
        "nvidia/nemotron-3.5-super-vl:free",
        "openrouter/free",
        "openai"
    )

    private val maxHistory = 32
    private val history = ArrayList<Pair<String, String>>()

    enum class Persona(val titleEn: String, val titleFa: String, val instruction: String) {
        ASSISTANT(
            "Vexa Human-Like AI",
            "وِکسا هوشمند (صمیمی و انسانی)",
            "You are Vexa AI — a warm, sharp, human-like assistant. Understand spoken/colloquial Persian and English naturally and accurately."
        ),
        TRADER(
            "Quantitative Market Analyst",
            "تحلیلگر ارشد ترید و بازارهای مالی",
            "You are Vexa AI in Quantitative Market Analyst mode. Analyze price action, support/resistance, RSI, trend structure, entry zones, Stop Loss (SL), and Take Profit (TP) clearly."
        ),
        CODER(
            "Principal Software Architect",
            "مهندس ارشد برنامه‌نویسی",
            "You are Vexa AI in Principal Software Architect mode. Write complete, clean, bug-free, ready-to-run code with clear human-friendly explanations."
        ),
        TUTOR(
            "Native Translator & Tutor",
            "مترجم تخصصی و معلم دوزبانه",
            "You are Vexa AI in Native Translator & Language Expert mode. Translate naturally and accurately while preserving exact meaning and tone."
        ),
        CREATIVE(
            "Creative Director & Scriptwriter",
            "کارگردان خلاق و فیلم‌نامه‌نویس",
            "You are Vexa AI in Creative Director mode. Craft compelling stories, video scripts, and creative concepts."
        )
    }

    data class AiResult(
        val text: String,
        val generatedImage: Bitmap? = null,
        val generatedFile: FileGenerator.GeneratedFile? = null,
        val isCodeOrTechnical: Boolean = false
    )

    fun getGatewayUrl(): String {
        val custom = prefs.getString("gateway_url", "").orEmpty().trim()
        return custom.ifEmpty { DEFAULT_GATEWAY_URL }
    }

    fun getPersona(): Persona {
        val name = prefs.getString("persona", Persona.ASSISTANT.name) ?: Persona.ASSISTANT.name
        return try { Persona.valueOf(name) } catch (e: Exception) { Persona.ASSISTANT }
    }

    fun setPersona(p: Persona) {
        prefs.edit().putString("persona", p.name).apply()
    }

    @Synchronized
    fun clear() = history.clear()

    @Synchronized
    fun restoreHistory(storedMessages: List<ChatHistoryStore.StoredMessage>) {
        history.clear()
        for (m in storedMessages.takeLast(maxHistory)) {
            if (m.senderName == "USER") {
                history.add("user" to m.text)
            } else if (m.senderName == "VEXA") {
                history.add("model" to m.text)
            }
        }
    }

    @Synchronized
    fun generateVideoWithSound(ctx: Context, rawPrompt: String, isFa: Boolean): AiResult {
        if (containsSexualContent(rawPrompt)) {
            val blockMsg = if (isFa) {
                "⛔ امکان ساخت ویدیو یا تصویر با محتوای جنسی و غیراخلاقی در Vexa AI وجود ندارد. لطفاً موضوع مناسب و سالمی را مطرح کنید."
            } else {
                "⛔ Generating sexually explicit or adult video/image content is not permitted in Vexa AI."
            }
            return AiResult(blockMsg)
        }

        val coreSubjectEn = translateSubjectForMedia(rawPrompt, isLogo = false).ifBlank { rawPrompt }
        val scenePrompts = listOf(
            "$coreSubjectEn, wide cinematic shot, natural lighting, photorealistic, 8k",
            "$coreSubjectEn, medium cinematic angle, dynamic motion, photorealistic, 8k",
            "$coreSubjectEn, dramatic close-up detail shot, cinematic lighting, 8k"
        )

        val bitmaps = mutableListOf<Bitmap>()
        val baseSeed = (System.currentTimeMillis() % 899999).toInt() + 1000
        for ((idx, sp) in scenePrompts.withIndex()) {
            val bmp = fetchSingleBitmap(sp, seed = baseSeed + idx * 17)
            if (bmp != null) bitmaps.add(bmp)
        }

        if (bitmaps.isEmpty()) {
            bitmaps.add(createStudioFallbackBitmap(rawPrompt, 0, isLogo = false))
            bitmaps.add(createStudioFallbackBitmap(rawPrompt, 1, isLogo = false))
            bitmaps.add(createStudioFallbackBitmap(rawPrompt, 2, isLogo = false))
        }

        val videoFile = VideoGenerator.createCinematicMp4(ctx, bitmaps, rawPrompt, withSound = true)
        if (videoFile == null) {
            return AiResult(
                text = if (isFa) "🎬 صحنه ویدیو آماده شد:\n«$rawPrompt»" else "🎬 Video scene ready:\n\"$rawPrompt\"",
                generatedImage = bitmaps.first()
            )
        }

        val durationSec = bitmaps.size * 2
        val msg = if (isFa) {
            "🎬🔊 ویدیوی صدادار شما ($durationSec ثانیه HD MP4) ساخته شد:\n«$rawPrompt»\nروی «باز کردن» بزنید تا با صدا پخش شود."
        } else {
            "🎬🔊 Your AI Video with sound (${durationSec}s HD MP4) is ready:\n\"$rawPrompt\""
        }
        return AiResult(text = msg, generatedImage = bitmaps.first(), generatedFile = videoFile)
    }

    @Synchronized
    fun generateImageOrLogo(rawPrompt: String, isFa: Boolean): AiResult {
        if (containsSexualContent(rawPrompt)) {
            val blockMsg = if (isFa) {
                "⛔ امکان تولید تصویر یا ویدیو با محتوای جنسی و غیراخلاقی در Vexa AI وجود ندارد. لطفاً سوژه دیگری انتخاب کنید."
            } else {
                "⛔ Generating sexually explicit or adult images is not allowed in Vexa AI."
            }
            return AiResult(blockMsg)
        }

        val logoMode = isLogoRequest(rawPrompt)
        val coreSubjectEn = translateSubjectForMedia(rawPrompt, isLogo = logoMode).ifBlank { rawPrompt }

        val finalPrompt = if (logoMode) {
            "minimalist vector logo design for $coreSubjectEn, flat modern geometric emblem, clean solid background, centered icon, no cluttered scene, high contrast brand identity"
        } else {
            "$coreSubjectEn, photorealistic photograph, realistic lighting, natural textures, sharp focus, 8k"
        }

        val seed = (System.currentTimeMillis() % 899999).toInt() + 1000
        val bmp = fetchSingleBitmap(finalPrompt, seed = seed)
            ?: createStudioFallbackBitmap(rawPrompt, 0, isLogo = logoMode)

        val caption = if (logoMode) {
            if (isFa) "✒️ لوگوی پیشنهادی شما آماده شد:\n«$rawPrompt»" else "✒️ Your logo design is ready:\n\"$rawPrompt\""
        } else {
            if (isFa) "📸 تصویر شما آماده شد:\n«$rawPrompt»" else "📸 Your image is ready:\n\"$rawPrompt\""
        }
        return AiResult(text = caption, generatedImage = bmp)
    }

    private fun createStudioFallbackBitmap(title: String, sceneIndex: Int, isLogo: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colors = if (isLogo) {
            intArrayOf(Color.parseColor("#070B16"), Color.parseColor("#0F172A"), Color.parseColor("#0891B2"))
        } else {
            when (sceneIndex % 3) {
                0 -> intArrayOf(Color.parseColor("#090D16"), Color.parseColor("#0E7490"), Color.parseColor("#2563EB"))
                1 -> intArrayOf(Color.parseColor("#180C2E"), Color.parseColor("#7E22CE"), Color.parseColor("#DB2777"))
                else -> intArrayOf(Color.parseColor("#052E2B"), Color.parseColor("#0D9488"), Color.parseColor("#0284C7"))
            }
        }
        val bgPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, 720f, 720f, colors, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 720f, 720f, bgPaint)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(65, 255, 255, 255)
        }
        canvas.drawCircle(360f, 320f, 140f + sceneIndex * 18f, circlePaint)

        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(if (isLogo) "VEXA LOGO DESIGN" else "VEXA AI STUDIO", 360f, 130f, tagPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(title.take(36), 360f, 360f, textPaint)
        return bmp
    }

    private fun translateSubjectForMedia(userPrompt: String, isLogo: Boolean): String {
        val clean = userPrompt.trim()
        if (!isPersianText(clean)) return clean

        val sys = if (isLogo) {
            "Translate the user's Persian logo/brand request into a clean English logo subject description. Output ONLY the English text."
        } else {
            "Translate the user's Persian spoken or written image/video description into a clear, accurate English description. Understand colloquial Persian phrases naturally. Output ONLY the English text."
        }

        for (model in listOf("google/gemma-3-27b-it:free", "openai")) {
            try {
                val res = callGateway(model, clean, null, sys, includeHistory = false)
                    .replace("\"", "")
                    .replace("\n", " ")
                    .trim()
                if (res.length >= 2 && !isPersianText(res)) {
                    return res
                }
            } catch (_: Exception) { }
        }
        return ""
    }

    private fun fetchSingleBitmap(englishPrompt: String, seed: Int): Bitmap? {
        val encoded = URLEncoder.encode(englishPrompt, "UTF-8")
        val baseGateway = getGatewayUrl().trimEnd('/')

        val candidateUrls = listOf(
            "$baseGateway/image?prompt=$encoded&seed=$seed",
            "https://image.pollinations.ai/prompt/$encoded?width=768&height=768&seed=$seed&nologo=true&model=flux",
            "https://image.pollinations.ai/prompt/$encoded?width=768&height=768&seed=${seed + 5}&nologo=true&model=turbo",
            "https://image.pollinations.ai/prompt/$encoded?width=512&height=512&seed=${seed + 9}&nologo=true"
        )

        for (urlStr in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 35000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                conn.setRequestProperty("Accept", "image/*,*/*")
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null && bmp.width >= 128 && bmp.height >= 128) {
                        return bmp
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }
        return null
    }

    @Synchronized
    fun generateRawFileContent(ext: String, topic: String): String? {
        val sys = "You are a file content generator. Output ONLY the raw, clean, complete content for a .$ext file about the user's request. " +
            "Do NOT wrap in markdown code fences (no ```). Do NOT add conversational intro or outro."
        for (m in frontierModels) {
            try {
                val res = callGateway(m, "File type: .$ext\nRequest: $topic", null, sys, includeHistory = false)
                    .replace(Regex("^```[a-zA-Z]*\\n?"), "")
                    .replace(Regex("\\n?```$"), "")
                    .trim()
                if (res.isNotEmpty()) return res
            } catch (_: Exception) { }
        }
        return null
    }

    @Synchronized
    fun ask(
        text: String,
        image: Bitmap? = null,
        forcePersona: Persona? = null,
        extraMarketContext: String = "",
        userDisplayName: String = ""
    ): AiResult {
        memory.autoLearnFromUserMessage(text, Int.MAX_VALUE)

        val isFa = isPersianText(text)
        val isTranslationReq = Regex("(ترجمه|معنی|به فارسی|به انگلیسی|برگردان|translate|meaning of|in persian|in english)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        val activePersona = forcePersona ?: if (isTranslationReq) Persona.TUTOR else getPersona()

        val isCodeReq = activePersona == Persona.CODER ||
            Regex("(کد|برنامه|اسکریپت|پایتون|جاوا|کاتلین|code|program|script|python|java|kotlin|html|css|js|sql|bug|error|function|algorithm)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

        val sysPrompt = buildString {
            append(activePersona.instruction)
            if (userDisplayName.isNotBlank()) {
                append("\nUser name: $userDisplayName.")
            }
            if (isFa) {
                append("\nبسیار مهم: پیام کاربر به زبان فارسی است. دقیقاً و با دقت کامل متوجه منظور فارسی کاربر شو، حتی اگر محاوره‌ای یا کوتاه باشد، و پاسخ را به زبان فارسی روان و دقیق بنویس.")
            } else {
                append("\nDetect the user's language and reply fluently and naturally in that exact same language.")
            }
            if (extraMarketContext.isNotEmpty()) {
                append("\n\n").append(extraMarketContext)
            }
            append(memory.asPromptContext())
        }

        val modelsToTry = if (image != null) visionModels else frontierModels
        var lastErr = ""
        for (m in modelsToTry) {
            try {
                val rawAnswer = callGateway(m, text, image, sysPrompt, includeHistory = true)
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .trim()

                if (isFa && !isCodeReq && !isPersianText(rawAnswer) && rawAnswer.isNotEmpty()) {
                    continue
                }

                val finalAnswer = if (isCodeReq || activePersona == Persona.TRADER || extraMarketContext.isNotEmpty()) {
                    rawAnswer
                } else {
                    rawAnswer.replace(Regex("[*#`_]"), "").trim()
                }

                if (finalAnswer.isNotEmpty()) {
                    val userLog = if (image != null) "[Image attached] $text" else text
                    history.add("user" to userLog)
                    history.add("model" to finalAnswer)
                    while (history.size > maxHistory) history.removeAt(0)
                    return AiResult(finalAnswer, isCodeOrTechnical = isCodeReq)
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }

        return AiResult(localHumanFallback(isFa, userDisplayName, lastErr))
    }

    private fun localHumanFallback(isFa: Boolean, name: String, err: String): String {
        val greetingName = if (name.isNotBlank()) " $name عزیز" else ""
        return if (isFa) {
            "سلام$greetingName! ارتباط با سرور ابری موقتاً ناپایدار شد ($err). لطفاً اتصال اینترنت را بررسی کرده و دوباره پیام را ارسال کنید."
        } else {
            "Hey $name! Cloud connection was temporarily interrupted ($err). Please check your internet and try again."
        }
    }

    private fun bitmapToDataUrl(bmp: Bitmap): String {
        val maxDim = 900
        val scale = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height, 1f)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun callGateway(
        model: String,
        text: String,
        image: Bitmap?,
        systemInstructionText: String,
        includeHistory: Boolean
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemInstructionText))

        if (includeHistory) {
            for ((role, t) in history) {
                val mappedRole = if (role == "model") "assistant" else "user"
                messages.put(JSONObject().put("role", mappedRole).put("content", t))
            }
        }

        if (image != null) {
            val contentArr = JSONArray()
            contentArr.put(JSONObject().put("type", "text").put("text", text.ifBlank { "Analyze this image in detail." }))
            contentArr.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", bitmapToDataUrl(image)))
            )
            messages.put(JSONObject().put("role", "user").put("content", contentArr))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", text))
        }

        val endpoints = listOf(getGatewayUrl(), FALLBACK_CLOUD_URL).distinct()
        var lastEx: Exception = IOException("Connection failed")

        for (ep in endpoints) {
            val effectiveModel = if (ep.contains("pollinations.ai")) "openai" else model
            val body = JSONObject()
                .put("model", effectiveModel)
                .put("messages", messages)

            for (attempt in 1..2) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(ep).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 45000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Connection", "close")
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VexaAI/13.0")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                    val code = conn.responseCode
                    if (code != 200) throw IOException("HTTP $code")
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val choices = JSONObject(resp).optJSONArray("choices") ?: throw IOException("Empty choices")
                    if (choices.length() == 0) throw IOException("No choice")
                    val msgObj = choices.getJSONObject(0).getJSONObject("message")
                    val content = msgObj.optString("content", "")
                    if (content.isNotBlank()) return content
                } catch (e: Exception) {
                    lastEx = e
                    try { Thread.sleep(400L) } catch (_: InterruptedException) { }
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) { }
                }
            }
        }
        throw lastEx
    }
}
