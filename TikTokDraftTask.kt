package com.mercado.bot

import android.content.Context
import android.net.Uri

/** Branche ici tes fonctions existantes (accessibilité) */
interface CjActions {
    /** Ouvre l'appli CJ, choisit un produit, appui long sur la vidéo, clique OK. Revient dès que le téléchargement est lancé. */
    suspend fun openProductAndSaveVideo()
}

interface TikTokActions {
    /** Ouvre TikTok, importe la vidéo (+ intro MERCADO), texte, musique, enregistre en brouillon. */
    suspend fun addIntroAndSaveDraft(video: Uri)
}

class TikTokDraftTask(
    private val context: Context,
    id: String,
    private val cj: CjActions,
    private val tiktok: TikTokActions
) : BotTask(id, "Brouillon TikTok") {

    override suspend fun run(ctx: TaskContext) {
        val startedAt = System.currentTimeMillis()

        // 1) Écran: lance le téléchargement dans l'appli CJ, puis LIBÈRE l'écran
        ctx.withScreen {
            cj.openProductAndSaveVideo()
        }

        // 2) Hors écran: attend la vidéo (jusqu'à 5 min). Pendant ce temps,
        //    l'autre tâche peut prendre l'écran.
        val video = ctx.waitFor(
            reason = "téléchargement CJ",
            timeoutMs = 5 * 60_000L
        ) { VideoWaiter.newestSince(context, startedAt) }
            ?: error("Vidéo CJ non reçue dans la galerie")

        // 3) Écran: reprend avec la vidéo la plus récente
        //    (rouvre TikTok toi-même au début: l'autre tâche a pu changer d'appli)
        ctx.withScreen {
            tiktok.addIntroAndSaveDraft(video)
        }
    }
}

/*
 * Démarrage (dans ton service du bot):
 *
 * val scheduler = TaskScheduler(
 *     scope = serviceScope,
 *     isPhoneFree = { PhoneState.isFree() },   // inactivité 3-5 min ou écran éteint
 *     onStatus = { id, title, status -> dashboard.update(id, title, status) },
 *     parallel = 2
 * )
 * scheduler.start()
 * scheduler.submit(TikTokDraftTask(applicationContext, "draft-1", cjActions, tiktokActions))
 * scheduler.submit(TikTokDraftTask(applicationContext, "draft-2", cjActions, tiktokActions))
 *
 * build.gradle (app):
 * implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"
 */
