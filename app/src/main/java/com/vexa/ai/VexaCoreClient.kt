package com.vexa.ai

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class VexaCoreClient(
    private val prefs: SharedPreferences,
    private val memory: MemoryStore,
    private val subs: SubscriptionManager
) {

    companion object {
        const val DEFAULT_GATEWAY_URL = "https://wolf-ai.amirmahdi-akhgari1390.workers.dev"

        fun isPersianText(text: String): Boolean {
            return Regex("[\u0600-\u06FF]").containsMatchIn(text)
        }
    }

    // Frontier Reasoning & Flagship Models for Pro/Alpha tiers (Claude-level depth)
    private val frontierModels = listOf(
        "qwen/qwen3-235b-a22b:free",
        "deepseek/deepseek-r1:free",
        "google/gemma-3-27b-it:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        "openrouter/free"
    )

    // Standard models for Free tier
    private val standardModels = listOf(
        "google/gemma-3-27b-it:free",
        "qwen/qwen3.8-27b:free",
        "openrouter/free"
    )

    private val visionModels = listOf(
        "google/gemma-3-27b-it:free",
        "nvidia/nemotron-3.5-super-vl:free",
        "openrouter/free"
    )

    private val maxHistory = 32
    private val history = ArrayList<Pair<String, String>>()

    enum class Persona(val titleEn: String, val titleFa: String, val instruction: String) {
        ASSISTANT(
            "Vexa Frontier Intelligence",
            "وِکسا فوق‌هوشمند (استدلال عمیق)",
            "You are Vexa AI, a frontier-grade global AI assistant rivaling the world's top reasoning models. " +
            "Think deeply, verify facts, structure answers clearly, and provide complete, thorough solutions without lazy shortcuts. " +
            "When responding in Persian, write fluent, natural, standard Persian (فارسی معیار و روان)."
        ),
        TRADER(
            "Quantitative Market Analyst",
            "تحلیلگر ارشد ترید و بازارهای مالی",
            "You are Vexa AI in Quantitative Market Analyst mode (Crypto, Forex, Gold, Stocks). " +
            "Provide deep institutional-grade technical analysis: price action, support/resistance, RSI, trend structure, entry zones, Stop Loss (SL), and Take Profit (TP)."
        ),
        CODER(
            "Principal Software Architect",
            "مهندس ارشد برنامه‌نویسی",
            "You are Vexa AI in Principal Software Architect mode. Write complete, production-ready, zero-bug code with architectural clarity."
        ),
        TUTOR(
            "Native Translator & Tutor",
            "مترجم تخصصی و معلم دوزبانه",
            "You are Vexa AI in Native Translator & Language Expert mode. Translate naturally between Persian and English preserving nuance, idioms, and tone."
        ),
        CREATIVE(
            "Creative Director & Scriptwriter",
            "کارگردان خلاق و فیلم‌نامه‌نویس",
            "You are Vexa AI in Creative Director mode. Craft compelling stories, video scripts, and high-converting content."
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

    /**
     * ساخت ویدیو واقعی MP4 با ۳ صحنه سینمایی هوش مصنوعی
     */
    @Synchronized
    fun generateVideo(ctx: Context, rawPrompt: String, isFa: Boolean): AiResult {
        val allowed = subs.canUse(SubscriptionManager.Feature.VIDEO)
        if (!allowed.first) return AiResult(allowed.second)

        val scenePrompts = buildVideoScenePrompts(rawPrompt)
        val bitmaps = mutableListOf<Bitmap>()
        for ((idx, sp) in scenePrompts.withIndex()) {
            val bmp = fetchSingleBitmap(sp, seedOffset = idx * 37)
            if (bmp != null) bitmaps.add(bmp)
        }

        if (bitmaps.isEmpty()) {
            val err = if (isFa) "تولید صحنه‌های ویدیو با اختلال اینترنت مواجه شد؛ لطفاً دوباره تلاش کنید."
            else "Failed to generate video frames. Please check your connection."
            return AiResult(err)
        }

        val videoFile = VideoGenerator.createCinematicMp4(ctx, bitmaps, rawPrompt)
        if (videoFile == null) {
            val err = if (isFa) "انکود ویدیو روی دستگاه ناموفق بود." else "On-device MP4 video encoding failed."
            return AiResult(err)
        }

        subs.recordUsage(SubscriptionManager.Feature.VIDEO)
        val msg = if (isFa) {
            "🎬 ویدیوی هوشمند شما (${bitmaps.size * 3} ثانیه HD MP4) ساخته شد:\n«$rawPrompt»\nروی دکمه باز کردن بزنید تا پخش شود."
        } else {
            "🎬 Your AI Cinematic Video (${bitmaps.size * 3}s HD MP4) is ready:\n\"$rawPrompt\""
        }
        return AiResult(text = msg, generatedImage = bitmaps.first(), generatedFile = videoFile)
    }

    private fun buildVideoScenePrompts(topic: String): List<String> {
        val sys = """
            You are a cinematic video director. Break the user's video idea into exactly 3 sequential visual scenes.
            Output ONLY 3 lines in English (one photorealistic, 8k cinematic scene description per line). No numbering, no bullets, no extra text.
        """.trimIndent()
        try {
            val res = callGateway("google/gemma-3-27b-it:free", topic, null, sys, includeHistory = false)
            val lines = res.lines().map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ') }.filter { it.length > 10 }
            if (lines.size >= 3) return lines.take(3)
        } catch (_: Exception) { }

        val base = buildPhotorealisticEnglishPrompt(topic)
        return listOf(
            "$base, wide establishing shot, golden hour lighting, 8k cinema",
            "$base, dynamic medium angle shot, dramatic atmospheric depth, 8k",
            "$base, epic close-up finale shot, ultra detailed, cinematic color grading"
        )
    }

    /**
     * تولید تصویر واقع‌گرایانه با کنترل سهمیه اشتراک
     */
    @Synchronized
    fun generateImage(rawPrompt: String, isFa: Boolean): AiResult {
        val allowed = subs.canUse(SubscriptionManager.Feature.IMAGE)
        if (!allowed.first) return AiResult(allowed.second)

        val enrichedEnglishPrompt = buildPhotorealisticEnglishPrompt(rawPrompt)
        val bmp = fetchSingleBitmap(enrichedEnglishPrompt, seedOffset = 0)
        if (bmp != null) {
            subs.recordUsage(SubscriptionManager.Feature.IMAGE)
            val caption = if (isFa) {
                "تصویر با کیفیت بالا ساخته شد:\n«$rawPrompt»"
            } else {
                "High-resolution image generated:\n\"$rawPrompt\""
            }
            return AiResult(text = caption, generatedImage = bmp)
        }

        val err = if (isFa) {
            "تولید تصویر با تأخیر سرور مواجه شد؛ لطفاً اتصال اینترنت را بررسی کرده و دوباره تلاش کنید."
        } else {
            "Image generation server timed out. Please check your connection and try again."
        }
        return AiResult(err)
    }

    private fun fetchSingleBitmap(englishPrompt: String, seedOffset: Int): Bitmap? {
        val encoded = URLEncoder.encode(englishPrompt, "UTF-8")
        val seed = ((System.currentTimeMillis() + seedOffset) % 999999).toInt()
        val size = if (subs.getCurrentTier() == SubscriptionManager.Tier.FREE) 768 else 1024

        val candidateUrls = listOf(
            "https://image.pollinations.ai/prompt/$encoded?model=flux-realism&width=$size&height=$size&seed=$seed&nologo=true&enhance=true",
            "https://image.pollinations.ai/prompt/$encoded?model=flux&width=$size&height=$size&seed=${seed + 1}&nologo=true",
            "https://image.pollinations.ai/prompt/$encoded?model=turbo&width=768&height=768&seed=${seed + 2}&nologo=true"
        )

        for (urlStr in candidateUrls) {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VexaAI/3.0")
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

    private fun buildPhotorealisticEnglishPrompt(userPrompt: String): String {
        val sys = """
            You are an expert AI photography & art prompt engineer.
            Convert the user's request (Persian or English) into a vivid, detailed English image prompt.
            Rules:
            1. Unless the user explicitly asks for cartoon, anime, 3D render, painting, or logo, ALWAYS make it ultra-photorealistic: realistic lighting, natural textures, depth of field, 8k DSLR photography, sharp focus.
            2. Accurately translate every Persian noun, adjective, setting, and detail.
            3. Output ONLY the final English prompt (max 60 words), no quotes.
        """.trimIndent()

        for (m in standardModels) {
            try {
                val res = callGateway(m, userPrompt, null, sys, includeHistory = false)
                    .replace("\"", "")
                    .trim()
                if (res.length > 8) return res
            } catch (_: Exception) { }
        }
        return "$userPrompt, photorealistic, 8k resolution, natural lighting, ultra detailed"
    }

    @Synchronized
    fun generateRawFileContent(ext: String, topic: String): String? {
        val allowed = subs.canUse(SubscriptionManager.Feature.FILE)
        if (!allowed.first) return "__LIMIT_REACHED__:${allowed.second}"

        val sys = "You are a file content generator. Output ONLY the raw, clean, complete content for a .$ext file about the user's request. " +
            "Do NOT wrap in markdown code fences (no ```). Do NOT add conversational intro or outro. " +
            "If ext is csv, output valid comma-separated rows. If ext is py/kt/html/js/json, output valid executable code. If ext is pdf/txt/md, write a comprehensive, well-structured document in the user's language."
        val models = if (subs.getCurrentTier() == SubscriptionManager.Tier.FREE) standardModels else frontierModels
        for (m in models) {
            try {
                val res = callGateway(m, "File type: .$ext\nRequest: $topic", null, sys, includeHistory = false)
                    .replace(Regex("^```[a-zA-Z]*\\n?"), "")
                    .replace(Regex("\\n?```$"), "")
                    .trim()
                if (res.isNotEmpty()) {
                    subs.recordUsage(SubscriptionManager.Feature.FILE)
                    return res
                }
            } catch (_: Exception) { }
        }
        return null
    }

    @Synchronized
    fun ask(
        text: String,
        image: Bitmap? = null,
        forcePersona: Persona? = null,
        extraMarketContext: String = ""
    ): AiResult {
        val allowed = subs.canUse(SubscriptionManager.Feature.CHAT)
        if (!allowed.first) return AiResult(allowed.second)

        val tier = subs.getCurrentTier()
        // Auto-learn user preferences/facts from their conversation (like ChatGPT Memory)
        memory.autoLearnFromUserMessage(text, tier.maxTrainingRules)

        val isFa = isPersianText(text)
        val isTranslationReq = Regex("(ترجمه|معنی|به فارسی|به انگلیسی|برگردان|translate|meaning of|in persian|in english)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        val activePersona = forcePersona ?: if (isTranslationReq) Persona.TUTOR else getPersona()

        val isCodeReq = activePersona == Persona.CODER ||
            Regex("(کد|برنامه|اسکریپت|پایتون|جاوا|کاتلین|code|program|script|python|java|kotlin|html|css|js|sql|bug|error|function|algorithm)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

        val sysPrompt = buildString {
            append(activePersona.instruction)
            if (isFa) {
                append("\nزبان پاسخ: حتماً به زبان فارسی کاملاً روان، طبیعی، خوش‌خوان و بدون غلط دستوری یا املایی پاسخ بده.")
                if (isTranslationReq) {
                    append("\nاگر کاربر درخواست ترجمه دارد، مفهوم و لحن جمله را به فارسیِ طبیعی و بومی برگردان و از ترجمه خشک و کلمه‌به‌کلمه پرهیز کن.")
                }
            } else {
                append("\nRespond strictly in natural, fluent English.")
            }
            if (extraMarketContext.isNotEmpty()) {
                append("\n\n").append(extraMarketContext)
                append("\nUse this live market data to perform deep technical analysis, evaluate RSI, support/resistance, and entry/exit strategy.")
            }
            if (image != null && (activePersona == Persona.TRADER || Regex("(چارت|نمودار|کندل|ترید|chart|candle|trade|trading|support|resistance)", RegexOption.IGNORE_CASE).containsMatchIn(text))) {
                append("\nThis image is a trading chart. Analyze trend, candlestick patterns, support/resistance zones, entry point, Stop Loss (SL), and Take Profit (TP).")
            }
            append(memory.asPromptContext())
        }

        val modelsToTry = when {
            image != null -> visionModels
            tier == SubscriptionManager.Tier.FREE -> standardModels
            else -> frontierModels // Pro & Alpha get frontier 235B / DeepSeek-R1 reasoning models!
        }

        var lastErr = ""
        for (m in modelsToTry) {
            try {
                val rawAnswer = callGateway(m, text, image, sysPrompt, includeHistory = true)
                    // Strip <think>...</think> reasoning tags if DeepSeek-R1 outputs them
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .trim()

                val finalAnswer = if (isCodeReq || activePersona == Persona.TRADER || extraMarketContext.isNotEmpty()) {
                    rawAnswer
                } else {
                    rawAnswer.replace(Regex("[*#`_]"), "").trim()
                }

                if (finalAnswer.isNotEmpty()) {
                    subs.recordUsage(SubscriptionManager.Feature.CHAT)
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
        val errMsg = if (isFa) {
            "ارتباط با سرور هوش مصنوعی وِکسا برقرار نشد. لطفاً اینترنت خود را بررسی کنید. ($lastErr)"
        } else {
            "Unable to reach Vexa AI servers. Please check your internet connection. ($lastErr)"
        }
        return AiResult(errMsg)
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

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)

        val conn = URL(getGatewayUrl()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 14000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val choices = JSONObject(resp).optJSONArray("choices") ?: throw IOException("Empty choices")
            if (choices.length() == 0) throw IOException("No choice")
            val msgObj = choices.getJSONObject(0).getJSONObject("message")
            return msgObj.optString("content", "")
        } finally {
            conn.disconnect()
        }
    }
}
