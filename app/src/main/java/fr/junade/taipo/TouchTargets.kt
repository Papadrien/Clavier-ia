package fr.junade.taipo

import fr.junade.taipo.dictionary.NextLetterModel

/**
 * Zones de frappe adaptatives, étape 2 : la géométrie. À partir de la probabilité que chaque touche d'une rangée soit la
 * prochaine tapée (voir [NextLetterModel]), calcule les bords de la zone de toucher de chaque touche. Seules les zones
 * invisibles changent, jamais les touches dessinées. Logique pure (sans vue), testée en JVM ; l'intégration à
 * [KeyboardView.keyAt] est un autre lot.
 *
 * Méthode « ancrée » (un redimensionnement trop agressif rend le clavier imprévisible) :
 * - seuls les bords entre deux touches voisines bougent, et toujours vers la voisine la moins probable ;
 * - le cœur de chaque touche (le [Settings.core] de sa largeur, centré) lui reste toujours : viser le centre d'une
 *   touche saisit donc toujours cette touche, même quand sa voisine est très probable ;
 * - les bords extérieurs de la rangée ne bougent pas, ni ceux qui touchent une touche non adaptative (Maj, Effacer, virgule...) ;
 * - deux voisines peu probables, ou aussi probables l'une que l'autre, ne changent rien.
 */
internal object TouchTargets {

    /**
     * Réglages (à ajuster par simulation, étape 3). [core] : part de la largeur de la touche toujours à elle, de 0 (aucun
     * ancrage) à 1 (aucun redimensionnement). [regularizer] : plus il est grand, plus il faut un écart net de probabilité
     * pour déplacer un bord (évite d'agir sur du bruit quand les probabilités sont toutes faibles). [strength] : intensité
     * globale, de 0 (zones nominales) à 1.
     */
    data class Settings(
        val core: Float = DEFAULT_CORE,
        val regularizer: Float = DEFAULT_REGULARIZER,
        val strength: Float = DEFAULT_STRENGTH,
    ) {
        init {
            require(core in 0f..1f) { "core hors de 0..1 : $core" }
            require(regularizer > 0f) { "regularizer doit être positif : $regularizer" }
            require(strength in 0f..1f) { "strength hors de 0..1 : $strength" }
        }
    }

    const val DEFAULT_CORE = 0.6f
    const val DEFAULT_REGULARIZER = 0.05f
    const val DEFAULT_STRENGTH = 1f

    /** Bords des zones nominales : somme cumulée des largeurs ([widths], en unités de poids), de 0 à la largeur de la rangée. */
    fun nominalEdges(widths: List<Float>): FloatArray {
        val edges = FloatArray(widths.size + 1)
        for (i in widths.indices) edges[i + 1] = edges[i] + widths[i]
        return edges
    }

    /**
     * Bords des zones de toucher d'une rangée : n + 1 valeurs croissantes, de 0 à la largeur de la rangée ; la touche i
     * occupe [bords[i], bords[i + 1]). [widths] : largeur nominale de chaque touche. [probabilities] : probabilité que
     * chaque touche soit la prochaine tapée, ou null pour une touche non adaptative (ses deux bords ne bougent pas).
     */
    fun edges(widths: List<Float>, probabilities: List<Double?>, settings: Settings = Settings()): FloatArray {
        require(widths.size == probabilities.size) { "Une probabilité par touche : ${widths.size} touches, ${probabilities.size} valeurs" }
        require(probabilities.all { it == null || it >= 0.0 }) { "Probabilité négative" }
        val nominal = nominalEdges(widths)
        val edges = nominal.copyOf()
        val margin = (1f - settings.core) / 2f
        for (i in 0 until widths.size - 1) {
            val left = probabilities[i] ?: continue
            val right = probabilities[i + 1] ?: continue
            // Écart relatif entre les deux voisines, dans ]-1 ; 1[ : > 0 quand la gauche est plus probable (le bord part vers la droite).
            val lean = (left - right) / (left + right + settings.regularizer) * settings.strength
            val boundary = nominal[i + 1]
            // Limites d'ancrage : le cœur de chaque touche reste à elle.
            val lowest = boundary - margin * widths[i]
            val highest = boundary + margin * widths[i + 1]
            edges[i + 1] = if (lean >= 0.0) {
                boundary + (lean * (highest - boundary)).toFloat()
            } else {
                boundary + (lean * (boundary - lowest)).toFloat()
            }
        }
        return edges
    }

    /**
     * Probabilité de chaque touche de [row] d'après ce qui peut suivre le mot tapé. Seules les lettres « a » à « z » et
     * l'apostrophe sont adaptatives ; les autres touches (Maj, Effacer, espace, ponctuation, chiffres, lettres propres à une
     * langue comme « ñ » ou « ß », dont le modèle replie la lettre sur une autre) restent nominales (null).
     */
    fun probabilities(row: List<Key>, next: NextLetterModel.Probabilities): List<Double?> =
        row.map { key -> adaptiveChar(key)?.let { next.of(it) } }

    /** Le caractère de [key] si sa zone est adaptative (lettre « a » à « z » ou apostrophe), sinon null. */
    fun adaptiveChar(key: Key): Char? {
        val char = (key.action as? KeyAction.TypeChar)?.char?.lowercaseChar() ?: return null
        return if (char in 'a'..'z' || char == '\'') char else null
    }

    /** Vrai si [row] a au moins une touche adaptative (sinon ses zones restent nominales). */
    fun hasAdaptiveKey(row: List<Key>): Boolean = row.any { adaptiveChar(it) != null }

    /** Bords des zones de toucher de [row] pour ce qui peut suivre le mot tapé. */
    fun rowEdges(row: List<Key>, next: NextLetterModel.Probabilities, settings: Settings = Settings()): FloatArray =
        edges(row.map { it.weight }, probabilities(row, next), settings)

    /**
     * Indice de la touche qui contient [x] (en unités de poids depuis le début de la rangée, comme [edges]). Un point
     * avant le premier bord ou après le dernier revient à la touche la plus proche, comme pour les zones nominales.
     */
    fun indexAt(edges: FloatArray, x: Float): Int {
        for (i in 1 until edges.size) {
            if (x < edges[i]) return i - 1
        }
        return edges.size - 2
    }
}
