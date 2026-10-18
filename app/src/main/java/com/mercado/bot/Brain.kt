package com.mercado.bot

/** Réponses simples du bot (français et anglais) + réponses déjà apprises. */
object Brain {
    fun key(t: String): String =
        t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().take(80)

    fun reply(text: String): String? = Store.learned(key(text)) ?: template(text)

    fun learn(text: String, reply: String) = Store.learn(key(text), reply)

    fun template(text: String): String? {
        val t = text.lowercase()
        val fr = listOf("bonjour", "salut", "prix", "livraison", "livrez", "commande", "merci", "combien", "svp", "disponible")
            .count { t.contains(it) }
        val en = listOf("hello", "price", "ship", "order", "thanks", "how much", "available", "do you")
            .count { t.contains(it) }
        if (fr == 0 && en == 0) return null
        val ship = Regex("livr|ship|deliver").containsMatchIn(t)
        val price = Regex("prix|combien|price|how much").containsMatchIn(t)
        val order = Regex("commande|order").containsMatchIn(t)
        return if (fr >= en) {
            when {
                ship -> "Oui, nous livrons. Donne-moi ta ville et le produit pour te confirmer le prix et le délai."
                price -> "Dis-moi quel produit t'intéresse et je te donne le prix tout de suite."
                order -> "Je vérifie ta commande et je te réponds dans un instant."
                else -> "Merci pour ton message ! Que souhaites-tu commander ?"
            }
        } else {
            when {
                ship -> "Yes, we ship. Tell me your city and the product and I'll confirm the price and delay."
                price -> "Tell me which product you like and I'll give you the price right away."
                order -> "I'm checking your order and I'll get back to you shortly."
                else -> "Thanks for your message! What would you like to order?"
            }
        }
    }
}
