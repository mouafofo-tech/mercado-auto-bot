package com.mercado.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Levée quand tu actives la Pause : le bot s'arrête net. */
private class PauseStop : RuntimeException()

/**
 * Le bot MERCADO.
 *  - répond aux clients (Telegram / TikTok) : modèles, réponses apprises, sinon ChatGPT
 *  - ne répond JAMAIS sur les prix : il attend que tu répondes toi-même
 *  - prépare un brouillon TikTok (intro + vidéo CJ + texte à l'écran + légende)
 *  - travaille seulement quand tu n'utilises pas le téléphone
 *
 * Sécurité codée en dur :
 *  - ne touche jamais aux applis de paiement / banque / argent (BLOCKED)
 *  - n'écrit jamais dans un champ mot de passe
 *  - n'écrit à un client que si son nom est visible à l'écran
 *  - TikTok : appuie sur "Drafts", jamais sur "Post"
 *  - s'arrête avec la Pause ou à 200 actions par jour
 *  - ChatGPT ne peut choisir qu'une action de la liste ACTIONS
 */
class BotService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BotService? = null

        const val CHATGPT = "com.openai.chatgpt"
        const val MARK = "REPONSE>>"
        const val IDLE_MS = 3 * 60 * 1000L
        const val LOCK_START = 19 * 60
        const val LOCK_END = 21 * 60 + 30
        val CALL_WORDS = listOf("incallui", "dialer", "telecom", "com.android.phone")

        val WATCHED = mapOf(
            "org.telegram.messenger" to "telegram",
            "com.zhiliaoapp.musically" to "tiktok",
            "com.ss.android.ugc.trill" to "tiktok"
        )
        val SEND_WORDS = listOf(
            "send", "envoyer", "send message", "envoyer le message",
            "send prompt", "envoyer l'invite", "envoyer un message", "submit"
        )
        val ACTIONS = listOf("RETRY", "BACK", "HOME", "RESTART", "WAIT", "SKIP")
        val BLOCKED = listOf("bank", "pay", "money", "wallet", "orange")
        val SYSTEM_WORDS = listOf(
            "upload", "téléchargement", "envoi de", "publié", "posted",
            "brouillon", "draft", "traitement", "processing"
        )
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var lastKey = ""
    private var lastAt = 0L

    @Volatile
    private var lastUserActivity = System.currentTimeMillis()

    @Volatile
    private var botBusy = false

    private var postNote = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.init(this)
        instance = this
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        main.removeCallbacks(ticker)
        hideOverlay()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return

        // Un appel arrive : le bot rend le téléphone pendant 5 minutes.
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString()?.lowercase() ?: ""
            if (CALL_WORDS.any { p.contains(it) }) {
                emergencyUntil = System.currentTimeMillis() + 5 * 60 * 1000L
                hideOverlay()
            }
        }

        // Activité de l'utilisateur : tout ce qui bouge à l'écran quand le bot ne travaille pas.
        if (e.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (!botBusy && e.packageName?.toString() != packageName) {
                lastUserActivity = System.currentTimeMillis()
            }
            return
        }

        val pkg = e.packageName?.toString() ?: return
        val channel = WATCHED[pkg] ?: return
        val n = e.parcelableData as? Notification
        if (n == null) { ignore("notification sans contenu", pkg); return }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) { ignore("résumé de groupe", pkg); return }
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) { ignore("notification permanente", pkg); return }
        if (n.extras?.containsKey(Notification.EXTRA_PROGRESS_MAX) == true) { ignore("barre de progression", pkg); return }
        val cat = n.category
        if (cat == Notification.CATEGORY_PROGRESS || cat == Notification.CATEGORY_SERVICE ||
            cat == Notification.CATEGORY_STATUS || cat == Notification.CATEGORY_TRANSPORT
        ) { ignore("catégorie $cat", pkg); return }
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isBlank() || title.isBlank()) { ignore("titre ou texte vide", pkg); return }
        if (SYSTEM_WORDS.any { text.contains(it, ignoreCase = true) }) { ignore("notification système", pkg); return }

        val key = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 15000) return
        lastKey = key
        lastAt = now

        // Le message est noté tout de suite, même si le bot est occupé ailleurs.
        val history = Store.history(channel, title)
        Store.addMessage(channel, title, "client", text)
        runJob { handleMessage(pkg, channel, title, text, n, history) }
    }

    private var lastIgnore = 0L

    /** Dit dans le dashboard pourquoi une notification de TikTok / Telegram a été ignorée (1 fois par minute au plus). */
    private fun ignore(why: String, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastIgnore < 60000L) return
        lastIgnore = now
        val d = Store.addTask("Notification ignorée : $why", pkg, "Diagnostic")
        Store.updateTask(d, true)
    }

    private fun runJob(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (p: PauseStop) {
                // Pause : on s'arrête sans bruit
            } catch (t: Throwable) {
                Store.addTask("Problème interne", t.message ?: t.javaClass.simpleName, "Problème")
            } finally {
                botBusy = false
                releaseScreen()
            }
        }
    }

    @Volatile
    private var draftPending = false
    private var draftTaskId = 0L

    /** Bouton de test et horaire : le brouillon part dès que le téléphone est libre (une seule demande à la fois). */
    fun postNow() {
        if (draftPending || botBusy || !canAct()) return
        draftTaskId = Store.addTask("Brouillon TikTok", "En attente que le téléphone soit libre", "TikTok")
        draftPending = true
    }

    private fun canStartNow(): Boolean {
        if (Store.paused) return false
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) return false
        return System.currentTimeMillis() - lastUserActivity >= IDLE_MS || overlay != null
    }

    // ------------------------------------------------------------------
    // Quand le téléphone est libre
    // ------------------------------------------------------------------
    private fun canAct(): Boolean = !Store.paused && Store.actionsToday() < Store.DAILY_LIMIT

    private fun safePkg(pkg: String): Boolean {
        val p = pkg.lowercase()
        return BLOCKED.none { p.contains(it) }
    }

    private var wake: PowerManager.WakeLock? = null

    /** Allume l'écran et le garde allumé pendant le travail du bot. */
    private fun holdScreen(pm: PowerManager) {
        releaseScreen()
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "mercado:bot"
        )
        wl.acquire(15 * 60 * 1000L)
        wake = wl
        Thread.sleep(1500)
    }

    private fun releaseScreen() {
        val wl = wake
        if (wl != null && wl.isHeld) wl.release()
        wake = null
    }

    /** Attend que le téléphone soit libre (rien touché depuis 3 minutes), puis allume l'écran. */
    private fun waitForIdle(maxMs: Long): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            if (Store.paused) return false
            val idle = System.currentTimeMillis() - lastUserActivity >= IDLE_MS
            if ((idle || overlay != null) && !km.isKeyguardLocked) {
                holdScreen(pm)
                if (pm.isInteractive) return true
                releaseScreen()
            }
            Thread.sleep(15000)
        }
        return false
    }

    // ------------------------------------------------------------------
    // Réponses aux clients
    // ------------------------------------------------------------------
    private fun handleMessage(pkg: String, channel: String, title: String, text: String, n: Notification, history: String) {
        if (!canAct() || !safePkg(pkg)) return

        // Règle : jamais de prix. Le bot attend, c'est toi qui réponds.
        if (Brain.asksPrice(text)) {
            Store.addTask("À toi : $title demande le prix", text.take(90), "À toi")
            return
        }

        val tag = if (channel == "tiktok") "TikTok" else "Telegram"
        val taskId = Store.addTask("Répondre à $title", text.take(90), tag)

        try {
            if (!waitForIdle(20 * 60 * 1000L)) {
                Store.updateTask(taskId, false, "Téléphone utilisé trop longtemps : réponds toi-même")
                return
            }
            botBusy = true
            replyAndSend(pkg, channel, title, text, n, taskId, history)
        } catch (p: PauseStop) {
            Store.updateTask(taskId, false, "Interrompu par la Pause")
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun replyAndSend(
        pkg: String, channel: String, title: String, text: String,
        n: Notification, taskId: Long, history: String
    ) {
        var from = "bot"
        var reply: String? = Brain.reply(text)
        var fromAi = false
        if (reply == null) {
            from = "ia"
            reply = askChatGpt(
                (if (history.isNotBlank()) "Début de la conversation : ${history.takeLast(600)} " else "") +
                    "Un client de ma boutique en ligne écrit : \"${text.take(300)}\". " +
                    "Écris une courte réponse polie de vendeur, dans la langue du client. " +
                    "Ne donne jamais de prix."
            )
            fromAi = reply != null
        }
        val msg = Brain.noPrice(reply?.replace("\n", " ")?.trim() ?: "").takeIf { it.isNotBlank() } ?: run {
            Store.updateTask(taskId, false, "Aucune réponse trouvée (ChatGPT indisponible)")
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
    // Brouillon TikTok : intro + vidéo CJ + texte + légende, puis "Drafts"
    // ------------------------------------------------------------------
    private fun postDraft(taskId: Long) {
        try {
            botBusy = true
            holdScreen(getSystemService(Context.POWER_SERVICE) as PowerManager)
            Store.updateTask(taskId, false, "Publication en cours")
            postNote = ""
            val err = saveCjVideo() ?: doPostDraft()
            Store.record("tiktok|draft", err == null)
            if (err == null) {
                Store.countAction()
                Store.updateTask(taskId, true, "Brouillon enregistré ($postNote)")
            } else {
                Store.updateTask(taskId, false, "Échec : $err")
            }
        } catch (p: PauseStop) {
            Store.updateTask(taskId, false, "Interrompu par la Pause")
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** Retourne null si tout s'est bien passé, sinon le nom de l'étape qui a échoué. */
    private fun doPostDraft(): String? {
        val pkg = tiktokPkg() ?: return "TikTok introuvable"

        // 1. Les textes (ChatGPT, sinon textes par défaut). Jamais de prix.
        val raw = askChatGpt(
            "You write TikTok text for a UK online shop called MERCADO. " +
                "Reply with exactly two parts separated by ||. " +
                "Part 1: on-screen text, 6 words maximum. " +
                "Part 2: caption, 100 characters maximum, with 4 relevant hashtags. " +
                "Never mention prices. No delivery time claims and no limited stock claims."
        )
        val parsed = Brain.parsePost(raw)
        val overlay = Brain.noPrice(parsed.first)
        val caption = Brain.noPrice(parsed.second)

        // 2. Ouvrir TikTok et compter les brouillons avant.
        if (!launch(pkg) || !waitFor(10000) { fg() == pkg }) return postFail("ouverture de TikTok")
        Thread.sleep(Store.settleMs(pkg))
        if (!goProfile(pkg)) return postFail("profil introuvable")
        val before = draftsCount() ?: 0

        // 3. Bouton + puis galerie.
        val plus = findText("Create") ?: findByRegion(0.42, 0.58, 0.92, 0.99) ?: return postFail("bouton + introuvable")
        if (!clickUntil(plus, 7000) { findText("Add sound") != null }) return postFail("écran caméra")

        val thumb = findByRegion(0.03, 0.19, 0.85, 0.93) ?: return postFail("miniature de la galerie")
        if (!clickUntil(thumb, 7000) { findText("Select multiple") != null || findText("Recents") != null }) {
            return postFail("galerie")
        }
        if (!clickText(4000, "Videos")) return postFail("onglet Videos")
        Thread.sleep(1500)
        val sm = waitNode(5000) { findText("Select multiple") } ?: return postFail("Select multiple")
        if (!isCheckedChain(sm)) click(sm)
        Thread.sleep(800)

        // 4. L'intro (4 secondes) d'abord, puis la vidéo CJ la plus récente.
        val intro = durationTiles().firstOrNull { it.first == "00:04" }
            ?: return postFail("intro de 4 secondes introuvable")
        click(intro.second)
        Thread.sleep(900)
        val cj = durationTiles().firstOrNull { it.first != "00:04" }
            ?: return postFail("vidéo CJ introuvable")
        click(cj.second)
        Thread.sleep(900)
        val next = waitNode(5000) { findStartsWith("Next") } ?: return postFail("bouton Next")
        click(next)
        if (!waitFor(25000) { findText("Sound") != null && findText("Text") != null }) {
            return postFail("éditeur")
        }

        // 5. Texte à l'écran (si ça échoue, on continue : c'est juste un brouillon).
        var textOk = false
        if (clickText(3000, "Text")) {
            val ed = waitNode(5000) { editables().firstOrNull() }
            if (ed != null && setText(ed, overlay)) {
                Thread.sleep(600)
                textOk = clickText(4000, "Done")
            }
        }
        Thread.sleep(1000)

        // 6. Flèche rouge en haut à droite : écran de publication.
        val arrow = findByRegion(0.82, 0.98, 0.03, 0.10) ?: return postFail("flèche suivante")
        if (!clickUntil(arrow, 12000) { findText("Drafts") != null && findText("Post") != null }) {
            return postFail("écran de publication")
        }

        // 7. Légende.
        var capOk = false
        val capNode = editables().maxByOrNull { val b = Rect(); it.getBoundsInScreen(b); b.height() }
        if (capNode != null) {
            capOk = setText(capNode, caption)
            Thread.sleep(800)
        }
        postNote = "texte à l'écran : " + (if (textOk) "oui" else "non") +
            ", légende : " + (if (capOk) "oui" else "non")

        // 8. Drafts, jamais Post.
        val drafts = findText("Drafts") ?: return postFail("bouton Drafts")
        if (!clickNarrow(drafts, 0.7)) return postFail("clic sur Drafts")
        Thread.sleep(5000)
        if (findText("Drafts") != null && findText("Post") != null) {
            val d2 = findText("Drafts")
            if (d2 != null) tapNode(d2)
            Thread.sleep(4000)
        }

        // 9. Vérification : le nombre de brouillons doit avoir augmenté.
        if (!goProfile(pkg)) return postFail("retour au profil")
        if (!waitFor(20000) { (draftsCount() ?: 0) > before }) {
            return postFail("brouillon non confirmé (avant : $before)")
        }
        return null
    }

    // ------------------------------------------------------------------
    // Appli CJ : produit avec beaucoup de "Lists", appui long sur la vidéo, "Save video to album?" > OK
    // ------------------------------------------------------------------
    private fun cjPkg(): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .firstOrNull { it.loadLabel(packageManager).toString().contains("CJdrop", ignoreCase = true) }
            ?.activityInfo?.packageName
    }

    private fun listsCounts(): List<Pair<Long, AccessibilityNodeInfo>> {
        val re = Regex("(\\d[\\d,]*)\\s*Lists", RegexOption.IGNORE_CASE)
        val out = ArrayList<Pair<Long, AccessibilityNodeInfo>>()
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val t = n.text?.toString() ?: continue
            val m = re.find(t) ?: continue
            val v = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            out.add(Pair(v, n))
        }
        return out.sortedByDescending { it.first }
    }

    /** Retourne null si la vidéo est enregistrée dans la galerie, sinon l'étape qui a échoué. */
    private fun saveCjVideo(): String? {
        val pkg = cjPkg() ?: return cjFail("appli CJ introuvable")
        if (!launch(pkg) || !waitFor(12000) { fg() == pkg }) return cjFail("ouverture de CJ")
        Thread.sleep(Store.settleMs(pkg) + 1500)

        for (i in 0 until 4) {
            if (fg() != pkg) {
                launch(pkg)
                waitFor(8000) { fg() == pkg }
                Thread.sleep(2000)
            }
            if (findText("Products") != null) break
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(1200)
        }
        if (!clickText(6000, "Products")) return cjFail("onglet Products")
        if (!waitFor(12000) { listsCounts().isNotEmpty() }) return cjFail("liste de produits")
        Thread.sleep(1500)

        val tried = ArrayList<Long>()
        for (round in 0 until 3) {
            val cand = listsCounts().firstOrNull { it.first !in tried } ?: break
            tried.add(cand.first)
            click(cand.second)
            val onPage = waitFor(12000) { findText("Buy Sample") != null || findText("Video Gallery") != null }
            if (onPage) Thread.sleep(2500)
            val pressed = onPage && longPressVideo()
            if (pressed && clickText(4000, "OK")) {
                Thread.sleep(10000)
                Store.record("cj|save", true)
                return null
            }
            if (round == 0) {
                val why = if (!onPage) "page produit non reconnue" else if (!pressed) "appui long sans effet" else "bouton OK introuvable"
                val dd = Store.addTask("Diagnostic CJ : $why", dumpScreen(), "Problème")
                Store.updateTask(dd, true)
            }
            Store.record("cj|save", false)
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(1500)
            if (!waitFor(8000) { listsCounts().isNotEmpty() }) break
        }
        return cjFail("vidéo non enregistrée")
    }

    /** Appui long sur la vidéo du produit : d'abord par l'accessibilité, sinon par un geste. */
    private fun longPressVideo(): Boolean {
        if (Store.paused) throw PauseStop()
        val node = findByRegion(0.35, 0.65, 0.22, 0.32)
        if (node != null) {
            var cur: AccessibilityNodeInfo? = node
            var i = 0
            while (cur != null && i < 6) {
                if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) break
                cur = cur.parent
                i++
            }
            if (waitFor(2500) { findStartsWith("Save video") != null }) return true
        }
        val dm = resources.displayMetrics
        longPressAt(dm.widthPixels * 0.5f, dm.heightPixels * 0.27f)
        return waitFor(3000) { findStartsWith("Save video") != null }
    }

    private fun longPressAt(x: Float, y: Float) {
        if (Store.paused) throw PauseStop()
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1200L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        overlayBlocksTouches(false)
        try {
            Thread.sleep(300)
            val sent = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (sent) latch.await(4, TimeUnit.SECONDS)
        } finally {
            overlayBlocksTouches(true)
        }
    }

    /** Touche l'écran à cet endroit avec un vrai geste (quand le clic d'accessibilité ne marche pas). */
    private fun tapAt(x: Float, y: Float) {
        if (Store.paused) throw PauseStop()
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        overlayBlocksTouches(false)
        try {
            Thread.sleep(300)
            val sent = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (sent) latch.await(3, TimeUnit.SECONDS)
        } finally {
            overlayBlocksTouches(true)
        }
    }

    private fun tapNode(n: AccessibilityNodeInfo) {
        val b = Rect()
        n.getBoundsInScreen(b)
        tapAt(b.centerX().toFloat(), b.centerY().toFloat())
    }

    /** Clic d'accessibilité, puis si rien ne change : vrai toucher au même endroit. */
    private fun clickUntil(node: AccessibilityNodeInfo, ms: Long, cond: () -> Boolean): Boolean {
        click(node)
        if (waitFor(ms, cond)) return true
        tapNode(node)
        return waitFor(ms, cond)
    }

    private fun cjFail(step: String): String {
        val d = Store.addTask("Diagnostic CJ : $step", dumpScreen(), "Problème")
        Store.updateTask(d, true)
        return "CJ : $step"
    }

    // ------------------------------------------------------------------
    // Heures du bot : du lundi au vendredi, de 19:00 à 21:30, le bot prend le téléphone
    // ------------------------------------------------------------------
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var overlay: FrameLayout? = null
    private var overlayStatus: TextView? = null

    @Volatile
    private var emergencyUntil = 0L

    private var lastTickError = 0L

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                val now = System.currentTimeMillis()
                if (now - lastTickError > 10 * 60 * 1000L) {
                    lastTickError = now
                    Store.addTask("Problème couverture", t.message ?: t.javaClass.simpleName, "Problème")
                }
            }
            main.postDelayed(this, 5000)
        }
    }

    private fun inLockWindow(): Boolean {
        val c = Calendar.getInstance()
        val d = c.get(Calendar.DAY_OF_WEEK)
        if (d == Calendar.SATURDAY || d == Calendar.SUNDAY) return false
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m >= LOCK_START && m < LOCK_END
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val inWindow = inLockWindow()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inCall = am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_RINGTONE ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION
        val want = inWindow && !Store.paused && now >= emergencyUntil && !inCall
        if (want && overlay == null) showOverlay()
        if (!want && overlay != null) hideOverlay()
        overlayStatus?.text = statusText()
        if (draftPending && Store.paused) {
            draftPending = false
            Store.updateTask(draftTaskId, false, "Annulé : Pause activée")
        }
        if (draftPending && !botBusy && canStartNow()) {
            draftPending = false
            val id = draftTaskId
            runJob { postDraft(id) }
        }
        if (inWindow && !Store.paused) startDailyDraft()
    }

    /** Un brouillon TikTok par jour d'ouverture, au début des heures du bot. */
    private fun startDailyDraft() {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val p = getSharedPreferences("mercado_sched", Context.MODE_PRIVATE)
        if (p.getString("draftDay", "") == day) return
        p.edit().putString("draftDay", day).apply()
        postNow()
    }

    private fun statusText(): String {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val left = LOCK_END - m
        val back = String.format(Locale.US, "%02d:%02d", LOCK_END / 60, LOCK_END % 60)
        val last = Store.tasks().optJSONObject(0)?.optString("title") ?: ""
        return "Ton téléphone revient à $back (dans $left min)." +
            (if (last.isNotBlank()) "\nDernière tâche : $last" else "")
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#F2151A3D"))
        root.isClickable = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER

        val title = TextView(this)
        title.text = "MERCADO Bot travaille"
        title.setTextColor(Color.parseColor("#F2B100"))
        title.textSize = 28f
        title.gravity = Gravity.CENTER

        val status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 18f
        status.gravity = Gravity.CENTER
        status.setPadding(48, 32, 48, 64)
        status.text = statusText()

        val urgence = TextView(this)
        urgence.text = "URGENCE : reste appuyé 10 secondes"
        urgence.setTextColor(Color.WHITE)
        urgence.textSize = 16f
        urgence.gravity = Gravity.CENTER
        urgence.setPadding(40, 32, 40, 32)
        urgence.setBackgroundColor(Color.parseColor("#C42A39"))

        val release = Runnable {
            emergencyUntil = System.currentTimeMillis() + 3 * 60 * 1000L
            hideOverlay()
        }
        urgence.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> main.postDelayed(release, 10000L)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(release)
            }
            true
        }

        col.addView(title)
        col.addView(status)
        col.addView(urgence)
        root.addView(
            col,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        lp.screenBrightness = 0.1f
        wm.addView(root, lp)
        overlay = root
        overlayStatus = status
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) { }
        overlay = null
        overlayStatus = null
    }

    /** Laisse passer un geste du bot à travers la couverture (block = false), puis la referme. */
    private fun overlayBlocksTouches(block: Boolean) {
        main.post {
            val v = overlay ?: return@post
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@post
            lp.flags = if (block) {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp)
            } catch (e: Exception) { }
        }
    }

    private fun tiktokPkg(): String? =
        WATCHED.filter { it.value == "tiktok" }.keys
            .firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }

    private fun goProfile(pkg: String): Boolean {
        for (i in 0 until 6) {
            if (fg() != pkg) {
                launch(pkg)
                waitFor(6000) { fg() == pkg }
            }
            val p = findText("Profile")
            if (p != null) {
                click(p)
                Thread.sleep(1500)
                if (waitFor(6000) { findText("TikTok Studio") != null || draftsCount() != null }) return true
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(1200)
        }
        return false
    }

    private fun draftsCount(): Int? {
        val re = Regex("Drafts:\\s*(\\d+)")
        for (n in allNodes()) {
            val t = n.text?.toString() ?: continue
            val m = re.find(t)
            if (m != null) return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun postFail(step: String): String {
        val d = Store.addTask("Diagnostic TikTok : $step", dumpScreen(), "Problème")
        Store.updateTask(d, true)
        return step
    }

    private fun dumpScreen(): String =
        allNodes()
            .filter { it.isVisibleToUser }
            .mapNotNull { n -> (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
            .joinToString(" | ")
            .take(900)

    private fun durationTiles(): List<Pair<String, AccessibilityNodeInfo>> {
        val re = Regex("\\d{1,2}:\\d{2}")
        val out = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        for (n in allNodes()) {
            val t = n.text?.toString()?.trim() ?: continue
            if (n.isVisibleToUser && re.matches(t)) out.add(Pair(t, n))
        }
        return out.sortedWith(
            compareBy<Pair<String, AccessibilityNodeInfo>>(
                { centerOf(it.second).second / 60 },
                { centerOf(it.second).first }
            )
        )
    }

    private fun centerOf(n: AccessibilityNodeInfo): Pair<Int, Int> {
        val b = Rect()
        n.getBoundsInScreen(b)
        return Pair(b.centerX(), b.centerY())
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

    private fun findText(vararg words: String): AccessibilityNodeInfo? =
        allNodes().firstOrNull { n ->
            n.isVisibleToUser && words.any { w ->
                n.text?.toString()?.trim().equals(w, ignoreCase = true) ||
                    n.contentDescription?.toString()?.trim().equals(w, ignoreCase = true)
            }
        }

    private fun findStartsWith(prefix: String): AccessibilityNodeInfo? =
        allNodes().firstOrNull { n ->
            n.isVisibleToUser &&
                (n.text?.toString()?.trim()?.startsWith(prefix, ignoreCase = true) == true)
        }

    private fun clickText(ms: Long, vararg words: String): Boolean {
        val n = waitNode(ms) { findText(*words) } ?: return false
        return click(n)
    }

    /** Le plus petit élément visible dont le centre est dans cette zone de l'écran (en fractions). */
    private fun findByRegion(x0: Double, x1: Double, y0: Double, y1: Double): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toDouble()
        val h = dm.heightPixels.toDouble()
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val b = Rect()
            n.getBoundsInScreen(b)
            val area = b.width().toLong() * b.height().toLong()
            if (area <= 0L) continue
            val cx = b.centerX() / w
            val cy = b.centerY() / h
            if (cx in x0..x1 && cy in y0..y1 && area < bestArea) {
                best = n
                bestArea = area
            }
        }
        return best
    }

    private fun isCheckedChain(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var i = 0
        while (cur != null && i < 3) {
            if (cur.isChecked) return true
            cur = cur.parent
            i++
        }
        return false
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (Store.paused) throw PauseStop()
        var cur: AccessibilityNodeInfo? = node
        var i = 0
        while (cur != null && i < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            i++
        }
        return false
    }

    /** Comme click, mais refuse de remonter jusqu'à un conteneur large (évite de toucher le mauvais bouton). */
    private fun clickNarrow(node: AccessibilityNodeInfo, maxFrac: Double): Boolean {
        if (Store.paused) throw PauseStop()
        val maxWidth = resources.displayMetrics.widthPixels * maxFrac
        var cur: AccessibilityNodeInfo? = node
        var i = 0
        while (cur != null && i < 4) {
            val b = Rect()
            cur.getBoundsInScreen(b)
            if (b.width() > maxWidth) return false
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            i++
        }
        return false
    }

    private fun editables(): List<AccessibilityNodeInfo> =
        allNodes().filter { it.isEditable && it.isVisibleToUser }

    private fun findEditable(): AccessibilityNodeInfo? =
        editables().maxByOrNull { val b = Rect(); it.getBoundsInScreen(b); b.top }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (Store.paused) throw PauseStop()
        if (node.isPassword) return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

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
        if (!setText(edit, text)) return false
        Thread.sleep(600)
        val cur = findEditable()?.text?.toString() ?: ""
        if (!cur.startsWith(text.take(10))) return false
        return pressSend(pkg)
    }

    /** Liste les boutons visibles, pour comprendre pourquoi l'envoi a échoué. */
    private fun dumpClickables(): String =
        allNodes()
            .filter { it.isClickable && it.isVisibleToUser }
            .joinToString(" | ") { n ->
                val b = Rect()
                n.getBoundsInScreen(b)
                "d=" + (n.contentDescription ?: "") + ",t=" + (n.text ?: "") +
                    ",id=" + (n.viewIdResourceName ?: "") + ",x=" + b.centerX() + ",y=" + b.centerY()
            }
            .take(900)

    /** Essaie plusieurs façons d'appuyer sur Envoyer, la plus réussie d'abord (apprentissage). */
    private fun pressSend(pkg: String): Boolean {
        val order = listOf("desc", "text", "id", "has", "right", "ime")
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
        val d = Store.addTask("Diagnostic envoi ($pkg)", dumpClickables(), "Problème")
        Store.updateTask(d, true)
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
            "has" -> nodes.firstOrNull { n ->
                val d = ((n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "")).lowercase()
                n.isVisibleToUser && !n.isEditable && (d.contains("send") || d.contains("envoy"))
            }
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
                n != e && n.isVisibleToUser &&
                    b.centerX() > eb.centerX() && b.centerY() in eb.top..eb.bottom &&
                    b.width() < eb.width() / 2
            }
            .maxByOrNull { val b = Rect(); it.getBoundsInScreen(b); b.right }
    }
}
