package com.vexa.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * موتور صوتی جهانی Vexa AI v8 (پشتیبانی از تمام زبان‌های دنیا):
 * - تشخیص خودکار زبان متن (فارسی، انگلیسی، عربی، ترکی، فرانسوی، آلمانی، اسپانیایی، روسی، چینی، ژاپنی، کره‌ای، هندی و...)
 * - پخش صدای طبیعی از طریق درگاه ابری بدون نیاز به VPN + پشتیبان موتور صوتی آفلاین گوشی
 */
class VexaVoiceEngine(
    private val ctx: Context,
    private val onStateChange: (isSpeaking: Boolean) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var cancelled = false

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setSpeechRate(1.02f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        handler.post { onStateChange(true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        handler.post { onStateChange(false) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { onStateChange(false) }
                    }
                })
            }
        }
    }

    /**
     * تشخیص خودکار کد زبان (ISO-639-1) برای تمام زبان‌های دنیا
     */
    fun detectLanguageCode(text: String, preferredLangTag: String = "fa-IR"): String {
        // Persian-specific characters (پ چ ژ گ ک ی)
        if (Regex("[پچژگکی]").containsMatchIn(text)) return "fa"
        // Arabic script fallback (check preferred language if fa vs ar)
        if (Regex("[\\u0600-\\u06FF]").containsMatchIn(text)) {
            return if (preferredLangTag.startsWith("ar")) "ar" else "fa"
        }
        // Cyrillic (Russian / Ukrainian)
        if (Regex("[\\u0400-\\u04FF]").containsMatchIn(text)) return "ru"
        // Chinese / Japanese / Korean
        if (Regex("[\\u4E00-\\u9FFF]").containsMatchIn(text)) return "zh-CN"
        if (Regex("[\\u3040-\\u30FF]").containsMatchIn(text)) return "ja"
        if (Regex("[\\uAC00-\\uD7AF]").containsMatchIn(text)) return "ko"
        // Hindi / Devanagari
        if (Regex("[\\u0900-\\u097F]").containsMatchIn(text)) return "hi"
        // Turkish-specific letters (ğ, ı, ş, İ)
        if (Regex("[ğĞıİşŞ]").containsMatchIn(text)) return "tr"
        // German-specific (ß, ä, ö, ü)
        if (Regex("[ßäöüÄÖÜ]").containsMatchIn(text)) return "de"
        // French-specific accents (é, è, ê, à, ç, ô, û)
        if (Regex("[éèêëàâçîïôûùÉÈÊÀÇ]").containsMatchIn(text)) return "fr"
        // Spanish-specific (ñ, ¿, ¡, á, í, ó, ú)
        if (Regex("[ñÑ¿¡áíóúÁÍÓÚ]").containsMatchIn(text)) return "es"

        // If user explicitly selected a language in UI (e.g., fr-FR, de-DE, es-ES, tr-TR, ar-SA), respect it for Latin text when appropriate
        val prefPrefix = preferredLangTag.substringBefore("-").lowercase(Locale.ROOT)
        if (prefPrefix in listOf("fr", "de", "es", "tr", "it", "pt", "nl")) {
            return prefPrefix
        }
        return "en"
    }

    fun cleanForSpeech(raw: String, langCode: String): String {
        val codeNotice = if (langCode == "fa") " کد برنامه روی صفحه نمایش داده شد. " else " Code block is displayed on screen. "
        return raw
            .replace(Regex("```[\\s\\S]*?```"), codeNotice)
            .replace(Regex("[*#`_~•👑🔒⚠️🎬📸🧠📱💬✨🌐🎨]"), " ")
            .replace("Vexa AI", if (langCode == "fa") "وِکسا" else "Vexa AI")
            .replace("PDF", if (langCode == "fa") "پی دی اف" else "PDF")
            .replace("MP4", if (langCode == "fa") "ام پی فور" else "MP4")
            .replace("RSI", if (langCode == "fa") "آر اس آی" else "R S I")
            .replace(Regex("https?://\\S+"), if (langCode == "fa") " لینک " else " link ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun speak(rawText: String, preferredLangTag: String = "fa-IR") {
        val langCode = detectLanguageCode(rawText, preferredLangTag)
        val text = cleanForSpeech(rawText, langCode)
        if (text.isBlank()) {
            onStateChange(false)
            return
        }
        cancelled = false
        stopMediaPlayerOnly()

        // First try local device TTS for fast offline playback if supported
        if (ttsReady && tts != null) {
            val locale = Locale.forLanguageTag(
                when (langCode) {
                    "fa" -> "fa-IR"
                    "ar" -> "ar-SA"
                    "tr" -> "tr-TR"
                    "fr" -> "fr-FR"
                    "de" -> "de-DE"
                    "es" -> "es-ES"
                    "ru" -> "ru-RU"
                    "zh-CN" -> "zh-CN"
                    "ja" -> "ja-JP"
                    "ko" -> "ko-KR"
                    "hi" -> "hi-IN"
                    else -> "en-US"
                }
            )
            val res = tts?.setLanguage(locale)
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                onStateChange(true)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vexa_tts")
                return
            }
        }

        // Otherwise stream natural voice for ANY world language via proxy / cloud TTS
        speakViaWorldCloudTts(text, langCode)
    }

    private fun speakViaWorldCloudTts(fullText: String, langCode: String) {
        onStateChange(true)
        val chunks = splitIntoChunks(fullText, 180)
        io.execute {
            try {
                for (chunk in chunks) {
                    if (cancelled) break
                    val audioFile = fetchWorldTtsAudio(chunk, langCode)
                    if (audioFile != null) {
                        if (cancelled) break
                        playAudioBlocking(audioFile)
                    }
                }
            } catch (_: Exception) {
            } finally {
                handler.post { onStateChange(false) }
            }
        }
    }

    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!؟،؛。！？\\n])\\s+"))
        val out = mutableListOf<String>()
        var buf = StringBuilder()
        for (s in sentences) {
            if (s.isBlank()) continue
            if (buf.length + s.length + 1 <= maxLen) {
                if (buf.isNotEmpty()) buf.append(" ")
                buf.append(s)
            } else {
                if (buf.isNotEmpty()) {
                    out.add(buf.toString())
                    buf = StringBuilder()
                }
                if (s.length <= maxLen) buf.append(s) else out.add(s.take(maxLen))
            }
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out
    }

    private fun fetchWorldTtsAudio(snippet: String, langCode: String): File? {
        val encoded = URLEncoder.encode(snippet, "UTF-8")
        val baseGateway = VexaCoreClient.DEFAULT_GATEWAY_URL.trimEnd('/')
        val endpoints = listOf(
            "$baseGateway/tts?lang=$langCode&text=$encoded",
            "https://translate.googleapis.com/translate_tts?ie=UTF-8&tl=$langCode&client=gtx&q=$encoded",
            "https://translate.google.com/translate_tts?ie=UTF-8&tl=$langCode&client=tw-ob&q=$encoded"
        )

        for (ep in endpoints) {
            try {
                val conn = URL(ep).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VexaAI/8.0")
                if (conn.responseCode == 200) {
                    val tmp = File(ctx.cacheDir, "vexa_tts_${System.nanoTime()}.mp3")
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    }
                    conn.disconnect()
                    if (tmp.exists() && tmp.length() > 300L) {
                        return tmp
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) { }
        }
        return null
    }

    private fun playAudioBlocking(file: File) {
        val lock = Object()
        var done = false
        handler.post {
            if (cancelled) {
                synchronized(lock) { done = true; lock.notifyAll() }
                return@post
            }
            try {
                stopMediaPlayerOnly()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        file.delete()
                        synchronized(lock) { done = true; lock.notifyAll() }
                    }
                    setOnErrorListener { _, _, _ ->
                        file.delete()
                        synchronized(lock) { done = true; lock.notifyAll() }
                        true
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                file.delete()
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        }
        synchronized(lock) {
            while (!done && !cancelled) {
                try { lock.wait(12000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun stopMediaPlayerOnly() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) { }
        mediaPlayer = null
    }

    fun stop() {
        cancelled = true
        tts?.stop()
        stopMediaPlayerOnly()
        onStateChange(false)
    }

    fun shutdown() {
        cancelled = true
        tts?.shutdown()
        stopMediaPlayerOnly()
        io.shutdownNow()
    }
}
