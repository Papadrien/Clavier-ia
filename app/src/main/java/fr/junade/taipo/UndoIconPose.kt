package fr.junade.taipo

/**
 * Pose de l'icône Annuler animée (« Rembobinage ») : calculs purs, en degrés. À chaque appui l'icône fait un tour complet
 * à l'envers (sens inverse des aiguilles d'une montre, celui de la flèche) en s'écrasant un peu au départ ; le ressort
 * ([SpringValue], ~20 % de dépassement) la fait dépasser son point d'arrivée puis osciller avant de se poser. La touche,
 * elle, ne change pas : seule l'icône tourne autour de son centre.
 */
internal object UndoIconPose {

    /** Un appui = un tour complet à l'envers (négatif : sens inverse des aiguilles d'une montre). */
    const val TURN_DEGREES = -360f

    /** Taille de l'icône au départ d'un appui, puis rebond vers 1. */
    const val TAP_SQUASH = 0.7f

    /** Taille de l'icône quand le bouton redevient actif (historique non vide) : petit « pop ». */
    const val ENABLE_SQUASH = 0.55f

    /** Pivot de la rotation et de l'écrasement : le centre de la grille de 24. */
    const val PIVOT = 12f

    /** Angle visé après [turns] appuis : un tour de plus à chaque appui, y compris pendant un ressort en cours. */
    fun targetAngle(turns: Int): Float = TURN_DEGREES * turns
}
