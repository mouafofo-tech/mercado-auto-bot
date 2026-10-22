package com.mercado.bot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mémoire du bot : tâches, conversations, statistiques d'apprentissage, réglages. */
object Store {
    const val DAILY_LIMIT = 200

    private lateinit var app: Context
    private var lastId = 0L

    fun init(c: Context) {
        app = c.applicationContext
    }

    private fun sp() = app.getSharedPreferences("mercado_bot", Context.MODE_PRIVATE)

    private fun arr(key: String): JSONArray =
        try { JSONArray(sp().getString(key, "[]")) } catch (e: Exception) { JSONArray() }

    private fun obj(key: String): JSONObject =
        try { JSONObject(sp().getString(key, "{}")) } catch (e: Exception) { JSONObject() }

    private fun put(key: String, v: Any) {
        sp().edit().putString(key, v.toString()).apply()
    }

    private fun time(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun nextId(): Long {
        val id = maxOf(System.currentTimeMillis(), lastId + 1)
        lastId = id
        return id
    }

    private fun trimTo(a: JSONArray, max: Int): JSONArray {
        if (a.length() <= max) return a
        val r = JSONArray()
        for (i in a.length() - max until a.length()) r.put(a.get(i))
        return r
    }

    var paused: Boolean
        get() = sp().getBoolean("paused", false)
        set(v) { sp().edit().putBoolean("paused", v).apply() }

    // ---------- tâches ----------
    @Synchronized
    fun tasks(): JSONArray {
        val a = arr("tasks")
        val r = JSONArray()
        for (i in a.length() - 1 downTo 0) r.put(a.get(i))
        return r
    }

    @Synchronized
    fun addTask(title: String, detail: String, tag: String): Long {
        val id = nextId()
        val a = arr("tasks")
        a.put(JSONObject().put("id", id).put("title", title).put("detail", detail).put("tag", tag).put("done", false))
        put("tasks", trimTo(a, 100))
        return id
    }

    @Synchronized
    fun updateTask(id: Long, done: Boolean, detail: String? = null) {
        val a = arr("tasks")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("id") == id) {
                o.put("done", done)
                if (detail != null) o.put("detail", detail)
            }
        }
        put("tasks", a)
    }

    // ---------- conversations ----------
    @Synchronized
    fun convs(): JSONArray {
        val a = arr("convs")
        val r = JSONArray()
        for (i in a.length() - 1 downTo 0) r.put(a.get(i))
        return r
    }

    @Synchronized
    fun addMessage(channel: String, name: String, from: String, text: String) {
        val a = arr("convs")
        var found: JSONObject? = null
        val rest = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (found == null && o.getString("channel") == channel && o.getString("name") == name) found = o
            else rest.put(o)
        }
        val c = found ?: JSONObject().put("id", nextId()).put("channel", channel).put("name", name).put("msgs", JSONArray())
        val msgs = c.getJSONArray("msgs")
        msgs.put(JSONObject().put("id", nextId()).put("from", from).put("text", text).put("time", time()))
        c.put("msgs", trimTo(msgs, 40))
        c.put("unread", from == "client")
        rest.put(c)
        put("convs", trimTo(rest, 30))
    }

    /** Les derniers messages d'une conversation, pour donner le contexte à ChatGPT. */
    @Synchronized
    fun history(channel: String, name: String, max: Int = 6): String {
        val a = arr("convs")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("channel") == channel && o.getString("name") == name) {
                val m = o.getJSONArray("msgs")
                val sb = StringBuilder()
                for (j in maxOf(0, m.length() - max) until m.length()) {
                    val x = m.getJSONObject(j)
                    sb.append(if (x.getString("from") == "client") "Client : " else "Moi : ")
                    sb.append(x.getString("text").replace("\n", " ")).append(" | ")
                }
                return sb.toString()
            }
        }
        return ""
    }

    // ---------- apprentissage ----------
    @Synchronized
    fun record(key: String, ok: Boolean) {
        val s = obj("stats")
        val o = s.optJSONObject(key) ?: JSONObject().put("ok", 0).put("fail", 0)
        val k = if (ok) "ok" else "fail"
        o.put(k, o.getInt(k) + 1)
        s.put(key, o)
        put("stats", s)
    }

    @Synchronized
    fun score(key: String): Double {
        val o = obj("stats").optJSONObject(key) ?: return 0.5
        val ok = o.getInt("ok")
        val fail = o.getInt("fail")
        return (ok + 1.0) / (ok + fail + 2.0)
    }

    @Synchronized
    fun recordWait(pkg: String, ms: Long) {
        val s = obj("waits")
        val old = s.optDouble(pkg, 2000.0)
        s.put(pkg, 0.7 * old + 0.3 * ms)
        put("waits", s)
    }

    @Synchronized
    fun settleMs(pkg: String): Long =
        (obj("waits").optDouble(pkg, 2000.0) * 0.5).toLong().coerceIn(500L, 3000L)

    @Synchronized
    fun learned(key: String): String? = obj("learned").optString(key, "").ifBlank { null }

    @Synchronized
    fun learn(key: String, reply: String) {
        val s = obj("learned")
        if (s.length() >= 200) return
        s.put(key, reply)
        put("learned", s)
    }

    // ---------- limite journalière ----------
    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    @Synchronized
    fun actionsToday(): Int = if (sp().getString("day", "") == day()) sp().getInt("count", 0) else 0

    @Synchronized
    fun countAction() {
        sp().edit().putString("day", day()).putInt("count", actionsToday() + 1).apply()
    }
}
