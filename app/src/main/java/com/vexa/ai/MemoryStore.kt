package com.vexa.ai

import android.content.Context
import org.json.JSONArray

class MemoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("vexa_memory", Context.MODE_PRIVATE)

    fun remember(fact: String, isPersian: Boolean = true): String {
        val clean = fact.trim()
        if (clean.isEmpty()) {
            return if (isPersian) "چه چیزی را به خاطر بسپارم؟" else "What would you like me to remember?"
        }
        val list = getAll().toMutableList()
        if (clean !in list) {
            list.add(clean)
            saveAll(list)
        }
        return if (isPersian) "در حافظه دائمی وِکسا ثبت شد: «$clean»" else "Saved to Vexa permanent memory: "$clean""
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
        prefs.edit().remove("facts").apply()
        return if (isPersian) "تمامی حافظه بلندمدت پاک شد." else "All permanent memory has been cleared."
    }

    fun asPromptContext(): String {
        val facts = getAll()
        if (facts.isEmpty()) return ""
        return "\nUser's saved long-term memory facts:\n" + facts.joinToString("\n") { "- $it" }
    }

    private fun saveAll(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("facts", arr.toString()).apply()
    }
}
