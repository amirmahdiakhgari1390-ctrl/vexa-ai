package com.vexa.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/**
 * سیستم ثبت‌نام و ورود اختصاصی Vexa AI (با نام، شماره تلفن و رمز عبور + ساخت خودکار رمز عبور امن)
 */
class AccountManager(ctx: Context) {

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

    fun register(rawName: String, rawPhone: String, rawPassword: String): Pair<Boolean, String> {
        val name = rawName.trim()
        val phone = normalizePhone(rawPhone)
        val pass = rawPassword.trim()

        if (name.length < 2) return false to "لطفاً نام و نام خانوادگی خود را وارد کنید."
        if (phone.length < 10) return false to "شماره تلفن معتبر نیست (حداقل ۱۰ رقم وارد کنید)."
        if (pass.length < 6) return false to "رمز عبور باید حداقل ۶ کاراکتر باشد."

        val all = getAllUsers()
        if (all.any { it.phone == phone }) {
            return false to "این شماره تلفن قبلاً ثبت‌نام شده است. از بخش ورود وارد شوید."
        }

        val newAcc = UserAccount(name, phone, hashPassword(pass))
        all.add(newAcc)
        saveAllUsers(all)
        prefs.edit().putString("current_user_phone", phone).apply()
        return true to "خوش آمدید $name عزیز! حساب کاربری شما ساخته شد."
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
