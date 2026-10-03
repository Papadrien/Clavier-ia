package fr.junade.taipo.suggestion

import java.io.File

/**
 * Remplace le contenu de [target] par [bytes] sans jamais laisser un fichier à moitié écrit : écriture
 * dans un fichier temporaire voisin (`<nom>.tmp`) puis renommage. Si le renommage direct échoue (le
 * fichier cible existe déjà sur certains systèmes de fichiers), la cible est supprimée puis le
 * renommage est retenté.
 *
 * Appel synchrone : quand la fonction rend la main, le fichier est écrit (ou une exception a été levée).
 * Les appels concurrents sur la même cible doivent être sérialisés par l'appelant (fichier temporaire commun).
 */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(target)) {
        target.delete()
        check(temp.renameTo(target)) { "Impossible de remplacer le fichier ${target.name}" }
    }
}
