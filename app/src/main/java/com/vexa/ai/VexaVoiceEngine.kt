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
 * موتور صدای فارسی بدون فیلترشکن Vexa AI v5:
 * - اولویت ۱: استفاده از درگاه اختصاصی کلودفلر (/tts?text=...) تا روی اینترنت ایران بدون VPN با صدای فارسی صحبت کند
 * - اولویت ۲: موتور آفلاین گوشی (در صورت پشتیبانی از فارسی)
 * - برای انگلیسی: موتور بومی انگلیسی
 */
class VexaVoiceEngine(
    private val ctx: Context,
    private val onStateChange: (isSpeaking: Boolean) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var localPersianAvailable = false
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var cancelled = false

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val faRes = tts?.setLanguage(Locale.forLanguageTag("fa-IR"))
                localPersianAvailable = (faRes != TextToSpeech.LANG_MISSING_DATA && faRes != TextToSpeech.LANG_NOT_SUPPORTED)
                tts?.setLanguage(Locale.US)
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

    fun cleanForSpeech(raw: String, isFa: Boolean): String {
        val codeNotice = if (isFa) " کد برنامه روی صفحه نمایش داده شد. " else " Code block is displayed on screen. "
        return raw
            .replace(Regex("```[\\s\\S]*?```"), codeNotice)
            .replace(Regex("[*#`_~•👑🔒⚠️🎬📸🧠📱💬✨]"), " ")
            .replace("Vexa AI", if (isFa) "وِکسا" else "Vexa AI")
            .replace("PDF", if (isFa) "پی دی اف" else "PDF")
            .replace("MP4", if (isFa) "ام پی فور" else "MP4")
            .replace("RSI", if (isFa) "آر اس آی" else "R S I")
            .replace(Regex("https?://\\S+"), if (isFa) " لینک " else " link ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun speak(rawText: String) {
        val isFa = VexaCoreClient.isPersianText(rawText)
        val text = cleanForSpeech(rawText, isFa)
        if (text.isBlank()) {
            onStateChange(false)
            return
        }
        cancelled = false
        stopMediaPlayerOnly()

        if (!isFa) {
            if (ttsReady && tts != null) {
                tts?.setLanguage(Locale.US)
                onStateChange(true)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vexa_en")
            } else {
                onStateChange(false)
            }
            return
        }

        // برای فارسی: اول از سرور واسط کلودفلر بدون نیاز به VPN پخش می‌کنیم؛ اگر آفلاین بود از موتور گوشی
        speakViaProxyPersian(text)
    }

    private fun speakViaProxyPersian(fullText: String) {
        onStateChange(true)
        val chunks = splitIntoChunks(fullText, 180)
        io.execute {
            var playedAny = false
            try {
                for (chunk in chunks) {
                    if (cancelled) break
                    val audioFile = fetchProxyPersianAudio(chunk)
                    if (audioFile != null) {
                        playedAny = true
                        if (cancelled) break
                        playAudioBlocking(audioFile)
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (!playedAny && !cancelled && ttsReady && localPersianAvailable && tts != null) {
                    handler.post {
                        tts?.setLanguage(Locale.forLanguageTag("fa-IR"))
                        tts?.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "vexa_fa_local")
                    }
                } else {
                    handler.post { onStateChange(false) }
                }
            }
        }
    }

    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!؟،؛\\n])\\s+"))
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

    private fun fetchProxyPersianAudio(snippet: String): File? {
        val encoded = URLEncoder.encode(snippet, "UTF-8")
        val baseGateway = VexaCoreClient.DEFAULT_GATEWAY_URL.trimEnd('/')
        val endpoints = listOf(
            "$baseGateway/tts?text=$encoded",
            "https://translate.googleapis.com/translate_tts?ie=UTF-8&tl=fa&client=gtx&q=$encoded"
        )

        for (ep in endpoints) {
            try {
                val conn = URL(ep).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "VexaAI-Android/5.0")
                if (conn.responseCode == 200) {
                    val tmp = File(ctx.cacheDir, "vexa_fa_${System.nanoTime()}.mp3")
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    }
                    conn.disconnect()
                    if (tmp.exists() && tmp.length() > 350L) {
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
