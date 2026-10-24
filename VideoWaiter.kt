package com.mercado.bot

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Trouve la vidéo la plus récente ajoutée à la galerie APRÈS un instant donné
 * (le moment où le bot a appuyé sur "Save video to album?" > OK dans l'appli CJ).
 *
 * - Ignore les vieilles vidéos de la galerie.
 * - Attend que la taille du fichier arrête de grandir (téléchargement fini).
 * - Si deux tâches téléchargent en même temps, chacune réserve sa propre vidéo.
 *
 * Permission nécessaire (à demander une fois):
 *   Android 13+ : READ_MEDIA_VIDEO
 *   Android 12- : READ_EXTERNAL_STORAGE
 */
object VideoWaiter {
    private val claimed = HashSet<Long>()
    private val collection: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    /** À utiliser avec ctx.waitFor(...). Retourne null tant que la vidéo n'est pas prête. */
    suspend fun newestSince(context: Context, sinceMs: Long): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val candidates = ArrayList<Pair<Long, Long>>() // id, taille
        resolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE),
            "${MediaStore.Video.Media.DATE_ADDED} >= ?",
            arrayOf(((sinceMs / 1000) - 2).toString()),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) candidates.add(c.getLong(0) to c.getLong(1))
        }

        for ((id, size) in candidates) {
            val taken = synchronized(claimed) { id in claimed }
            if (taken) continue

            delay(1_500) // laisse le temps au fichier de finir d'écrire
            val size2 = sizeOf(resolver, id)
            if (size2 > 0 && size2 == size) {
                synchronized(claimed) { claimed.add(id) }
                return@withContext ContentUris.withAppendedId(collection, id)
            }
        }
        null
    }

    private fun sizeOf(resolver: ContentResolver, id: Long): Long {
        val uri = ContentUris.withAppendedId(collection, id)
        resolver.query(uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return -1
    }
}
