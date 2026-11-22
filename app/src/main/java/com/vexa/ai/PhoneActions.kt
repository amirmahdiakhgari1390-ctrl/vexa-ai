package com.vexa.ai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class PhoneActions(private val ctx: Context) {

    private var torchOn = false

    private data class AppInfo(val label: String, val pkg: String)

    companion object {
        private val ALIASES = mapOf(
            "واتساپ" to "whatsapp", "واتس اپ" to "whatsapp", "تلگرام" to "telegram",
            "اینستاگرام" to "instagram", "اینستا" to "instagram", "کروم" to "chrome",
            "یوتیوب" to "youtube", "دوربین" to "camera", "تنظیمات" to "settings",
            "نقشه" to "maps", "گوگل مپ" to "maps", "نشان" to "neshan", "بلد" to "balad",
            "گالری" to "photos", "مخاطبین" to "contacts",
            "ماشین حساب" to "calculator", "ساعت" to "clock", "جیمیل" to "gmail",
            "گوگل" to "google", "اسپاتیفای" to "spotify", "بله" to "bale", "ایتا" to "eitaa",
            "روبیکا" to "rubika", "دیوار" to "divar", "اسنپ" to "snapp", "تپسی" to "tapsi",
            "پلی استور" to "vending", "فایل" to "files", "شماره گیر" to "dialer",
            "تلفن" to "dialer", "پیامک" to "messaging", "پیام" to "messaging"
        )
    }

    private fun fold(s: String) =
        s.lowercase(Locale.ROOT).replace('\u200c', ' ').replace('ي', 'ی').replace('ك', 'ک').trim()

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun installedApps(): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map {
            AppInfo(fold(it.loadLabel(pm).toString()), it.activityInfo.packageName)
        }
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
        return 1.0 - dp[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun findApp(name: String): AppInfo? {
        val n = fold(name)
        val apps = installedApps()
        apps.firstOrNull { it.label == n }?.let { return it }
        val target = ALIASES[n] ?: n
        apps.firstOrNull { it.label.contains(target) || it.pkg.lowercase(Locale.ROOT).contains(target) }?.let { return it }
        apps.firstOrNull { it.label.contains(n) }?.let { return it }
        return apps.map { it to similarity(it.label, n) }
            .filter { it.second >= 0.7 }
            .maxByOrNull { it.second }?.first
    }

    fun openApp(name: String, isFa: Boolean): String {
        val app = findApp(name) ?: return if (isFa) "برنامه‌ای به نام $name پیدا نکردم." else "Could not find an app named $name."
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return if (isFa) "نتوانستم ${app.label} را باز کنم." else "Unable to launch ${app.label}."
        return if (launch(intent)) {
            if (isFa) "در حال باز کردن ${app.label}" else "Opening ${app.label}."
        } else {
            if (isFa) "نتوانستم ${app.label} را باز کنم." else "Unable to launch ${app.label}."
        }
    }

    fun torch(on: Boolean?, isFa: Boolean): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return if (isFa) "این گوشی چراغ قوه ندارد." else "Flashlight is not available on this device."
            torchOn = on ?: !torchOn
            cm.setTorchMode(id, torchOn)
            if (torchOn) {
                if (isFa) "چراغ قوه روشن شد." else "Flashlight turned on."
            } else {
                if (isFa) "چراغ قوه خاموش شد." else "Flashlight turned off."
            }
        } catch (e: Exception) {
            if (isFa) "نتوانستم چراغ قوه را کنترل کنم." else "Failed to toggle flashlight."
        }
    }

    fun volume(direction: Int, isFa: Boolean): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (direction) {
            1 -> {
                repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                if (isFa) "صدا زیاد شد." else "Volume increased."
            }
            -1 -> {
                repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                if (isFa) "صدا کم شد." else "Volume decreased."
            }
            2 -> {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                if (isFa) "صدا روی حداکثر تنظیم شد." else "Volume set to maximum."
            }
            else -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                if (isFa) "صدا قطع شد." else "Audio muted."
            }
        }
    }

    fun setRingerMode(silent: Boolean, isFa: Boolean): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = if (silent) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            if (silent) {
                if (isFa) "گوشی روی حالت ویبره قرار گرفت." else "Phone switched to vibrate mode."
            } else {
                if (isFa) "صدای زنگ گوشی فعال شد." else "Ringer restored to normal mode."
            }
        } catch (e: Exception) {
            if (isFa) "تغییر حالت زنگ نیازمند مجوز عدم مزاحمت است." else "Do Not Disturb permission is required to change ringer mode."
        }
    }

    fun battery(isFa: Boolean): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        if (level < 0) return if (isFa) "نتوانستم وضعیت باتری را بخوانم." else "Unable to read battery status."
        val pct = level * 100 / scale
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return if (isFa) {
            val stateTxt = if (charging) "در حال شارژ" else "در حال مصرف"
            "شارژ باتری $pct درصد است ($stateTxt، دمای باتری ${temp} درجه)."
        } else {
            val stateTxt = if (charging) "charging" else "discharging"
            "Battery is at $pct% ($stateTxt, temperature ${temp}°C)."
        }
    }

    fun openWifiPanel(isFa: Boolean): String =
        if (launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) {
            if (isFa) "پنل اینترنت و وای‌فای باز شد." else "Internet & Wi-Fi panel opened."
        } else {
            if (isFa) "نتوانستم تنظیمات را باز کنم." else "Could not open connectivity settings."
        }

    fun openBluetoothSettings(isFa: Boolean): String =
        if (launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) {
            if (isFa) "تنظیمات بلوتوث باز شد." else "Bluetooth settings opened."
        } else {
            if (isFa) "نتوانستم تنظیمات را باز کنم." else "Could not open Bluetooth settings."
        }

    fun openDisplaySettings(isFa: Boolean): String =
        if (launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))) {
            if (isFa) "تنظیمات نور و صفحه نمایش باز شد." else "Display & brightness settings opened."
        } else {
            if (isFa) "نتوانستم تنظیمات صفحه نمایش را باز کنم." else "Could not open display settings."
        }

    private fun findContact(name: String): Pair<String, String>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )
        c?.use { if (it.moveToFirst()) return Pair(it.getString(0), it.getString(1)) }
        return null
    }

    private fun resolveTarget(target: String): Pair<String, String>? {
        val digits = target.replace(" ", "").replace("-", "")
        if (digits.length >= 5 && digits.all { it.isDigit() || it == '+' }) {
            return Pair(digits, digits)
        }
        return findContact(target.trim())
    }

    fun call(target: String, isFa: Boolean): String {
        val resolved = resolveTarget(target) ?: return if (isFa) {
            "مخاطبی به نام ${target.trim()} پیدا نکردم (یا مجوز مخاطبین داده نشده)."
        } else {
            "Contact ${target.trim()} not found (or contacts permission missing)."
        }
        val (label, number) = resolved
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (launch(Intent(action, uri))) {
            if (isFa) "در حال تماس با $label" else "Calling $label..."
        } else {
            if (isFa) "نتوانستم تماس بگیرم." else "Unable to initiate call."
        }
    }

    fun sendSms(target: String, message: String, isFa: Boolean): String {
        val resolved = resolveTarget(target) ?: return if (isFa) {
            "مخاطب ${target.trim()} پیدا نشد."
        } else {
            "Contact ${target.trim()} not found."
        }
        val (label, number) = resolved
        if (message.isBlank()) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            return if (launch(i)) {
                if (isFa) "صفحه پیامک برای $label باز شد." else "Opened SMS screen for $label."
            } else {
                if (isFa) "نتوانستم پیامک را باز کنم." else "Could not open SMS app."
            }
        }
        return try {
            if (granted(Manifest.permission.SEND_SMS)) {
                @Suppress("DEPRECATION")
                SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
                if (isFa) "پیامک با متن «$message» برای $label ارسال شد." else "SMS sent to $label: "$message""
            } else {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
                    putExtra("sms_body", message)
                }
                if (launch(i)) {
                    if (isFa) "متن پیامک برای $label آماده شد؛ دکمه ارسال را بزنید." else "SMS draft ready for $label; tap send."
                } else {
                    if (isFa) "نتوانستم پیامک را باز کنم." else "Could not open SMS app."
                }
            }
        } catch (e: Exception) {
            if (isFa) "ارسال پیامک با خطا مواجه شد." else "Failed to send SMS."
        }
    }

    fun sendWhatsApp(target: String, message: String, isFa: Boolean): String {
        val resolved = resolveTarget(target) ?: return if (isFa) {
            "مخاطب ${target.trim()} در دفترچه تلفن پیدا نشد."
        } else {
            "Contact ${target.trim()} not found in contacts."
        }
        val (label, rawNum) = resolved
        var cleanNum = rawNum.replace(Regex("[^0-9+]"), "")
        if (cleanNum.startsWith("09")) cleanNum = "98" + cleanNum.substring(1)
        cleanNum = cleanNum.removePrefix("+")
        val url = "https://api.whatsapp.com/send?phone=$cleanNum&text=" + Uri.encode(message)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return if (launch(i)) {
            if (isFa) "صفحه چت واتساپ با $label باز شد." else "Opened WhatsApp chat with $label."
        } else {
            if (isFa) "واتساپ روی گوشی نصب نیست." else "WhatsApp is not installed on this device."
        }
    }

    fun navigateTo(destination: String, isFa: Boolean): String {
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        if (launch(mapIntent)) {
            return if (isFa) "مسیریابی به سمت $destination در گوگل مپ شروع شد." else "Starting navigation to $destination."
        }
        val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(destination))
        return if (launch(Intent(Intent.ACTION_VIEW, webUri))) {
            if (isFa) "نقشه برای $destination باز شد." else "Opened map for $destination."
        } else {
            if (isFa) "نتوانستم نقشه را باز کنم." else "Could not open maps."
        }
    }

    fun playYouTube(query: String, isFa: Boolean): String {
        val webUri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
        }
        if (launch(ytIntent)) {
            return if (isFa) "در حال جستجوی «$query» در یوتیوب." else "Searching YouTube for "$query"."
        }
        return if (launch(Intent(Intent.ACTION_VIEW, webUri))) {
            if (isFa) "یوتیوب برای «$query» باز شد." else "Opened YouTube for "$query"."
        } else {
            if (isFa) "نتوانستم یوتیوب را باز کنم." else "Could not open YouTube."
        }
    }

    fun search(q: String, isFa: Boolean): String {
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        return if (launch(Intent(Intent.ACTION_VIEW, uri))) {
            if (isFa) "نتیجه‌ی جستجوی «$q» باز شد." else "Opened search results for "$q"."
        } else {
            if (isFa) "نتوانستم مرورگر را باز کنم." else "Could not open browser."
        }
    }

    fun readClipboard(isFa: Boolean): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        return if (text.isNullOrEmpty()) {
            if (isFa) "کلیپ‌بورد گوشی خالی است." else "Your clipboard is currently empty."
        } else {
            if (isFa) "متن کپی‌شده در کلیپ‌بورد: $text" else "Clipboard content: $text"
        }
    }

    fun flipCoin(isFa: Boolean): String {
        val heads = Random.nextBoolean()
        return if (isFa) (if (heads) "شیر آمد!" else "خط آمد!") else (if (heads) "It landed on Heads!" else "It landed on Tails!")
    }

    fun rollDice(isFa: Boolean): String {
        val n = Random.nextInt(1, 7)
        return if (isFa) "تاس عدد $n را نشان می‌دهد." else "You rolled a $n."
    }

    fun randomPassword(len: Int = 12, isFa: Boolean): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#%&*"
        val pass = (1..len.coerceIn(6, 32)).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Vexa Password", pass))
        return if (isFa) "رمز عبور قوی ساخته شد و در کلیپ‌بورد کپی شد: $pass" else "Strong password generated and copied to clipboard: $pass"
    }

    fun timer(seconds: Int, label: String, isFa: Boolean): String {
        val s = seconds.coerceIn(1, 86400)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return if (launch(i)) {
            if (isFa) "تایمر تنظیم شد." else "Timer set."
        } else {
            if (isFa) "نتوانستم تایمر بگذارم." else "Could not set timer."
        }
    }

    fun alarm(hour: Int, minute: Int, isFa: Boolean): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) {
            if (isFa) "آلارم برای ساعت $hour و $minute دقیقه تنظیم شد." else String.format(Locale.US, "Alarm set for %02d:%02d.", hour, minute)
        } else {
            if (isFa) "نتوانستم آلارم بگذارم." else "Could not set alarm."
        }
    }

    private val notesFile get() = File(ctx.filesDir, "vexa_notes.txt")

    fun saveNote(text: String, isFa: Boolean): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        notesFile.appendText("[$stamp] $text\n")
        return if (isFa) "یادداشت شد." else "Note saved."
    }

    fun readNotes(isFa: Boolean): String {
        if (!notesFile.exists()) return if (isFa) "هنوز یادداشتی ندارید." else "You don't have any notes yet."
        val last = notesFile.readLines().filter { it.isNotBlank() }.takeLast(5)
        if (last.isEmpty()) return if (isFa) "هنوز یادداشتی ندارید." else "You don't have any notes yet."
        val joined = last.joinToString(" . ") { it.substringAfter("] ") }
        return if (isFa) "آخرین یادداشت‌ها: $joined" else "Recent notes: $joined"
    }
}
