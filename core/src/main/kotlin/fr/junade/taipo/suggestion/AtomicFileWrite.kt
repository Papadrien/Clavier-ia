package fr.junade.taipo.suggestion

import java.io.File
import java.io.FileOutputStream

/**
 * Remplace le contenu de [target] par [bytes] sans jamais laisser un fichier à moitié écrit : écriture
 * dans un fichier temporaire voisin (`<nom>.tmp`) puis renommage. Si le renommage direct échoue (le
 * fichier cible existe déjà sur certains systèmes de fichiers), la cible est supprimée puis le
 * renommage est retenté.
 *
 * Appel synchrone : quand la fonction rend la main, le fichier est écrit (ou une exception a été levée).
 * Les appels concurrents sur la même cible doivent être sérialisés par l'appelant (fichier temporaire commun).
 *
 * Audit BUG-03 : le contenu est forcé sur le disque (`fsync`) avant le renommage. Sans cela, après une coupure de courant,
 * le renommage peut avoir eu lieu alors que les données n'étaient pas écrites : le fichier serait vide ou tronqué.
 */
fun writeAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    FileOutputStream(temp).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    if (!temp.renameTo(target)) {
        target.delete()
        check(temp.renameTo(target)) { "Impossible de remplacer le fichier ${target.name}" }
    }
}
