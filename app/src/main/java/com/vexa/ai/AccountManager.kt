package com.vexa.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

class AccountManager(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("vexa_accounts_db", Context.MODE_PRIVATE)

    data class UserAccount(
        val fullName: String,
        val phone: String,
        val passwordHash: String
    )

    fun isLoggedIn(): Boolean {
        return !prefs.getString("current_user_phone", "").isNullOrEmpty()
    }

    fun getCurrentUser(): UserAccount? {
        val currentPhone = prefs.getString("current_user_phone", "").orEmpty()
        if (currentPhone.isEmpty()) return null
        return findByPhone(currentPhone)
    }

    fun generateStrongPassword(): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "@#$%&"
        val all = upper + lower + digits + symbols
        val chars = mutableListOf(
            upper[Random.nextInt(upper.length)],
            lower[Random.nextInt(lower.length)],
            digits[Random.nextInt(digits.length)],
            symbols[Random.nextInt(symbols.length)]
        )
        repeat(6) { chars.add(all[Random.nextInt(all.length)]) }
        chars.shuffle()
        return chars.joinToString("")
    }

    fun normalizePhone(raw: String): String {
        val sb = StringBuilder()
        for (c in raw.trim()) {
            when (c) {
                in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                in '0'..'9' -> sb.append(c)
                '+' -> if (sb.isEmpty()) sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun hashPassword(pass: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun sendVerificationOtp(rawName: String, rawPhone: String, rawPassword: String): Triple<Boolean, String, Boolean> {
        val name = rawName.trim()
        val phone = normalizePhone(rawPhone)
        val pass = rawPassword.trim()

        if (name.length < 2) return Triple(false, "لطفاً نام و نام خانوادگی خود را وارد کنید.", false)
        if (phone.length < 10) return Triple(false, "شماره تلفن معتبر نیست (مثلاً 09123456789).", false)
        if (pass.length < 6) return Triple(false, "رمز عبور باید حداقل ۶ کاراکتر باشد.", false)

        val all = getAllUsers()
        if (all.any { it.phone == phone }) {
            return Triple(false, "این شماره تلفن قبلاً ثبت‌نام شده است. از بخش ورود وارد شوید.", false)
        }

        val otpCode = Random.nextInt(10000, 100000).toString()
        prefs.edit()
            .putString("pending_name", name)
            .putString("pending_phone", phone)
            .putString("pending_pass_hash", hashPassword(pass))
            .putString("pending_otp", otpCode)
            .putLong("pending_otp_time", System.currentTimeMillis())
            .apply()

        var smsSent = false
        val smsBody = "VEXA AI\nکد تایید: $otpCode"
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                SmsManager.getDefault().sendTextMessage(
                    phone,
                    null,
                    smsBody,
                    null,
                    null
                )
                smsSent = true
            }
        } catch (_: Exception) {
            smsSent = false
        }

        val msg = if (smsSent) {
            "کد تایید ۵ رقمی VEXA AI به $phone ارسال شد."
        } else {
            "کد تایید ۵ رقمی VEXA AI: $otpCode"
        }
        return Triple(true, msg, smsSent)
    }

    fun verifyOtpAndCompleteRegistration(rawOtp: String): Pair<Boolean, String> {
        val inputCode = normalizePhone(rawOtp)
        val savedCode = prefs.getString("pending_otp", "").orEmpty()
        val savedTime = prefs.getLong("pending_otp_time", 0L)

        if (savedCode.isEmpty()) {
            return false to "ابتدا دکمه ارسال کد تایید ۵ رقمی را بزنید."
        }
        if (System.currentTimeMillis() - savedTime > 10 * 60 * 1000L) {
            return false to "کد تایید منقضی شده است. لطفاً دوباره درخواست کد دهید."
        }
        if (inputCode != savedCode) {
            return false to "کد تایید ۵ رقمی اشتباه است! لطفاً کد صحیح را وارد کنید."
        }

        val name = prefs.getString("pending_name", "").orEmpty()
        val phone = prefs.getString("pending_phone", "").orEmpty()
        val passHash = prefs.getString("pending_pass_hash", "").orEmpty()

        val all = getAllUsers()
        all.add(UserAccount(name, phone, passHash))
        saveAllUsers(all)

        prefs.edit()
            .remove("pending_otp")
            .remove("pending_name")
            .remove("pending_phone")
            .remove("pending_pass_hash")
            .putString("current_user_phone", phone)
            .apply()

        return true to "شماره تلفن شما تایید شد! خوش آمدید $name عزیز."
    }

    fun login(rawPhone: String, rawPassword: String): Pair<Boolean, String> {
        val phone = normalizePhone(rawPhone)
        val pass = rawPassword.trim()
        val user = findByPhone(phone) ?: return false to "حسابی با این شماره تلفن پیدا نشد. لطفاً ابتدا ثبت‌نام کنید."
        if (user.passwordHash != hashPassword(pass)) {
            return false to "رمز عبور اشتباه است."
        }
        prefs.edit().putString("current_user_phone", phone).apply()
        return true to "خوش آمدید ${user.fullName} عزیز!"
    }

    fun logout() {
        prefs.edit().remove("current_user_phone").apply()
    }

    private fun findByPhone(phone: String): UserAccount? {
        return getAllUsers().firstOrNull { it.phone == phone }
    }

    private fun getAllUsers(): MutableList<UserAccount> {
        val raw = prefs.getString("users_list", "[]") ?: "[]"
        val out = mutableListOf<UserAccount>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    UserAccount(
                        fullName = o.optString("name"),
                        phone = o.optString("phone"),
                        passwordHash = o.optString("pass")
                    )
                )
            }
        } catch (_: Exception) { }
        return out
    }

    private fun saveAllUsers(users: List<UserAccount>) {
        val arr = JSONArray()
        for (u in users) {
            arr.put(
                JSONObject()
                    .put("name", u.fullName)
                    .put("phone", u.phone)
                    .put("pass", u.passwordHash)
            )
        }
        prefs.edit().putString("users_list", arr.toString()).apply()
    }
}
