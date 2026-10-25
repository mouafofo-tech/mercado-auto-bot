package com.mercado.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Lancée quand tu reprends le téléphone pendant que le bot utilise l'écran. */
class PhoneBusyException : Exception("Téléphone utilisé")

/**
 * Une tâche du bot (ex: "Brouillon TikTok").
 * Dans run():
 *  - ctx.step("nom") { ... }  : étape mémorisée, jamais refaite si déjà terminée
 *  - ctx.withScreen { ... }   : tout ce qui touche l'écran (un seul à la fois, interrompu si tu touches le téléphone)
 *  - ctx.waitFor(...)         : attendre SANS bloquer l'écran
 *  - ctx.put / get            : données mémorisées (produit, heure, vidéo...)
 */
abstract class BotTask(val id: String, val title: String) {
    abstract suspend fun run(ctx: TaskContext)
}

class TaskContext internal constructor(
    private val scheduler: TaskScheduler,
    private val store: TaskStore,
    val task: BotTask
) {
    fun status(text: String) = scheduler.report(task, text)

    /** Exécute l'étape seulement si elle n'est pas déjà faite, puis la marque comme faite. */
    suspend fun step(name: String, block: suspend () -> Unit) {
        if (store.isDone(task.id, name)) return
        status("Étape : $name")
        block()
        store.markDone(task.id, name)
    }

    fun put(key: String, value: String) = store.put(task.id, key, value)
    fun get(key: String): String? = store.get(task.id, key)
    fun putIfAbsent(key: String, value: String) {
        if (store.get(task.id, key) == null) store.put(task.id, key, value)
    }

    suspend fun <T> withScreen(block: suspend () -> T): T = scheduler.useScreen(task, block)

    suspend fun <T : Any> waitFor(
        reason: String,
        timeoutMs: Long,
        pollMs: Long = 2_000,
        check: suspend () -> T?
    ): T? {
        status("Attend : $reason")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            check()?.let { return it }
            delay(pollMs)
        }
        return null
    }
}

class TaskScheduler(
    private val scope: CoroutineScope,
    private val store: TaskStore,
    /** true quand tu n'utilises pas le téléphone. Doit ignorer les gestes du bot lui-même. */
    private val isPhoneFree: suspend () -> Boolean,
    private val onStatus: (taskId: String, title: String, status: String) -> Unit,
    /** Appelé juste avant chaque bloc écran: allume le téléphone s'il est éteint (voir ScreenWaker). */
    private val prepareScreen: suspend () -> Unit = {},
    /** Message "Wait", prise du téléphone, puis retour à toi (voir PhoneHandover). */
    private val handover: PhoneHandover? = null,
    private val parallel: Int = 2,
    private val maxAttempts: Int = 3
) {
    private val queue = Channel<BotTask>(Channel.UNLIMITED)
    private val screenLock = Mutex()

    private class Box<T>(val value: T)

    fun start() {
        repeat(parallel) {
            scope.launch(Dispatchers.Default) {
                for (task in queue) runWithRetry(task)
            }
        }
    }

    fun submit(task: BotTask) {
        store.begin(task.id)
        report(task, "En file")
        queue.trySend(task)
    }

    internal fun report(task: BotTask, text: String) = onStatus(task.id, task.title, text)

    internal suspend fun <T> useScreen(task: BotTask, block: suspend () -> T): T {
        report(task, "Attend l'écran")
        return screenLock.withLock {
            if (handover != null) {
                report(task, "Wait : demande le téléphone")
                handover.acquire(isPhoneFree)
            } else {
                while (!isPhoneFree()) delay(5_000)
            }
            try {
                report(task, "Utilise l'écran")
                prepareScreen()
                runWatched(block)
            } finally {
                handover?.release()
            }
        }
    }

    /** Le bot a-t-il encore le droit d'utiliser l'écran ? */
    private suspend fun phoneOk(): Boolean =
        handover?.allowsBot(isPhoneFree) ?: isPhoneFree()

    /** Exécute block, et l'arrête dès que tu touches le téléphone. */
    private suspend fun <T> runWatched(block: suspend () -> T): T {
        val interrupted = AtomicBoolean(false)
        try {
            return coroutineScope {
                val work = async { block() }
                val watcher = launch {
                    while (true) {
                        delay(1_000)
                        if (!phoneOk()) {
                            interrupted.set(true)
                            work.cancel()
                            break
                        }
                    }
                }
                try {
                    work.await()
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (interrupted.get()) throw PhoneBusyException()
            throw e
        }
    }

    private suspend fun runWithRetry(task: BotTask) {
        var attempt = 1
        var lastError = "inconnue"
        while (attempt <= maxAttempts) {
            try {
                task.run(TaskContext(this, store, task))
                store.finish(task.id)
                report(task, "Terminé")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhoneBusyException) {
                // Pas un échec: on garde la mémoire et on reprend quand le téléphone est libre.
                report(task, "En pause : tu utilises le téléphone. Reprendra où il en était")
                delay(2_000)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                report(task, "Échec (essai $attempt/$maxAttempts) : $lastError")
                attempt++
                if (attempt <= maxAttempts) delay(30_000L * attempt)
            }
        }
        store.finish(task.id)
        report(task, "Échec : $lastError")
    }
}
