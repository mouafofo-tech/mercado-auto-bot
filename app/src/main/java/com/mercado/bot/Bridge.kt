package com.mercado.bot

import android.content.Intent
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Passerelle entre le dashboard (index.html) et le bot. Lecture seule, sauf la Pause. */
class Bridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun getTasks(): String = Store.tasks().toString()

    @JavascriptInterface
    fun getConversations(): String = Store.convs().toString()

    @JavascriptInterface
    fun getSettings(): String = JSONObject()
        .put("paused", Store.paused)
        .put("connected", BotService.instance != null)
        .put("bots", JSONObject().put("telegram", true).put("tiktok", true).put("site", false))
        .toString()

    @JavascriptInterface
    fun setPaused(p: Boolean) {
        Store.paused = p
    }

    @JavascriptInterface
    fun openAccessibility() {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
