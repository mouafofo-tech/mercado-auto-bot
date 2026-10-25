package com.mercado.bot

import android.content.Context
import android.net.Uri

/** Branche ici tes fonctions existantes (accessibilité). */
interface CjActions {
    /** Ouvre l'appli CJ > Products et lit les produits visibles (titre + nombre de Lists). */
    suspend fun readCandidates(): List<CjProduct>

    /** Ouvre CE produit, appui long sur la vidéo, OK. Revient dès que le téléchargement est lancé. */
    suspend fun openProductAndSaveVideo(productId: String)
}

interface TikTokActions {
    /** Ouvre TikTok, importe la vidéo (+ intro MERCADO), texte, musique, enregistre en brouillon. */
    suspend fun addIntroAndSaveDraft(video: Uri)
}

class TikTokDraftTask(
    private val context: Context,
    id: String,
    private val cj: CjActions,
    private val tiktok: TikTokActions,
    private val picker: ProductPicker
) : BotTask(id, "Brouillon TikTok") {

    override suspend fun run(ctx: TaskContext) {

        // 1) Choix du produit au hasard (mémorisé: si tu interromps, il garde le même produit)
        ctx.step("pick_product") {
            val candidates = ctx.withScreen { cj.readCandidates() }
            val product = picker.pick(candidates)
                ?: error("Aucun produit avec assez de Lists")
            ctx.put("productId", product.id)
            ctx.put("productTitle", product.title)
        }

        // 2) Lance le téléchargement CJ, puis libère l'écran
        ctx.step("cj_download") {
            ctx.putIfAbsent("startedAt", System.currentTimeMillis().toString())
            val productId = ctx.get("productId") ?: error("Produit perdu")
            ctx.withScreen { cj.openProductAndSaveVideo(productId) }
        }

        // 3) Hors écran: attend la vidéo la plus récente (l'autre tâche peut utiliser l'écran)
        ctx.step("wait_video") {
            val startedAt = ctx.get("startedAt")?.toLong() ?: error("Heure de début perdue")
            val video = ctx.waitFor("téléchargement CJ", 5 * 60_000L) {
                VideoWaiter.newestSince(context, startedAt)
            } ?: error("Vidéo CJ non reçue dans la galerie")
            ctx.put("video", video.toString())
        }

        // 4) TikTok: une seule étape (l'éditeur TikTok perd son état si on le quitte)
        ctx.step("tiktok_draft") {
            val video = Uri.parse(ctx.get("video") ?: error("Vidéo perdue"))
            ctx.withScreen { tiktok.addIntroAndSaveDraft(video) }
        }
    }
}

/*
 * Démarrage (dans ton service du bot):
 *
 * val store = TaskStore(applicationContext)
 * val picker = ProductPicker(applicationContext)          // minLists = 500 par défaut
 * val scheduler = TaskScheduler(
 *     scope = serviceScope,
 *     store = store,
 *     isPhoneFree = { PhoneState.isFree() },   // doit ignorer les gestes du bot lui-même
 *     onStatus = { id, title, status -> dashboard.update(id, title, status) },
 *     parallel = 2
 * )
 * scheduler.start()
 *
 * // Reprend les tâches commencées avant un redémarrage de l'appli
 * for (id in store.pendingIds()) {
 *     scheduler.submit(TikTokDraftTask(applicationContext, id, cjActions, tiktokActions, picker))
 * }
 *
 * // Nouvelle tâche
 * scheduler.submit(TikTokDraftTask(applicationContext, "draft-" + System.currentTimeMillis(), cjActions, tiktokActions, picker))
 */
