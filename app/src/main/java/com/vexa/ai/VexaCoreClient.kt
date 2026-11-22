package com.vexa.ai

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
    private val memory: MemoryStore
) {

    companion object {
        const val DEFAULT_GATEWAY_URL = "https://wolf-ai.amirmahdi-akhgari1390.workers.dev"

        fun isPersianText(text: String): Boolean {
            return Regex("[\u0600-\u06FF]").containsMatchIn(text)
        }
    }

    private val textModels = listOf(
        "openrouter/free",
        "qwen/qwen3.8-27b:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "google/gemma-3-27b-it:free"
    )

    private val visionModels = listOf(
        "nvidia/nemotron-3.5-super-vl:free",
        "google/gemma-3-27b-it:free",
        "openrouter/free"
    )

    private val maxHistory = 24
    private val history = ArrayList<Pair<String, String>>()

    enum class Persona(val titleEn: String, val titleFa: String, val instruction: String) {
        ASSISTANT(
            "Vexa Global Assistant",
            "وِکسا کلاسیک (هوشمند و همه‌کاره)",
            "You are Vexa AI, an ultra-intelligent, fast, and polite global voice and action assistant. " +
            "Always detect the user's language (English, Persian, etc.) and reply naturally in that exact same language."
        ),
        TRADER(
            "Quantitative Market Analyst",
            "تحلیلگر ارشد ترید و بازارهای مالی",
            "You are Vexa AI in Quantitative Market & Trading Analyst mode (Crypto, Forex, Gold, Stocks). " +
            "Analyze price action, key support/resistance levels, RSI, market trend, and risk management. " +
            "Provide potential entry zones, Stop Loss (SL), and Take Profit (TP) targets in the user's language."
        ),
        CODER(
            "Principal Software Architect",
            "مهندس ارشد برنامه‌نویسی",
            "You are Vexa AI in Principal Software Engineer mode. Write clean, complete, production-ready code " +
            "with concise technical explanations in the user's language."
        ),
        TUTOR(
            "Global Tutor & Translator",
            "معلم و مترجم حرفه‌ای",
            "You are Vexa AI in Multilingual Tutor & Translator mode. Explain complex concepts clearly and translate accurately."
        ),
        CREATIVE(
            "Creative Director & Writer",
            "ایده‌پرداز و نویسنده خلاق",
            "You are Vexa AI in Creative Director mode. Craft compelling stories, scripts, marketing copy, and innovative ideas."
        )
    }

    data class AiResult(
        val text: String,
        val generatedImage: Bitmap? = null,
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
    fun generateImage(prompt: String, isFa: Boolean): AiResult {
        val englishPrompt = translatePromptForImage(prompt)
        try {
            val encoded = URLEncoder.encode(englishPrompt, "UTF-8")
            val seed = (System.currentTimeMillis() % 100000).toInt()
            val imgUrl = URL("https://image.pollinations.ai/prompt/$encoded?width=768&height=768&seed=$seed&nologo=true")
            val conn = imgUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.setRequestProperty("User-Agent", "VexaAI-Global/1.0")
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val caption = if (isFa) "تصویر شما آماده شد:\n«$prompt»" else "Your image is ready:\n\"$prompt\""
                    return AiResult(text = caption, generatedImage = bmp)
                }
            }
            conn.disconnect()
        } catch (_: Exception) { }

        val err = if (isFa) "ساخت تصویر با اختلال موقت مواجه شد؛ لطفاً اتصال اینترنت را بررسی کنید."
        else "Image generation encountered a temporary network issue. Please check your connection and try again."
        return AiResult(err)
    }

    private fun translatePromptForImage(prompt: String): String {
        if (!isPersianText(prompt)) return prompt
        return try {
            val sys = "Translate the user's image description into a detailed, high-quality English image generation prompt. Return ONLY the English prompt, nothing else."
            val res = callGateway("openrouter/free", prompt, null, sys, includeHistory = false)
            res.trim().ifBlank { prompt }
        } catch (_: Exception) {
            prompt
        }
    }

    @Synchronized
    fun generateRawFileContent(ext: String, topic: String): String? {
        val sys = "You are a file content generator. Output ONLY the raw, clean, complete content for a .$ext file about the user's request. " +
            "Do NOT wrap in markdown code fences (no ```). Do NOT add conversational intro or outro. " +
            "If ext is csv, output valid comma-separated rows. If ext is py/kt/html/js/json, output valid executable code. If ext is pdf/txt/md, write a well-structured document in the user's language."
        for (m in textModels) {
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
        extraMarketContext: String = ""
    ): AiResult {
        val isFa = isPersianText(text)
        val activePersona = forcePersona ?: getPersona()
        val isCodeReq = activePersona == Persona.CODER ||
            Regex("(کد|برنامه|اسکریپت|پایتون|جاوا|کاتلین|code|program|script|python|java|kotlin|html|css|js|sql|bug|error|function|algorithm)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

        val sysPrompt = buildString {
            append(activePersona.instruction)
            append(if (isFa) "\nRespond strictly in natural, fluent Persian." else "\nRespond strictly in natural, fluent English.")
            if (extraMarketContext.isNotEmpty()) {
                append("\n\n").append(extraMarketContext)
                append("\nUse this live market data to perform technical analysis, evaluate RSI, support/resistance, and entry/exit strategy.")
            }
            if (image != null && (activePersona == Persona.TRADER || Regex("(چارت|نمودار|کندل|ترید|chart|candle|trade|trading|support|resistance)", RegexOption.IGNORE_CASE).containsMatchIn(text))) {
                append("\nThis image is a trading chart. Analyze the overall trend, candlestick patterns, key support/resistance zones, entry point, Stop Loss (SL), and Take Profit (TP).")
            }
            append(memory.asPromptContext())
        }

        val modelsToTry = if (image != null) visionModels else textModels
        var lastErr = ""
        for (m in modelsToTry) {
            try {
                val rawAnswer = callGateway(m, text, image, sysPrompt, includeHistory = true)
                val finalAnswer = if (isCodeReq || activePersona == Persona.TRADER || extraMarketContext.isNotEmpty()) {
                    rawAnswer.trim()
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
            conn.connectTimeout = 12000
            conn.readTimeout = 35000
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
