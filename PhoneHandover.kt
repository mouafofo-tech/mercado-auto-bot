package com.mercado.bot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * Passage du téléphone entre toi et le bot.
 *
 * 1. Le bot veut travailler et tu utilises le téléphone:
 *    message "Wait" avec compte à rebours (bouton "Plus tard" = 10 min, sauf dans la fenêtre lundi-vendredi).
 * 2. Le bot prend le téléphone: écran "MERCADO Bot travaille" (touches bloquées si blockTouches = true).
 * 3. Quand il n'a plus besoin de l'écran (ex: il attend un téléchargement), le téléphone te revient
 *    après giveBackAfterMs, avec le message "Terminé".
 *
 * Sécurité: le bouton "Reprendre mon téléphone" est toujours visible. Il arrête le bot 30 min.
 * Aucune permission en plus: l'overlay utilise le service d'accessibilité.
 */
class PhoneHandover(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val askSeconds: Int = 15,
    private val giveBackAfterMs: Long = 15_000,
    /** true: écran plein qui bloque tes touches. false: simple bandeau, tes touches passent (et arrêtent le bot). */
    private val blockTouches: Boolean = true,
    /** true pendant lundi-vendredi 19:00-21:30: le bot prend le téléphone après 5 s, sans "Plus tard". */
    private val isTakeoverWindow: () -> Boolean = { false }
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var current: View? = null
    private var countdownLabel: TextView? = null
    private var giveBackJob: Job? = null

    @Volatile
    var active = false
        private set

    @Volatile
    private var stopRequested = false

    @Volatile
    private var snoozeUntil = 0L

    // ---------- appelé par le scheduler ----------

    /** Attend d'avoir le téléphone (en le demandant si tu l'utilises). */
    suspend fun acquire(isFree: suspend () -> Boolean) {
        giveBackJob?.cancel()
        giveBackJob = null

        if (active) {
            if (blockTouches || isFree()) return
            // tu as repris le téléphone en mode bandeau: on arrête la session
            active = false
            ui { hide(current) }
        }

        while (true) {
            if (System.currentTimeMillis() < snoozeUntil) {
                delay(5_000)
                continue
            }
            if (isFree()) {
                startSession()
                return
            }
            if (askUser()) {
                startSession()
                return
            }
            snoozeUntil = System.currentTimeMillis() + 10 * 60_000L // "Plus tard"
        }
    }

    /** Le bot a fini son bloc écran: si personne d'autre n'en a besoin, le téléphone te revient. */
    fun release() {
        giveBackJob?.cancel()
        giveBackJob = scope.launch {
            delay(giveBackAfterMs)
            endSession()
        }
    }

    /** Le bot peut-il continuer à utiliser l'écran ? */
    suspend fun allowsBot(isFree: suspend () -> Boolean): Boolean {
        if (stopRequested) return false
        return if (active && blockTouches) true else isFree()
    }

    // ---------- sessions ----------

    private fun startSession() {
        stopRequested = false
        active = true
        ui { showWorking() }
    }

    private fun endSession() {
        if (!active) return
        active = false
        ui { showDone() }
    }

    private fun stop() {
        stopRequested = true
        snoozeUntil = System.currentTimeMillis() + 30 * 60_000L
        active = false
        hide(current)
    }

    /** Affiche "Wait" avec compte à rebours. true = le bot prend le téléphone, false = "Plus tard". */
    private suspend fun askUser(): Boolean {
        val canSnooze = !isTakeoverWindow()
        val seconds = if (canSnooze) askSeconds else 5
        val answer = CompletableDeferred<Boolean>()
        ui { showWait(canSnooze) { answer.complete(false) } }

        for (s in seconds downTo 1) {
            ui { countdownLabel?.text = "⏳ Wait… MERCADO Bot a une tâche.\nIl prend le téléphone dans $s s" }
            val r = withTimeoutOrNull(1_000) { answer.await() }
            if (r != null) {
                ui { hide(current) }
                return r
            }
        }
        ui { hide(current) }
        return true
    }

    // ---------- écrans (thread principal) ----------

    private fun showWait(canSnooze: Boolean, onLater: () -> Unit) {
        val box = banner(Color.parseColor("#F5B820"))
        val text = label("", 16f, Color.parseColor("#0F1235"))
        countdownLabel = text
        box.addView(text)
        if (canSnooze) box.addView(button("Plus tard (10 min)") { onLater() })
        show(box, bannerParams())
    }

    private fun showWorking() {
        if (blockTouches) {
            val box = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(240, 15, 18, 53))
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setOnTouchListener { _, _ -> true } // avale tes touches
            }
            box.addView(label("🤖", 56f))
            box.addView(label("MERCADO Bot travaille", 24f))
            box.addView(label("Attends, je te rends le téléphone dès que j'ai fini.", 16f))
            box.addView(button("Reprendre mon téléphone") { stop() })
            show(box, fullParams())
        } else {
            val box = banner(Color.parseColor("#F5B820"))
            box.addView(label("🤖 MERCADO Bot travaille…", 14f, Color.parseColor("#0F1235")))
            box.addView(button("Reprendre") { stop() })
            show(box, bannerParams())
        }
    }

    private fun showDone() {
        val box = banner(Color.parseColor("#3DDC97"))
        box.addView(label("✅ Terminé. Le téléphone est à toi.", 16f, Color.parseColor("#0F1235")))
        show(box, bannerParams())
        main.postDelayed({ hide(box) }, 3_000)
    }

    // ---------- outils ----------

    private fun show(v: View, params: WindowManager.LayoutParams) {
        current?.let { runCatching { wm.removeView(it) } }
        wm.addView(v, params)
        current = v
    }

    private fun hide(v: View?) {
        if (v == null || current !== v) return
        runCatching { wm.removeView(v) }
        current = null
        countdownLabel = null
    }

    private fun ui(block: () -> Unit) {
        main.post { runCatching(block) }
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private fun banner(bg: Int) = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(bg)
        setPadding(dp(16), dp(36), dp(16), dp(16))
    }

    private fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(service).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(service).apply {
        this.text = text
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun bannerParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP }

    private fun fullParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )
}

/** Fenêtre lundi-vendredi 19:00-21:30. Si tu as déjà cette fonction dans ton bot, utilise la tienne. */
fun isMercadoWindow(): Boolean {
    val c = Calendar.getInstance()
    val day = c.get(Calendar.DAY_OF_WEEK)
    if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false
    val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    return minutes in (19 * 60) until (21 * 60 + 30)
}

/*
 * Branchement dans ton service d'accessibilité:
 *
 * val handover = PhoneHandover(
 *     service = this@TonService,
 *     scope = serviceScope,
 *     askSeconds = 15,
 *     giveBackAfterMs = 15_000,
 *     blockTouches = true,
 *     isTakeoverWindow = { isMercadoWindow() }
 * )
 *
 * val scheduler = TaskScheduler(
 *     scope = serviceScope,
 *     store = store,
 *     isPhoneFree = { PhoneState.isFree() },
 *     onStatus = { id, title, status -> dashboard.update(id, title, status) },
 *     prepareScreen = { if (!ScreenWaker.ensureAwake(this@TonService)) error("Téléphone verrouillé par un code") },
 *     handover = handover,
 *     parallel = 2
 * )
 */
