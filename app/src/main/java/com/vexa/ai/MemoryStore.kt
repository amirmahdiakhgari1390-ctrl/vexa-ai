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

    fun remember(fact: String, isPersian: Boolean = true, maxRules: Int = Int.MAX_VALUE): String {
        val clean = fact.trim()
        if (clean.isEmpty()) {
            return if (isPersian) "چه نکته‌ای را به وِکسا آموزش دهم؟" else "What would you like to teach Vexa?"
        }
        val list = getAll().toMutableList()
        if (clean !in list) {
            if (list.size >= maxRules) {
                return if (isPersian) {
                    "ظرفیت حافظه تکمیل شده است."
                } else {
                    "Memory capacity reached."
                }
            }
            list.add(clean)
            saveAll(list)
        }
        return if (isPersian) "آموزش داده شد و در ذهن وِکسا ثبت گردید: «$clean»" else "Learned and saved to Vexa memory: \"$clean\""
    }

    fun autoLearnFromUserMessage(userMsg: String, maxRules: Int) {
        val clean = userMsg.trim()
        if (clean.length < 8 || clean.length > 160) return
        val triggers = listOf(
            "اسم من ", "من هستم", "شغل من", "من تریدر", "من برنامه نویس", "من برنامه‌نویس",
            "علاقه دارم", "دوست دارم", "همیشه به من", "از این به بعد", "سبک من",
            "my name is", "i am a ", "i work as", "i prefer", "always call me", "from now on"
        )
        val lower = clean.lowercase()
        if (triggers.any { lower.contains(it) }) {
            val list = getAll().toMutableList()
            if (list.size < maxRules && list.none { it.equals(clean, ignoreCase = true) }) {
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
        prefs.edit().remove("facts").remove("custom_instructions").apply()
        return if (isPersian) "تمامی آموزش‌ها و حافظه بلندمدت وِکسا پاک شد." else "All Vexa training and long-term memory cleared."
    }

    fun asPromptContext(): String {
        val sb = StringBuilder()
        val custom = getCustomInstructions()
        if (custom.isNotEmpty()) {
            sb.append("\n[User's Custom Training & Behavior Rules - STRICTLY FOLLOW]:\n")
            sb.append(custom).append("\n")
        }
        val facts = getAll()
        if (facts.isNotEmpty()) {
            sb.append("\n[Learned Facts & User Preferences]:\n")
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
