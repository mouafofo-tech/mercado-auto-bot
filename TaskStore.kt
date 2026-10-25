package com.mercado.bot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mémoire du bot: pour chaque tâche, les étapes déjà faites et quelques données
 * (produit choisi, heure de début du téléchargement, vidéo trouvée...).
 * Sauvegardé sur le téléphone: survit si tu quittes l'appli ou si le bot redémarre.
 */
class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("mercado_tasks", Context.MODE_PRIVATE)

    private fun key(id: String) = "task_$id"

    private fun load(id: String): JSONObject =
        JSONObject(prefs.getString(key(id), null) ?: """{"done":[],"data":{}}""")

    private fun save(id: String, obj: JSONObject) {
        prefs.edit().putString(key(id), obj.toString()).commit()
    }

    @Synchronized
    fun begin(id: String) {
        if (!prefs.contains(key(id))) save(id, load(id))
    }

    /** Tâche terminée (ou abandonnée): on efface sa mémoire. */
    @Synchronized
    fun finish(id: String) {
        prefs.edit().remove(key(id)).commit()
    }

    @Synchronized
    fun isDone(id: String, step: String): Boolean {
        val done = load(id).getJSONArray("done")
        for (i in 0 until done.length()) if (done.getString(i) == step) return true
        return false
    }

    @Synchronized
    fun markDone(id: String, step: String) {
        val obj = load(id)
        obj.getJSONArray("done").put(step)
        save(id, obj)
    }

    @Synchronized
    fun put(id: String, name: String, value: String) {
        val obj = load(id)
        obj.getJSONObject("data").put(name, value)
        save(id, obj)
    }

    @Synchronized
    fun get(id: String, name: String): String? {
        val data = load(id).getJSONObject("data")
        return if (data.has(name)) data.getString(name) else null
    }

    /** Tâches commencées mais pas finies (à relancer après un redémarrage). */
    @Synchronized
    fun pendingIds(): List<String> =
        prefs.all.keys.filter { it.startsWith("task_") }.map { it.removePrefix("task_") }
}
