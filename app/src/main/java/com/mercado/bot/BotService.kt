package com.mercado.bot

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * Le bot. Il lit les notifications Telegram / TikTok, ouvre la conversation, répond
 * (modèles, réponses apprises, sinon ChatGPT), et note tout dans Store pour le dashboard.
 *
 * Règles de sécurité codées en dur :
 *  - ne touche jamais aux applis de paiement / banque / argent (voir BLOCKED)
 *  - n'écrit jamais dans un champ mot de passe
 *  - n'écrit que si le nom du client est visible à l'écran
 *  - s'arrête quand Pause est active ou quand la limite du jour est atteinte
 *  - ChatGPT ne peut choisir qu'une action de la liste ACTIONS
 */
class BotService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BotService? = null

        const val CHATGPT = "com.openai.chatgpt"
        const val MARK = "REPONSE>>"

        val WATCHED = mapOf(
            "org.telegram.messenger" to "telegram",
            "com.zhiliaoapp.musically" to "tiktok",
            "com.ss.android.ugc.trill" to "tiktok"
        )
        val SEND_WORDS = listOf("send", "envoyer", "send message", "envoyer le message")
        val ACTIONS = listOf("RETRY", "BACK", "HOME", "RESTART", "WAIT", "SKIP")
        val BLOCKED = listOf("bank", "pay", "money", "wallet", "orange")
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var lastKey = ""
    private var lastAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.init(this)
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val channel = WATCHED[pkg] ?: return
        val n = e.parcelableData as? Notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isBlank()) return

        val key = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 15000) return
        lastKey = key
        lastAt = now

        exec.execute {
            try {
                handleMessage(pkg, channel, title, text, n)
            } catch (t: Throwable) {
                Store.addTask("Problème interne", t.message ?: t.javaClass.simpleName, "Problème")
            }
        }
    }

    // ------------------------------------------------------------------
    // Cerveau
    // ------------------------------------------------------------------
    private fun canAct(): Boolean = !Store.paused && Store.actionsToday() < Store.DAILY_LIMIT

    private fun safePkg(pkg: String): Boolean {
        val p = pkg.lowercase()
        return BLOCKED.none { p.contains(it) }
    }

    private fun handleMessage(pkg: String, channel: String, title: String, text: String, n: Notification) {
        if (!canAct() || !safePkg(pkg)) return
        val tag = if (channel == "tiktok") "TikTok" else "Telegram"
        val taskId = Store.addTask("Répondre à $title", text.take(90), tag)
        Store.addMessage(channel, title, "client", text)

        var from = "bot"
        var reply: String? = Brain.reply(text)
        var fromAi = false
        if (reply == null) {
            from = "ia"
            reply = askChatGpt(
                "Un client de ma boutique en ligne écrit : \"${text.take(300)}\". " +
                    "Écris une courte réponse polie de vendeur, dans la langue du client."
            )
            fromAi = reply != null
        }
        val msg = reply?.replace("\n", " ")?.trim()?.takeIf { it.isNotBlank() } ?: run {
            Store.updateTask(taskId, false, "Aucune réponse trouvée (ChatGPT indisponible)")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        var done = false
        var attempt = 0
        while (!done && attempt < 2) {
            attempt++
            done = openChat(pkg, title, n) && sendText(pkg, msg)
            if (!done && attempt < 2) {
                val act = solveProblem(pkg, "impossible d'envoyer la réponse à $title")
                if (act == "SKIP") break
            }
        }
        Store.record("$pkg|reply", done)
        if (done) {
            Store.countAction()
            Store.addMessage(channel, title, from, msg)
            if (fromAi) Brain.learn(text, msg)
            Store.updateTask(taskId, true, "Réponse envoyée ($from)")
        } else {
            Store.updateTask(taskId, false, "Échec après 2 essais")
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** En cas de problème, le bot demande à ChatGPT de choisir UNE action dans une liste sûre. */
    private fun solveProblem(pkg: String, what: String): String {
        val id = Store.addTask("Problème : $what", "Le bot demande à l'IA quoi faire", "Problème")
        val ans = askChatGpt(
            "Un robot qui contrôle un téléphone Android a un problème : $what (appli $pkg). " +
                "Choisis UNE action parmi : ${ACTIONS.joinToString(", ")}. Réponds avec ce seul mot."
        )
        val act = ACTIONS.firstOrNull { ans?.uppercase()?.contains(it) == true } ?: "WAIT"
        when (act) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RESTART" -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Thread.sleep(1500)
                launch(pkg)
            }
            "WAIT" -> Thread.sleep(5000)
            else -> {}
        }
        Store.updateTask(id, true, "L'IA a choisi : $act")
        return act
    }

    /** Ouvre ChatGPT sur le téléphone, pose la question, lit la réponse. */
    private fun askChatGpt(question: String): String? {
        if (!launch(CHATGPT)) return null
        val t0 = System.currentTimeMillis()
        if (!waitFor(10000) { fg() == CHATGPT }) return null
        Store.recordWait(CHATGPT, System.currentTimeMillis() - t0)
        Thread.sleep(Store.settleMs(CHATGPT))

        val prompt = "$question Réponds en commençant par $MARK puis ton texte, sans rien d'autre."
        if (!sendText(CHATGPT, prompt)) return null

        var last = ""
        var same = 0
        val end = System.currentTimeMillis() + 90000
        while (System.currentTimeMillis() < end && same < 3) {
            Thread.sleep(1500)
            val cur = allNodes()
                .mapNotNull { it.text?.toString() }
                .lastOrNull { it.contains(MARK) && !it.contains("commençant par") }
                ?: continue
            if (cur == last) same++ else { same = 0; last = cur }
        }
        val ans = last.substringAfter(MARK).trim()
        return if (ans.isBlank()) null else ans
    }

    // ------------------------------------------------------------------
    // Manipulation de l'écran
    // ------------------------------------------------------------------
    private fun fg(): String? = rootInActiveWindow?.packageName?.toString()

    private fun launch(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return true
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun <T> waitNode(ms: Long, f: () -> T?): T? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val r = f()
            if (r != null) return r
            Thread.sleep(250)
        }
        return null
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > 40) return
        out.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        walk(rootInActiveWindow, out, 0)
        return out
    }

    private fun screenContains(s: String): Boolean =
        allNodes().any { it.text?.toString()?.contains(s, ignoreCase = true) == true }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var i = 0
        while (cur != null && i < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            i++
        }
        return false
    }

    private fun findEditable(): AccessibilityNodeInfo? =
        allNodes()
            .filter { it.isEditable && it.isVisibleToUser }
            .maxByOrNull { val b = Rect(); it.getBoundsInScreen(b); b.top }

    private fun openChat(pkg: String, title: String, n: Notification): Boolean {
        val t0 = System.currentTimeMillis()
        try { n.contentIntent?.send() } catch (e: Exception) { }
        var ok = waitFor(4000) { fg() == pkg }
        if (!ok) {
            launch(pkg)
            ok = waitFor(6000) { fg() == pkg }
        }
        if (!ok) return false
        Store.recordWait(pkg, System.currentTimeMillis() - t0)
        Thread.sleep(Store.settleMs(pkg))
        // Sécurité : on n'écrit que si la bonne conversation est ouverte.
        return title.isNotBlank() && screenContains(title)
    }

    private fun sendText(pkg: String, text: String): Boolean {
        if (Store.paused || !safePkg(pkg) || fg() != pkg) return false
        val edit = waitNode(5000) { findEditable() } ?: return false
        if (edit.isPassword) return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        Thread.sleep(600)
        val cur = findEditable()?.text?.toString() ?: ""
        if (!cur.startsWith(text.take(10))) return false
        return pressSend(pkg)
    }

    /** Essaie plusieurs façons d'appuyer sur Envoyer, la plus réussie d'abord (apprentissage). */
    private fun pressSend(pkg: String): Boolean {
        val order = listOf("desc", "text", "id", "right", "ime")
            .sortedByDescending { Store.score("$pkg|send|$it") }
        for (t in order) {
            if (Store.paused) return false
            val tried = trySend(t)
            if (!tried) {
                Store.record("$pkg|send|$t", false)
                continue
            }
            Thread.sleep(1500)
            val e = findEditable()
            val cleared = e == null || e.isShowingHintText || e.text.isNullOrEmpty()
            Store.record("$pkg|send|$t", cleared)
            if (cleared) return true
        }
        return false
    }

    private fun trySend(tactic: String): Boolean {
        val nodes = allNodes()
        if (tactic == "ime") {
            return Build.VERSION.SDK_INT >= 30 &&
                findEditable()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        }
        val target: AccessibilityNodeInfo? = when (tactic) {
            "desc" -> nodes.firstOrNull { n ->
                val d = n.contentDescription?.toString()?.trim()?.lowercase()
                d != null && SEND_WORDS.contains(d)
            }
            "text" -> nodes.firstOrNull { n ->
                val d = n.text?.toString()?.trim()?.lowercase()
                d != null && SEND_WORDS.contains(d)
            }
            "id" -> nodes.firstOrNull { it.viewIdResourceName?.lowercase()?.contains("send") == true }
            "right" -> findEditable()?.let { rightOf(it, nodes) }
            else -> null
        }
        return target != null && click(target)
    }

    private fun rightOf(e: AccessibilityNodeInfo, nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val eb = Rect()
        e.getBoundsInScreen(eb)
        return nodes
            .filter { n ->
                val b = Rect()
                n.getBoundsInScreen(b)
                n != e && n.isClickable && n.isVisibleToUser &&
                    b.centerX() > eb.centerX() && b.centerY() in eb.top..eb.bottom &&
                    b.width() < eb.width() / 2
            }
            .maxByOrNull { val b = Rect(); it.getBoundsInScreen(b); b.right }
    }
}
