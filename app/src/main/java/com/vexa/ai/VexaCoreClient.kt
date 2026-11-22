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

/**
 * هسته هوش مصنوعی Vexa AI نسخه ۷:
 * - پایداری حداکثری ارتباط بدون فیلترشکن با مکانیزم تلاش مجدد (Auto-Retry) و چرخش هوشمند مسیرها
 * - موتور تولید تصویر ۴ مسیره با ترجمه دقیق پرامپت فارسی به انگلیسی
 * - ساخت ویدیوهای MP4 کاملاً منطبق با پرامپت کاربر (حفظ دقیق سوژه اصلی در تمام صحنه‌ها)
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

    /**
     * ساخت ویدیوی واقعی MP4 کاملاً منطبق با پرامپت کاربر:
     * - هسته موضوع پرامپت در تمام صحنه‌ها ثابت می‌ماند تا خروجی دقیقاً مطابق خواسته کاربر باشد
     */
    @Synchronized
    fun generateVideo(ctx: Context, rawPrompt: String, isFa: Boolean): AiResult {
        val coreSubjectEn = translateToExactEnglishSubject(rawPrompt)
        if (coreSubjectEn.isBlank()) {
            val err = if (isFa) {
                "ارتباط با سرور برای ترجمه موضوع ویدیو برقرار نشد. لطفاً اینترنت را بررسی کنید."
            } else {
                "Could not reach server to process video prompt. Please check your connection."
            }
            return AiResult(err)
        }

        // 3 coherent camera angles of the EXACT same subject so the video strictly matches the prompt!
        val scenePrompts = listOf(
            "$coreSubjectEn, wide cinematic shot, natural lighting, photorealistic, 8k",
            "$coreSubjectEn, medium cinematic angle, dynamic motion, photorealistic, 8k",
            "$coreSubjectEn, dramatic close-up detail shot, cinematic lighting, 8k"
        )

        val bitmaps = mutableListOf<Bitmap>()
        val baseSeed = (System.currentTimeMillis() % 899999).toInt() + 1000
        for ((idx, sp) in scenePrompts.withIndex()) {
            val bmp = fetchSingleBitmapWithRetry(sp, seed = baseSeed + idx * 17)
            if (bmp != null) bitmaps.add(bmp)
        }

        if (bitmaps.isEmpty()) {
            val err = if (isFa) {
                "سرور تولید صحنه‌های ویدیو در این لحظه پاسخ نداد. لطفاً چند ثانیه بعد دوباره امتحان کنید."
            } else {
                "Video scene generator did not respond right now. Please try again in a few seconds."
            }
            return AiResult(err)
        }

        val videoFile = VideoGenerator.createCinematicMp4(ctx, bitmaps, rawPrompt)
        if (videoFile == null) {
            val err = if (isFa) "ساخت فایل ویدیویی MP4 روی گوشی با خطا مواجه شد." else "On-device MP4 encoding failed."
            return AiResult(err)
        }

        val durationSec = bitmaps.size * 3
        val msg = if (isFa) {
            "🎬 ویدیوی شما ($durationSec ثانیه HD MP4) دقیقاً مطابق دستور شما ساخته شد:\n«$rawPrompt»\nروی دکمه باز کردن بزنید تا پخش شود."
        } else {
            "🎬 Your AI Cinematic Video (${durationSec}s HD MP4) matching your prompt is ready:\n\"$rawPrompt\""
        }
        return AiResult(text = msg, generatedImage = bitmaps.first(), generatedFile = videoFile)
    }

    /**
     * تولید تصویر واقع‌گرایانه با ترجمه دقیق پرامپت و ۴ مسیر پشتیبان
     */
    @Synchronized
    fun generateImage(rawPrompt: String, isFa: Boolean): AiResult {
        val coreSubjectEn = translateToExactEnglishSubject(rawPrompt)
        if (coreSubjectEn.isBlank()) {
            val err = if (isFa) {
                "ارتباط با سرور برای پردازش متن تصویر برقرار نشد. لطفاً اینترنت را بررسی کنید."
            } else {
                "Could not connect to process image prompt. Please check your connection."
            }
            return AiResult(err)
        }

        val fullPrompt = "$coreSubjectEn, photorealistic, ultra detailed, natural lighting, 8k"
        val seed = (System.currentTimeMillis() % 899999).toInt() + 1000
        val bmp = fetchSingleBitmapWithRetry(fullPrompt, seed = seed)

        if (bmp != null) {
            val caption = if (isFa) {
                "تصویر شما آماده شد:\n«$rawPrompt»"
            } else {
                "Image generated:\n\"$rawPrompt\""
            }
            return AiResult(text = caption, generatedImage = bmp)
        }

        val err = if (isFa) {
            "سرور تصویرساز در این لحظه شلوغ است و پاسخ نداد؛ لطفاً یک بار دیگر تلاش کنید."
        } else {
            "Image server is busy right now. Please try again."
        }
        return AiResult(err)
    }

    /**
     * ترجمه دقیق سوژه فارسی به انگلیسی (بدون اضافه کردن اشیای بی‌ربط!)
     */
    private fun translateToExactEnglishSubject(userPrompt: String): String {
        val clean = userPrompt.trim()
        if (!isPersianText(clean)) return clean

        val sys = "Translate the user's Persian visual description into a faithful, accurate English image description. " +
            "Keep EVERY detail, object, action, color, and setting the user mentioned. Do NOT invent unrelated objects. Output ONLY the English description."

        for (model in listOf("google/gemma-3-27b-it:free", "openai")) {
            try {
                val res = callGateway(model, clean, null, sys, includeHistory = false)
                    .replace("\"", "")
                    .replace("\n", " ")
                    .trim()
                if (res.length >= 4 && !isPersianText(res)) {
                    return res
                }
            } catch (_: Exception) { }
        }
        return ""
    }

    private fun fetchSingleBitmapWithRetry(englishPrompt: String, seed: Int): Bitmap? {
        val encoded = URLEncoder.encode(englishPrompt, "UTF-8")
        val baseGateway = getGatewayUrl().trimEnd('/')

        val candidateUrls = listOf(
            "$baseGateway/image?prompt=$encoded&seed=$seed",
            "https://image.pollinations.ai/prompt/$encoded?width=768&height=768&seed=$seed&nologo=true&model=flux",
            "https://image.pollinations.ai/prompt/$encoded?width=768&height=768&seed=${seed + 5}&nologo=true&model=turbo",
            "https://image.pollinations.ai/prompt/$encoded?width=512&height=512&seed=${seed + 9}&nologo=true"
        )

        for (urlStr in candidateUrls) {
            for (attempt in 1..2) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 55000
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
                    try { Thread.sleep(600L) } catch (_: InterruptedException) { }
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) { }
                }
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
                append("\nThe user's name is $userDisplayName. Address them warmly and naturally like a real human friend/assistant.")
            }
            append("\nIMPORTANT: Detect the exact language of the user's message (Persian, English, Arabic, Turkish, French, German, Spanish, Russian, Chinese, Japanese, Korean, Hindi, or ANY world language) and reply fluently and naturally in that exact same language like an intelligent, warm human.")
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

            // Retry up to 2 times per endpoint to eliminate transient network drops
            for (attempt in 1..2) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(ep).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 55000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Connection", "close")
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VexaAI/7.0")
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
                    try { Thread.sleep(500L) } catch (_: InterruptedException) { }
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) { }
                }
            }
        }
        throw lastEx
    }
}
