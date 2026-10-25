package com.mercado.bot

import android.content.Context
import kotlin.math.sqrt
import kotlin.random.Random

/** Un produit lu dans l'appli CJ (onglet Products). */
data class CjProduct(val id: String, val title: String, val lists: Int)

/**
 * Choisit un produit AU HASARD parmi ceux qui ont beaucoup de "Lists".
 * - ignore les produits avec moins de minLists
 * - ne garde que les topPoolSize meilleurs
 * - plus de Lists = un peu plus de chances (mais pas toujours le même)
 * - évite les avoidLast derniers produits déjà utilisés (mémorisés sur le téléphone)
 */
class ProductPicker(
    context: Context,
    private val minLists: Int = 500,
    private val topPoolSize: Int = 20,
    private val avoidLast: Int = 30
) {
    private val prefs = context.getSharedPreferences("mercado_picker", Context.MODE_PRIVATE)

    @Synchronized
    fun pick(candidates: List<CjProduct>): CjProduct? {
        val recent = loadRecent()
        val pool = candidates
            .filter { it.lists >= minLists && it.id !in recent }
            .sortedByDescending { it.lists }
            .take(topPoolSize)
        if (pool.isEmpty()) return null

        val weights = pool.map { sqrt(it.lists.toDouble()) }
        var r = Random.nextDouble() * weights.sum()
        for (i in pool.indices) {
            r -= weights[i]
            if (r <= 0) return remember(pool[i], recent)
        }
        return remember(pool.last(), recent)
    }

    private fun loadRecent(): List<String> =
        prefs.getString("recent", "")!!.split("|").filter { it.isNotBlank() }

    private fun remember(p: CjProduct, recent: List<String>): CjProduct {
        val updated = (recent + p.id).takeLast(avoidLast)
        prefs.edit().putString("recent", updated.joinToString("|")).commit()
        return p
    }
}
