package fr.junade.taipo

import android.util.Log

/**
 * Journal de l'application : actif uniquement dans les builds debug ([BuildConfig.DEBUG]). En
 * release, aucun message n'est écrit dans logcat. À utiliser à la place de [android.util.Log].
 * Aucun texte saisi ou copié par l'utilisateur ne doit y figurer.
 */
object AppLog {

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }
}
