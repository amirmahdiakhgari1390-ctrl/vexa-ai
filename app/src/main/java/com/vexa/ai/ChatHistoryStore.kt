package com.vexa.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * مدیریت تاریخچه گفتگوهای چندگانه (مشابه تاریخچه چت در Vexa AI)
 */
class ChatHistoryStore(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("vexa_chat_sessions", Context.MODE_PRIVATE)
    private val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    data class StoredMessage(
        val text: String,
        val senderName: String,
        val time: String
    )

    data class ChatSession(
        val id: String,
        var title: String,
        var updatedAt: String,
        val messages: MutableList<StoredMessage>
    )

    fun getAllSessions(): MutableList<ChatSession> {
        val raw = prefs.getString("sessions_json", "[]") ?: "[]"
        val result = mutableListOf<ChatSession>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val msgsArr = obj.optJSONArray("messages") ?: JSONArray()
                val msgs = mutableListOf<StoredMessage>()
                for (j in 0 until msgsArr.length()) {
                    val m = msgsArr.getJSONObject(j)
                    msgs.add(
                        StoredMessage(
                            text = m.optString("text"),
                            senderName = m.optString("sender"),
                            time = m.optString("time")
                        )
                    )
                }
                result.add(
                    ChatSession(
                        id = obj.optString("id"),
                        title = obj.optString("title", "New Chat"),
                        updatedAt = obj.optString("updatedAt", ""),
                        messages = msgs
                    )
                )
            }
        } catch (_: Exception) { }
        return result
    }

    fun createNewSession(defaultTitle: String = "گفتگوی جدید / New Chat"): ChatSession {
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = defaultTitle,
            updatedAt = dateFmt.format(Date()),
            messages = mutableListOf()
        )
        val all = getAllSessions()
        all.add(0, session)
        saveAll(all)
        return session
    }

    fun saveSession(session: ChatSession) {
        session.updatedAt = dateFmt.format(Date())
        val all = getAllSessions()
        val idx = all.indexOfFirst { it.id == session.id }
        if (idx >= 0) {
            all.removeAt(idx)
        }
        all.add(0, session)
        // Keep up to 40 recent conversations
        while (all.size > 40) all.removeAt(all.size - 1)
        saveAll(all)
    }

    fun deleteSession(sessionId: String) {
        val all = getAllSessions()
        all.removeAll { it.id == sessionId }
        saveAll(all)
    }

    private fun saveAll(sessions: List<ChatSession>) {
        val arr = JSONArray()
        for (s in sessions) {
            val msgsArr = JSONArray()
            for (m in s.messages) {
                msgsArr.put(
                    JSONObject()
                        .put("text", m.text)
                        .put("sender", m.senderName)
                        .put("time", m.time)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("messages", msgsArr)
            )
        }
        prefs.edit().putString("sessions_json", arr.toString()).apply()
    }
}
