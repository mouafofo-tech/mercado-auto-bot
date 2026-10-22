package com.mercado.bot

/** Réponses simples du bot (français et anglais), réponses apprises, règles de texte. */
object Brain {
    fun key(t: String): String =
        t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().take(80)

    /** Vrai si le client demande le prix : le bot ne répond pas, c'est toi qui réponds. */
    fun asksPrice(text: String): Boolean =
        Regex("prix|combien|price|how much|cost|coût|tarif").containsMatchIn(text.lowercase())

    /** Enlève tout prix d'un texte (règle : le bot ne donne jamais de prix). */
    fun noPrice(s: String): String =
        s.replace(Regex("[\$£€]\\s?\\d[\\d.,]*"), "")
            .replace(
                Regex("\\d[\\d.,]*\\s?(usd|dollars?|gbp|pounds?|eur|euros?|fcfa|xaf)", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    /** Sépare la réponse de ChatGPT en (texte à l'écran, légende). Textes par défaut si besoin. */
    fun parsePost(raw: String?): Pair<String, String> {
        val defaultText = "Message us to order"
        val defaultCaption = "Amazing finds from MERCADO #deals #shopping #ukshop #fyp"
        if (raw.isNullOrBlank()) return Pair(defaultText, defaultCaption)
        val parts = raw.split("||")
        val text = (parts.getOrNull(0) ?: "").trim().take(60)
        val caption = (parts.getOrNull(1) ?: "").trim().take(150)
        return Pair(
            if (text.isBlank()) defaultText else text,
            if (caption.isBlank()) defaultCaption else caption
        )
    }

    fun reply(text: String): String? = Store.learned(key(text)) ?: template(text)

    fun learn(text: String, reply: String) = Store.learn(key(text), reply)

    fun template(text: String): String? {
        val t = text.lowercase()
        val fr = listOf("bonjour", "salut", "livraison", "livrez", "commande", "merci", "svp", "disponible")
            .count { t.contains(it) }
        val en = listOf("hello", "ship", "order", "thanks", "available", "do you")
            .count { t.contains(it) }
        if (fr == 0 && en == 0) return null
        val ship = Regex("livr|ship|deliver").containsMatchIn(t)
        val order = Regex("commande|order").containsMatchIn(t)
        return if (fr >= en) {
            when {
                ship -> "Oui, nous livrons. Donne-moi ta ville et le produit, je te confirme."
                order -> "Je vérifie ta commande et je te réponds dans un instant."
                else -> "Merci pour ton message ! Que souhaites-tu commander ?"
            }
        } else {
            when {
                ship -> "Yes, we ship. Tell me your city and the product and I'll confirm."
                order -> "I'm checking your order and I'll get back to you shortly."
                else -> "Thanks for your message! What would you like to order?"
            }
        }
    }
}
