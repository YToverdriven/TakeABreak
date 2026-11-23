package com.takeabreak.watcher

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** The last ten alerts received from the Take a Break controller. */
object ReceivedMessages {
    private const val PREFS = "watcher_messages"
    private const val KEY = "messages"
    const val MAX = 10

    fun add(context: Context, text: String) {
        val items = load(context).toMutableList()
        items.add(0, Entry(System.currentTimeMillis(), text))
        val kept = items.take(MAX)
        val array = JSONArray()
        for ((at, message) in kept) {
            array.put(JSONObject().put("t", at).put("m", message))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, array.toString())
        }
    }

    fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val items = mutableListOf<Entry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val text = obj.optString("m").trim()
                if (text.isEmpty()) continue
                items.add(Entry(obj.optLong("t"), text))
            }
            items.take(MAX)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun displayText(context: Context): String {
        val items = load(context)
        if (items.isEmpty()) return "No messages from Take a Break yet."
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        return items.joinToString("\n\n") { entry ->
            format.format(Date(entry.at)) + "\n" + entry.text
        }
    }

    data class Entry(val at: Long, val text: String)
}
