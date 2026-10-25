package com.mercado.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * Allume l'écran quand le téléphone est éteint.
 *
 * - Écran éteint: allumage avec un wake lock.
 * - Écran de verrouillage SANS code (simple glissement): le bot glisse vers le haut.
 * - Écran de verrouillage AVEC code/schéma/empreinte: le bot ne peut pas déverrouiller
 *   (retourne false). Voir les options dans la réponse.
 *
 * Manifest: <uses-permission android:name="android.permission.WAKE_LOCK" />
 * Config accessibilité (xml): android:canPerformGestures="true"
 */
object ScreenWaker {

    /** true si l'écran est allumé et déverrouillé, prêt pour le bot. */
    @Suppress("DEPRECATION")
    suspend fun ensureAwake(service: AccessibilityService): Boolean {
        val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (!pm.isInteractive) {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "mercado:wake"
            )
            wl.acquire(10_000)
            delay(1_500)
        }

        if (km.isKeyguardLocked) {
            if (km.isKeyguardSecure) return false // code, schéma ou empreinte
            swipeUp(service)
            delay(1_000)
        }

        return pm.isInteractive && !km.isKeyguardLocked
    }

    /** Éteint l'écran (Android 9+). À utiliser quand le bot a fini toutes ses tâches. */
    fun lockScreen(service: AccessibilityService) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    private fun swipeUp(service: AccessibilityService) {
        val m = service.resources.displayMetrics
        val x = m.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, m.heightPixels * 0.85f)
            lineTo(x, m.heightPixels * 0.25f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        service.dispatchGesture(gesture, null, null)
    }
}

/*
 * Branchement dans ton service d'accessibilité:
 *
 * val scheduler = TaskScheduler(
 *     scope = serviceScope,
 *     store = store,
 *     isPhoneFree = { PhoneState.isFree() },
 *     onStatus = { id, title, status -> dashboard.update(id, title, status) },
 *     prepareScreen = {
 *         if (!ScreenWaker.ensureAwake(this@TonService)) {
 *             error("Téléphone verrouillé par un code")
 *         }
 *     },
 *     parallel = 2
 * )
 */
