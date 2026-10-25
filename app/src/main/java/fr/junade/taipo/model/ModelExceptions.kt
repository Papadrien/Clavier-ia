package fr.junade.taipo.model

/**
 * Le fichier du modèle est introuvable, incomplet ou illisible (story 8.6). Reste une [IllegalStateException]
 * pour ne pas changer le comportement des appelants existants.
 */
class ModelFileException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Le moteur n'a pas pu charger un fichier pourtant présent (story 8.6) : modèle incompatible avec le moteur,
 * corrompu, ou échec d'initialisation. La cause d'origine est conservée pour le journal de debug.
 */
class ModelLoadException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
