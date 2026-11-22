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

/**
 * مغز هوش مصنوعی Vexa AI نسخه ۶:
 * - اتصال هوشمند چندمسیره (Cloudflare Gateway + درگاه ابری پشتیبان) برای رفع کامل مشکل قطع ارتباط بدون VPN
 * - رفتار صمیمی، انسانی، باهوش و طبیعی
 * - تولید تصویر و ویدیوی واقعی MP4
 */
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
            "You are Vexa AI — speak and think like a brilliant, warm, empathetic, and witty human companion, NEVER like a stiff robotic template. " +
            "Understand emotions, humor, slang, and context deeply. Give thoughtful, natural, well-structured answers. " +
            "When speaking Persian, use natural, warm, conversational Persian (فارسی روان، صمیمی و امروزی)."
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
            "You are Vexa AI in Native Translator & Language Expert mode. Translate naturally between Persian and English preserving nuance, idioms, and human tone."
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
    fun generateVideo(ctx: Context, rawPrompt: String, isFa: Boolean): AiResult {
        val scenePrompts = buildVideoScenePrompts(rawPrompt)
        val bitmaps = mutableListOf<Bitmap>()
        for ((idx, sp) in scenePrompts.withIndex()) {
            val bmp = fetchSingleBitmap(sp, seedOffset = idx * 41)
            if (bmp != null) bitmaps.add(bmp)
        }

        if (bitmaps.isEmpty()) {
            bitmaps.add(createFallbackArtBitmap(rawPrompt, 0))
            bitmaps.add(createFallbackArtBitmap(rawPrompt, 1))
            bitmaps.add(createFallbackArtBitmap(rawPrompt, 2))
        }

        val videoFile = VideoGenerator.createCinematicMp4(ctx, bitmaps, rawPrompt)
        if (videoFile == null) {
            val err = if (isFa) "انکود ویدیو روی این گوشی با خطا مواجه شد." else "On-device MP4 video encoding failed."
            return AiResult(err)
        }

        val msg = if (isFa) {
            "🎬 ویدیوی شما (${bitmaps.size * 3} ثانیه HD MP4) آماده شد:\n«$rawPrompt»\nروی دکمه باز کردن بزنید تا پخش شود."
        } else {
            "🎬 Your AI Cinematic Video (${bitmaps.size * 3}s HD MP4) is ready:\n\"$rawPrompt\""
        }
        return AiResult(text = msg, generatedImage = bitmaps.first(), generatedFile = videoFile)
    }

    private fun buildVideoScenePrompts(topic: String): List<String> {
        val base = buildPhotorealisticEnglishPrompt(topic)
        return listOf(
            "$base, wide cinematic establishing shot, golden lighting, 8k",
            "$base, dynamic medium shot, dramatic atmosphere, 8k",
            "$base, epic close-up finale shot, ultra detailed, cinema"
        )
    }

    @Synchronized
    fun generateImage(rawPrompt: String, isFa: Boolean): AiResult {
        val enrichedEnglishPrompt = buildPhotorealisticEnglishPrompt(rawPrompt)
        val bmp = fetchSingleBitmap(enrichedEnglishPrompt, seedOffset = 0)
        if (bmp != null) {
            val caption = if (isFa) {
                "تصویر شما آماده شد:\n«$rawPrompt»"
            } else {
                "Image generated:\n\"$rawPrompt\""
            }
            return AiResult(text = caption, generatedImage = bmp)
        }

        val err = if (isFa) {
            "سرور تصویرساز در این لحظه پاسخ نداد؛ لطفاً اتصال اینترنت را بررسی کرده و دوباره امتحان کنید."
        } else {
            "Image server did not respond. Please check your internet connection and try again."
        }
        return AiResult(err)
    }

    private fun fetchSingleBitmap(englishPrompt: String, seedOffset: Int): Bitmap? {
        val encoded = URLEncoder.encode(englishPrompt, "UTF-8")
        val seed = ((System.currentTimeMillis() + seedOffset) % 999999).toInt()

        val baseGateway = getGatewayUrl().trimEnd('/')
        val candidateUrls = listOf(
            "$baseGateway/image?prompt=$encoded&seed=$seed",
            "https://image.pollinations.ai/prompt/$encoded?model=flux&width=768&height=768&seed=$seed&nologo=true",
            "https://image.pollinations.ai/prompt/$encoded?model=turbo&width=768&height=768&seed=${seed + 1}&nologo=true"
        )

        for (urlStr in candidateUrls) {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null && bmp.width > 64) return bmp
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) { }
        }
        return null
    }

    private fun createFallbackArtBitmap(title: String, sceneIndex: Int): Bitmap {
        val bmp = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colors = when (sceneIndex % 3) {
            0 -> intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#0E7490"), Color.parseColor("#3B82F6"))
            1 -> intArrayOf(Color.parseColor("#1E1B4B"), Color.parseColor("#6D28D9"), Color.parseColor("#EC4899"))
            else -> intArrayOf(Color.parseColor("#042F2E"), Color.parseColor("#059669"), Color.parseColor("#0284C7"))
        }
        val bgPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, 720f, 720f, colors, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 720f, 720f, bgPaint)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(55, 255, 255, 255)
        }
        canvas.drawCircle(360f, 320f, 150f + sceneIndex * 25f, circlePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(title.take(34), 360f, 340f, textPaint)
        return bmp
    }

    private fun buildPhotorealisticEnglishPrompt(userPrompt: String): String {
        if (!isPersianText(userPrompt)) {
            return "$userPrompt, photorealistic, 8k, natural lighting, detailed"
        }
        val sys = "Translate the user's Persian image description into a concise, vivid English image generation prompt (photorealistic, 8k, detailed). Output ONLY the English prompt."
        try {
            val res = callGateway("openai", userPrompt, null, sys, includeHistory = false)
                .replace("\"", "")
                .trim()
            if (res.length > 6) return res
        } catch (_: Exception) { }
        return "$userPrompt, photorealistic, 8k resolution, cinematic lighting"
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
                append("\nThe user's name is $userDisplayName. Address them warmly and naturally like a real human friend/assistant.")
            }
            if (isFa) {
                append("\nمهم: حتماً به زبان فارسی کاملاً روان، طبیعی، صمیمی و انسانی پاسخ بده (مثل یک انسان باهوش و خوش‌برخورد صحبت کن، نه مثل یک ربات خشک).")
            } else {
                append("\nRespond strictly in natural, warm, human-like English.")
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

        return AiResult(localHumanFallback(text, isFa, userDisplayName, lastErr))
    }

    private fun localHumanFallback(text: String, isFa: Boolean, name: String, err: String): String {
        val greetingName = if (name.isNotBlank()) " $name عزیز" else ""
        return if (isFa) {
            "سلام$greetingName! الان ارتباط اینترنتی من با سرور ابری موقتاً برقرار نشد ($err). لطفاً از منوی بالا سمت چپ وارد «تنظیمات اتصال سرور» شوید یا اتصال اینترنت گوشی را بررسی کنید. تمام ابزارهای آفلاین گوشی مثل تماس، پیامک، چراغ قوه، ماشین‌حساب، تاریخ شمسی و یادداشت همین الان فعال هستند."
        } else {
            "Hey $name! Cloud connection failed ($err). Check your internet connection or configure your gateway URL in the side menu."
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

        // اگر از مسیر پشتیبان مستقیم استفاده کنیم یا مدل openai باشد، همواره روی هر اینترنتی پاسخ می‌دهد
        val endpoints = listOf(getGatewayUrl(), FALLBACK_CLOUD_URL).distinct()
        var lastEx: Exception = IOException("Connection failed")

        for (ep in endpoints) {
            val effectiveModel = if (ep.contains("pollinations.ai")) "openai" else model
            val body = JSONObject()
                .put("model", effectiveModel)
                .put("messages", messages)

            val conn = URL(ep).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 12000
                conn.readTimeout = 35000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VexaAI/6.0")
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
            } finally {
                conn.disconnect()
            }
        }
        throw lastEx
    }
}
