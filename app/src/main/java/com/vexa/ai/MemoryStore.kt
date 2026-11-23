package com.vexa.ai

import android.content.Context
import org.json.JSONArray

class MemoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("vexa_training_memory", Context.MODE_PRIVATE)

    fun getCustomInstructions(): String {
        return prefs.getString("custom_instructions", "").orEmpty().trim()
    }

    fun setCustomInstructions(instructions: String) {
        prefs.edit().putString("custom_instructions", instructions.trim()).apply()
    }

    fun getUserNickname(): String = prefs.getString("user_nickname", "").orEmpty().trim()
    fun setUserNickname(nick: String) = prefs.edit().putString("user_nickname", nick.trim()).apply()

    fun getUserProfession(): String = prefs.getString("user_profession", "").orEmpty().trim()
    fun setUserProfession(prof: String) = prefs.edit().putString("user_profession", prof.trim()).apply()

    fun getResponseStyle(): String = prefs.getString("response_style", "balanced").orEmpty()
    fun setResponseStyle(style: String) = prefs.edit().putString("response_style", style).apply()

    fun remember(fact: String, isPersian: Boolean = true, @Suppress("UNUSED_PARAMETER") maxRules: Int = Int.MAX_VALUE): String {
        val clean = fact.trim()
        if (clean.isEmpty()) {
            return if (isPersian) "چه نکته‌ای را به وِکسا آموزش دهم؟" else "What would you like to teach Vexa?"
        }
        val list = getAll().toMutableList()
        if (clean !in list) {
            list.add(clean)
            saveAll(list)
        }
        return if (isPersian) "آموزش داده شد و در ذهن وِکسا ثبت گردید: «$clean»" else "Learned and saved to Vexa memory: \"$clean\""
    }

    fun autoLearnFromUserMessage(userMsg: String, @Suppress("UNUSED_PARAMETER") maxRules: Int = Int.MAX_VALUE) {
        val clean = userMsg.trim()
        if (clean.length < 6 || clean.length > 240) return
        val triggers = listOf(
            "اسم من ", "من هستم", "شغل من", "من تریدر", "من برنامه نویس", "من برنامه‌نویس",
            "علاقه دارم", "دوست دارم", "همیشه به من", "از این به بعد", "سبک من", "یاد بگیر",
            "my name is", "i am a ", "i work as", "i prefer", "always call me", "from now on"
        )
        val lower = clean.lowercase()
        if (triggers.any { lower.contains(it) }) {
            val list = getAll().toMutableList()
            if (list.none { it.equals(clean, ignoreCase = true) }) {
                list.add(clean)
                saveAll(list)
            }
        }
    }

    fun getAll(): List<String> {
        val raw = prefs.getString("facts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAll(isPersian: Boolean = true): String {
        prefs.edit()
            .remove("facts")
            .remove("custom_instructions")
            .remove("user_nickname")
            .remove("user_profession")
            .apply()
        return if (isPersian) "تمامی آموزش‌ها و حافظه بلندمدت وِکسا پاک شد." else "All Vexa training and long-term memory cleared."
    }

    fun asPromptContext(): String {
        val sb = StringBuilder()
        val nick = getUserNickname()
        val prof = getUserProfession()
        val style = getResponseStyle()
        if (nick.isNotEmpty() || prof.isNotEmpty()) {
            sb.append("\n[User Profile]:\n")
            if (nick.isNotEmpty()) sb.append("- Preferred Name / Nickname: ").append(nick).append("\n")
            if (prof.isNotEmpty()) sb.append("- User Profession & Expertise: ").append(prof).append("\n")
        }
        val styleRule = when (style) {
            "friendly" -> "Use a warm, friendly, conversational human tone."
            "formal" -> "Use a polite, formal, executive tone."
            "concise" -> "Keep answers sharp, concise, and straight to the point."
            "detailed" -> "Provide comprehensive, deep, step-by-step explanations."
            else -> "Adapt naturally to the user's tone with clarity and intelligence."
        }
        sb.append("\n[Preferred Response Style]: ").append(styleRule).append("\n")

        val custom = getCustomInstructions()
        if (custom.isNotEmpty()) {
            sb.append("\n[User's Unlimited Custom Training Rules - STRICTLY FOLLOW]:\n")
            sb.append(custom).append("\n")
        }
        val facts = getAll()
        if (facts.isNotEmpty()) {
            sb.append("\n[Unlimited Learned Facts & User Preferences]:\n")
            facts.forEach { sb.append("- ").append(it).append("\n") }
        }
        return sb.toString()
    }

    private fun saveAll(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("facts", arr.toString()).apply()
    }
}
