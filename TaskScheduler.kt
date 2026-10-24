package com.mercado.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Une tâche du bot (ex: "Brouillon TikTok").
 * Dans run(), utilise:
 *  - ctx.withScreen { ... }  pour tout ce qui touche l'écran (un seul à la fois)
 *  - ctx.waitFor(...)        pour attendre SANS bloquer l'écran
 */
abstract class BotTask(val id: String, val title: String) {
    abstract suspend fun run(ctx: TaskContext)
}

class TaskContext internal constructor(
    private val scheduler: TaskScheduler,
    val task: BotTask
) {
    fun status(text: String) = scheduler.report(task, text)

    /** Réserve l'écran. Les autres tâches attendent ou travaillent hors écran. */
    suspend fun <T> withScreen(block: suspend () -> T): T = scheduler.useScreen(task, block)

    /**
     * Attend qu'une condition soit remplie (ex: vidéo téléchargée) sans tenir l'écran.
     * Retourne null si le délai est dépassé.
     */
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
    /** true quand tu n'utilises pas le téléphone (voir détection d'inactivité) */
    private val isPhoneFree: suspend () -> Boolean,
    /** pour mettre à jour le dashboard */
    private val onStatus: (taskId: String, title: String, status: String) -> Unit,
    private val parallel: Int = 2,
    private val maxAttempts: Int = 3
) {
    private val queue = Channel<BotTask>(Channel.UNLIMITED)
    private val screenLock = Mutex()

    fun start() {
        repeat(parallel) {
            scope.launch(Dispatchers.Default) {
                for (task in queue) runWithRetry(task)
            }
        }
    }

    fun submit(task: BotTask) {
        report(task, "En file")
        queue.trySend(task)
    }

    internal fun report(task: BotTask, text: String) = onStatus(task.id, task.title, text)

    internal suspend fun <T> useScreen(task: BotTask, block: suspend () -> T): T {
        report(task, "Attend l'écran")
        while (true) {
            while (!isPhoneFree()) delay(5_000)
            val got = screenLock.withLock {
                if (!isPhoneFree()) {
                    null
                } else {
                    report(task, "Utilise l'écran")
                    Result.success(block())
                }
            }
            if (got != null) return got.getOrThrow()
        }
    }

    private suspend fun runWithRetry(task: BotTask) {
        var lastError = "inconnue"
        for (attempt in 1..maxAttempts) {
            try {
                task.run(TaskContext(this, task))
                report(task, "Terminé")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                report(task, "Échec (essai $attempt/$maxAttempts) : $lastError")
                if (attempt < maxAttempts) delay(30_000L * attempt)
            }
        }
        report(task, "Échec : $lastError")
    }
}
