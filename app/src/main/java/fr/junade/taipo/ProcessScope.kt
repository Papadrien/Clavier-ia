package fr.junade.taipo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Portée de coroutines liée au processus (audit BUG-04, BUG-09, SEC-01) : pour un travail qui doit aller à son terme même si
 * l'écran ou le service qui l'a lancé disparaît (rotation pendant une restauration, fermeture du moteur IA à l'arrêt du clavier,
 * effacement de l'historique). Une erreur dans une tâche n'annule pas les autres.
 */
object ProcessScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)
