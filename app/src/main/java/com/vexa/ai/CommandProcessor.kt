package com.vexa.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CommandProcessor(
    private val ctx: Context,
    private val phone: PhoneActions,
    private val vexaCore: VexaCoreClient,
    private val memory: MemoryStore
) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class ProcessResponse(
        val text: String,
        val generatedImage: Bitmap? = null,
        val generatedFile: FileGenerator.GeneratedFile? = null,
        val speakText: String? = null
    )

    companion object {
        val WAKE_WORDS = listOf("vexa", "vixa", "alexa", "وِکسا", "وکسا", "ویکسا", "jarvis", "جارویس")
    }

    fun norm(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'ي' -> 'ی'
                    'ك' -> 'ک'
                    '\u200c' -> ' '
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                }
            )
        }
        return sb.toString().replace(Regex("[؟?!،؛,]"), " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
    }

    fun stripWake(cmd: String): Pair<String, Boolean> {
        var cleaned = cmd
            .replace(Regex("^(hey|hi|ok|okay|سلام|هی)\s+(vexa|vixa|وِکسا|وکسا|ویکسا)\b"), "")
            .trim()
        val tokens = cleaned.split(" ").filter { it.isNotBlank() }
        val kept = tokens.filter { it !in WAKE_WORDS }
        val heard = (cleaned != cmd) || (kept.size != tokens.size)
        return Pair(kept.joinToString(" ").trim(), heard)
    }

    private fun has(cmd: String, vararg keys: String) = keys.any { cmd.contains(norm(it)) }
    private fun hasW(cmd: String, vararg keys: String): Boolean {
        val t = cmd.split(" ")
        return keys.any { norm(it) in t }
    }

    fun process(raw: String, image: Bitmap? = null, onReply: (ProcessResponse) -> Unit) {
        val isFa = VexaCoreClient.isPersianText(raw)
        val cmd = norm(raw)

        if (image != null) {
            io.execute {
                val res = vexaCore.ask(raw, image = image)
                val shortSpeak = if (res.isCodeOrTechnical) {
                    if (isFa) "تحلیل تصویر انجام شد." else "Image analysis complete."
                } else res.text
                main.post { onReply(ProcessResponse(res.text, speakText = shortSpeak)) }
            }
            return
        }

        if (cmd.isBlank()) {
            onReply(ProcessResponse(""))
            return
        }

        // 1. AI Image Generation (English & Persian)
        val imgGenRegex = Regex("(تولید عکس|ساخت عکس|عکس بساز|تصویر بساز|تصویر تولید کن|نقاشی بکش|generate image|create image|draw an image|draw a picture|generate a picture)")
        if (imgGenRegex.containsMatchIn(cmd)) {
            val promptDesc = raw.replace(
                Regex("(please|a|an|the|لطفاً|لطفا|یک|یه|تولید عکس از|تولید عکس|ساخت عکس از|ساخت عکس|عکس بساز از|عکس بساز|تصویر بساز از|تصویر بساز|تصویر تولید کن از|تصویر تولید کن|نقاشی بکش از|نقاشی بکش|generate image of|generate image|create image of|create image|draw an image of|draw a picture of)", RegexOption.IGNORE_CASE),
                " "
            ).replace(Regex("\\s+"), " ").trim()

            if (promptDesc.isEmpty()) {
                val msg = if (isFa) "تصویر چه چیزی را بسازم؟ مثلاً: عکس بساز از یک شهر آینده‌نگر در شب"
                else "What would you like me to create? For example: Generate an image of a futuristic neon city at night."
                onReply(ProcessResponse(msg))
                return
            }
            io.execute {
                val aiRes = vexaCore.generateImage(promptDesc, isFa)
                main.post {
                    onReply(
                        ProcessResponse(
                            text = aiRes.text,
                            generatedImage = aiRes.generatedImage,
                            speakText = if (aiRes.generatedImage != null) {
                                if (isFa) "تصویر شما آماده شد." else "Your image is ready."
                            } else aiRes.text
                        )
                    )
                }
            }
            return
        }

        // 2. Real File Generation (PDF, Python, CSV, HTML, Kotlin, JS, JSON, MD, TXT)
        val wantsFile = (has(cmd, "فایل") && has(cmd, "بساز", "تولید کن", "درست کن", "ایجاد کن", "بده", "خروجی")) ||
            (has(cmd, "file") && has(cmd, "create", "generate", "make", "export", "build"))
        if (wantsFile) {
            val ext = detectRequestedExtension(cmd)
            if (ext != null) {
                io.execute {
                    val content = vexaCore.generateRawFileContent(ext, raw)
                    if (content.isNullOrBlank()) {
                        val err = if (isFa) "تولید محتوای فایل ناموفق بود؛ اینترنت را بررسی کنید."
                        else "Could not generate file content. Please check your connection."
                        main.post { onReply(ProcessResponse(err)) }
                    } else {
                        val gf = FileGenerator.createFile(ctx, ext, "Vexa_${ext.uppercase()}", content)
                        val msg = if (isFa) {
                            "فایل ${gf.displayName} با موفقیت ساخته شد.
می‌توانید همین الان آن را باز کنید یا به اشتراک بگذارید."
                        } else {
                            "File ${gf.displayName} has been generated.
Tap below to open or share it."
                        }
                        main.post {
                            onReply(
                                ProcessResponse(
                                    text = msg,
                                    generatedFile = gf,
                                    speakText = if (isFa) "فایل ${ext.uppercase()} شما ساخته شد." else "Your ${ext.uppercase()} file is ready."
                                )
                            )
                        }
                    }
                }
                return
            }
        }

        // 3. Trading Position Size & Risk Calculator
        if (has(cmd, "حجم معامله", "مدیریت ریسک", "پوزیشن سایز", "position size", "risk management")) {
            val nums = Regex("\\d+(?:\\.\\d+)?").findAll(cmd).map { it.value.toDouble() }.toList()
            if (nums.size >= 3) {
                val calcResult = TradingEngine.calculatePositionSize(nums[0], nums[1], nums[2], isFa)
                onReply(ProcessResponse(calcResult, speakText = if (isFa) "محاسبه مدیریت ریسک انجام شد." else "Risk calculation complete."))
                return
            } else {
                val hint = if (isFa) "سه عدد را به ترتیب بگویید: سرمایه کل، درصد ریسک و درصد حد ضرر.
مثال: حجم معامله با سرمایه 1000 ریسک 2 درصد و استاپ 5 درصد"
                else "Please provide 3 numbers: account balance, risk %, and stop-loss %.
Example: Position size with balance 1000 risk 2 stop 5"
                onReply(ProcessResponse(hint))
                return
            }
        }

        // 4. Local Device Commands
        val local = handleLocal(cmd, isFa)
        if (local != null) {
            onReply(ProcessResponse(local))
            return
        }

        // 5. Live Market & Technical Trading Analysis
        val isTradeQuery = has(cmd, "تحلیل", "ترید", "سیگنال", "حمایت", "مقاومت", "پرایس اکشن", "rsi", "کریپتو", "فارکس", "analyze", "analysis", "trade", "trading", "signal", "support", "resistance", "crypto", "forex")
        val detectedCoin = TradingEngine.detectCoinId(cmd)
        if (isTradeQuery || detectedCoin != null) {
            io.execute {
                val snap = detectedCoin?.let { TradingEngine.fetchMarketData(it) }
                val marketCtx = if (snap != null) TradingEngine.buildTechnicalContext(snap) else ""
                val res = vexaCore.ask(
                    text = raw,
                    image = null,
                    forcePersona = if (isTradeQuery) VexaCoreClient.Persona.TRADER else null,
                    extraMarketContext = marketCtx
                )
                val shortSpeak = if (res.text.length > 260) {
                    if (isFa) "تحلیل بازار انجام شد. جزئیات کامل روی صفحه نمایش داده شد."
                    else "Market analysis complete. Full technical breakdown is on screen."
                } else res.text
                main.post { onReply(ProcessResponse(res.text, speakText = shortSpeak)) }
            }
            return
        }

        // 6. General AI, Coding, Translation & Conversation
        io.execute {
            val res = vexaCore.ask(raw, image = null)
            val shortSpeak = if (res.isCodeOrTechnical || res.text.length > 320) {
                if (isFa) "پاسخ و کدهای درخواستی شما آماده شد." else "Your response is ready on screen."
            } else res.text
            main.post { onReply(ProcessResponse(res.text, speakText = shortSpeak)) }
        }
    }

    private fun detectRequestedExtension(cmd: String): String? {
        return when {
            has(cmd, "pdf", "پی دی اف", "پی‌دی‌اف") -> "pdf"
            has(cmd, "python", "پایتون", "py") -> "py"
            has(cmd, "csv", "اکسل", "جدول", "spreadsheet") -> "csv"
            has(cmd, "html", "سایت", "صفحه وب", "webpage") -> "html"
            has(cmd, "kotlin", "کاتلین", "kt") -> "kt"
            has(cmd, "javascript", "جاوااسکریپت", "جاوا اسکریپت", "js") -> "js"
            has(cmd, "json", "جیسون") -> "json"
            has(cmd, "markdown", "مارک داون", "md") -> "md"
            has(cmd, "txt", "متنی", "متن", "text") -> "txt"
            else -> null
        }
    }

    private fun handleLocal(cmd: String, isFa: Boolean): String? {

        // Permanent Long-Term Memory
        if (has(cmd, "حافظه بلندمدت را پاک کن", "حافظه رو پاک کن", "همه چیز را فراموش کن", "clear memory", "forget everything")) {
            vexaCore.clear()
            return memory.clearAll(isFa)
        }
        if (has(cmd, "چی یادته", "چی به خاطر داری", "حافظه ات چیه", "what do you remember", "show memory")) {
            val facts = memory.getAll()
            return if (facts.isEmpty()) {
                if (isFa) "هنوز مورد خاصی در حافظه دائمی ثبت نشده است." else "Nothing is stored in your permanent memory yet."
            } else {
                val joined = facts.joinToString(if (isFa) " ، " else ", ")
                if (isFa) "این موارد را به خاطر دارم: $joined" else "Here is what I remember: $joined"
            }
        }
        val remKey = listOf("به خاطر بسپار که", "به خاطر بسپار", "یادت باشه که", "یادت باشه", "حفظ کن که", "remember that", "remember").firstOrNull { has(cmd, it) }
        if (remKey != null) {
            val fact = cmd.substringAfter(norm(remKey)).trim()
            return memory.remember(fact, isFa)
        }

        // Clear current chat history
        if (has(cmd, "گفتگو را فراموش کن", "گفتگو رو فراموش کن", "چت را پاک کن", "clear chat", "reset chat")) {
            vexaCore.clear()
            return if (isFa) "گفتگوی فعلی پاک شد." else "Current conversation cleared."
        }

        // WhatsApp
        if (has(cmd, "واتساپ", "واتس اپ", "whatsapp") && has(cmd, "پیام", "بفرست", "بگو", "send", "message")) {
            val afterTo = if (cmd.contains(" به ")) cmd.substringAfter(" به ")
            else if (cmd.contains(" to ")) cmd.substringAfter(" to ")
            else cmd.substringAfter(" برای ")
            val parts = afterTo.split(Regex("(پیام بده|بگو|بفرست|که|saying|that|:)"), limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                return phone.sendWhatsApp(parts[0].trim(), parts[1].trim(), isFa)
            }
        }

        // SMS
        val smsKey = listOf("پیامک بده به", "پیامک بزن به", "اس ام اس بده به", "پیام بده به", "send sms to", "text").firstOrNull { has(cmd, it) }
        if (smsKey != null) {
            val rest = cmd.substringAfter(norm(smsKey)).trim()
            val tokens = rest.split(" ")
            if (tokens.isNotEmpty()) {
                val target = tokens[0]
                val msg = tokens.drop(1).joinToString(" ").removePrefix("که ").removePrefix("saying ").trim()
                return phone.sendSms(target, msg, isFa)
            }
        }

        // Navigation
        val navKey = listOf("مسیریابی به", "مسیریابی تا", "مسیر به", "مسیر تا", "برو به", "navigate to", "directions to").firstOrNull { has(cmd, it) }
        if (navKey != null) {
            val dest = cmd.substringAfter(norm(navKey)).trim()
            if (dest.isNotEmpty()) return phone.navigateTo(dest, isFa)
        }

        // YouTube
        if (has(cmd, "یوتیوب", "youtube") && has(cmd, "پخش", "بگرد", "جستجو", "سرچ", "play", "search")) {
            val q = cmd.replace(Regex("(در یوتیوب|توی یوتیوب|یوتیوب|پخش کن|جستجو کن|سرچ کن|پیدا کن|on youtube|youtube|play|search for|search)"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (q.isNotEmpty()) return phone.playYouTube(q, isFa)
        }

        // Utilities
        if (has(cmd, "کلیپ بورد", "کلیپ‌بورد", "چی کپی کردم", "متن کپی شده", "clipboard")) return phone.readClipboard(isFa)
        if (has(cmd, "شیر یا خط", "سکه بنداز", "flip a coin", "toss a coin")) return phone.flipCoin(isFa)
        if (has(cmd, "تاس بنداز", "یه تاس", "roll a dice", "roll dice")) return phone.rollDice(isFa)
        if (has(cmd, "رمز عبور بساز", "پسورد بساز", "رمز قوی", "generate password", "create password")) return phone.randomPassword(12, isFa)

        // Settings & Hardware
        if (has(cmd, "وای فای", "وایفای", "wifi", "wi-fi")) return phone.openWifiPanel(isFa)
        if (has(cmd, "بلوتوث", "bluetooth")) return phone.openBluetoothSettings(isFa)
        if (has(cmd, "نور صفحه", "روشنایی", "صفحه نمایش", "brightness", "display settings")) return phone.openDisplaySettings(isFa)
        if (has(cmd, "حالت ویبره", "گوشی را سایلنت", "گوشی رو سایلنت", "silent mode", "vibrate mode")) return phone.setRingerMode(true, isFa)
        if (has(cmd, "حالت عادی", "از سایلنت دربیار", "صدای زنگ را وصل کن", "normal mode", "unmute ringer")) return phone.setRingerMode(false, isFa)

        // Flashlight
        if (has(cmd, "چراغ قوه", "فلش", "flashlight", "torch")) {
            val off = has(cmd, "خاموش") || hasW(cmd, "off")
            val on = has(cmd, "روشن", "بزن") || hasW(cmd, "on")
            return phone.torch(if (off) false else if (on) true else null, isFa)
        }

        // Volume
        if (has(cmd, "صدا", "ولوم", "volume")) {
            if (has(cmd, "حداکثر", "آخر", "ماکزیمم", "max", "maximum")) return phone.volume(2, isFa)
            if (has(cmd, "بی صدا", "قطع", "mute", "سایلنت")) return phone.volume(0, isFa)
            if (has(cmd, "زیاد", "بالا", "بلند", "افزایش", "increase") || hasW(cmd, "up")) return phone.volume(1, isFa)
            if (has(cmd, "کم", "پایین", "آهسته", "کاهش", "decrease") || hasW(cmd, "down")) return phone.volume(-1, isFa)
        }

        // Battery
        if (has(cmd, "باتری", "شارژ", "battery")) return phone.battery(isFa)

        // Call
        val callKey = listOf("تماس بگیر با", "تماس بگیر", "زنگ بزن به", "زنگ بزن", "call").firstOrNull { has(cmd, it) }
        if (callKey != null) {
            val k = norm(callKey)
            val target = (cmd.substringAfter(k).ifBlank { cmd.substringBefore(k) })
                .replace(Regex("(^| )(با|به|شماره|لطفا|please)( |$)"), " ").trim()
            if (target.isEmpty()) return if (isFa) "با چه کسی تماس بگیرم؟" else "Who would you like me to call?"
            return phone.call(target, isFa)
        }

        // Alarm
        if (has(cmd, "آلارم", "الارم", "alarm", "بیدارم کن", "wake me up")) {
            val nums = Regex("\\d+").findAll(cmd).map { it.value.toInt() }.toList()
            var hour = nums.getOrNull(0) ?: Numbers.parse(cmd)?.toInt()
                ?: return if (isFa) "آلارم را برای چه ساعتی بگذارم؟" else "What time should I set the alarm for?"
            var minute = nums.getOrNull(1) ?: 0
            if (minute == 0 && has(cmd, "و نیم", "half past")) minute = 30
            if (hour < 12 && has(cmd, "عصر", "بعدازظهر", "بعد از ظهر", "شب") || (hour < 12 && hasW(cmd, "pm"))) hour += 12
            if (hour !in 0..23 || minute !in 0..59) return if (isFa) "این ساعت معتبر نیست." else "That time is not valid."
            return phone.alarm(hour, minute, isFa)
        }

        // Timer
        if (has(cmd, "تایمر", "زمان سنج", "timer")) {
            val n = Numbers.parse(cmd) ?: return if (isFa) "تایمر را برای چند دقیقه بگذارم؟" else "How long should I set the timer for?"
            val secs = when {
                has(cmd, "ثانیه", "second") -> n
                has(cmd, "ساعت", "hour") -> n * 3600
                else -> n * 60
            }
            return phone.timer(secs.toInt(), "", isFa)
        }

        // Notes
        if (has(cmd, "یادداشت", "note", "notes")) {
            if (has(cmd, "بخون", "بخوان", "read", "show", "list")) return phone.readNotes(isFa)
            val key = listOf("یادداشت کن", "یادداشت", "take a note", "save note", "note").first { has(cmd, it) }
            val k = norm(key)
            val content = cmd.substringAfter(k).ifBlank { cmd.substringBefore(k) }
                .replace(Regex("(^| )(را|رو|که|that)( |$)"), " ").trim()
            if (content.isEmpty()) return if (isFa) "چه چیزی را یادداشت کنم؟" else "What would you like me to note down?"
            return phone.saveNote(content, isFa)
        }

        // Date & Time
        if (has(cmd, "تاریخ", "امروز چندم", "چندمه", "what day", "today's date", "date")) return Jalali.todayText(isFa)
        if (has(cmd, "ساعت چنده", "ساعت چند", "ساعت الان", "what time", "current time") || cmd == "ساعت" || cmd == "time") {
            val now = Calendar.getInstance()
            val h = now.get(Calendar.HOUR_OF_DAY)
            val m = now.get(Calendar.MINUTE)
            return if (isFa) {
                if (m == 0) "ساعت $h است." else "ساعت $h و $m دقیقه است."
            } else {
                val fmt = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
                "The current time is $fmt."
            }
        }

        // Calculator
        var e = cmd
        listOf(
            "به علاوه" to "+", "بعلاوه" to "+", "plus" to "+",
            "منهای" to "-", "minus" to "-",
            "ضرب در" to "*", "ضربدر" to "*", "times" to "*", "×" to "*", " x " to "*",
            "تقسیم بر" to "/", "تقسیم" to "/", "divided by" to "/", "÷" to "/"
        ).forEach { (k, v) -> e = e.replace(k, " $v ") }
        e = e.replace(Regex("(حساب کن|محاسبه کن|چند میشه|چند می شه|چنده|برابر با|برابر|calculate|what is|=)"), " ").trim()
        if (Regex("[+\\-*/]").containsMatchIn(e) && e.matches(Regex("[\\d\\s+\\-*/().]+"))) {
            val v = Calc.eval(e)
            if (v == null || v.isNaN() || v.isInfinite()) {
                return if (isFa) "این عبارت قابل محاسبه نیست." else "That expression cannot be evaluated."
            }
            val formatted = Calc.format(v)
            return if (isFa) "جواب $formatted است." else "The answer is $formatted."
        }

        // Open App
        val openKey = listOf("باز کن", "بازکن", "اجرا کن", "اجرا", "open", "launch").firstOrNull { has(cmd, it) }
        if (openKey != null) {
            val filler = Regex("(^| )(برنامه|اپ|اپلیکیشن|را|رو|لطفا|app|the|please|و)( |$)")
            var name = cmd.replace(norm(openKey), " ")
            repeat(2) { name = name.replace(filler, " ") }
            name = name.trim()
            return if (name.isEmpty()) {
                if (isFa) "کدام برنامه را باز کنم؟" else "Which app would you like me to open?"
            } else phone.openApp(name, isFa)
        }

        // Greetings
        if (cmd.split(" ").size <= 6) {
            if (has(cmd, "ممنون", "مرسی", "thank")) return if (isFa) "خواهش می‌کنم." else "You're welcome!"
            if (has(cmd, "تو کی", "اسمت چیه", "کی هستی", "who are you", "your name")) {
                return if (isFa) "من وِکسا (Vexa AI) هستم؛ دستیار صوتی هوشمند، تحلیلگر بازار، برنامه‌نویس و تولیدکننده تصویر و فایل."
                else "I am Vexa AI, your global voice assistant, market analyst, software engineer, and media creator."
            }
            if (has(cmd, "حالت چطوره", "چطوری", "how are you")) {
                return if (isFa) "عالی هستم، تمام سیستم‌های وِکسا آماده هستند. چطور می‌توانم کمکتان کنم؟"
                else "I'm operating at peak performance. How can I assist you today?"
            }
            if (hasW(cmd, "سلام", "درود", "hello", "hi", "hey")) {
                return if (isFa) "سلام! من وِکسا هستم، در خدمتم." else "Hello! I'm Vexa AI. How can I help?"
            }
        }

        return null
    }
}
