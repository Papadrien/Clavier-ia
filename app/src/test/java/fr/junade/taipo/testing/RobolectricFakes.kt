package fr.junade.taipo.testing

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import org.robolectric.RuntimeEnvironment

/**
 * Audit TEST-01 : outillage des tests Robolectric des contrôleurs.
 *
 * [fake] crée une implémentation d'une interface d'hôte (souvent très large) qui ne répond qu'aux membres listés ; un membre
 * Unit ou Boolean non listé ne fait rien (faux), tout autre membre non listé échoue, pour qu'un test ne dépende jamais en
 * silence d'une valeur inventée. Une propriété Kotlin se nomme par son accesseur (`getLastSelectionStart`).
 */
@Suppress("UNCHECKED_CAST")
internal fun <T : Any> fake(type: KClass<T>, vararg answers: Pair<String, (Array<out Any?>) -> Any?>): T {
    val table = answers.toMap()
    return Proxy.newProxyInstance(type.java.classLoader, arrayOf(type.java)) { proxy, method, args ->
        val arguments: Array<out Any?> = args ?: emptyArray()
        table[method.name]?.let { return@newProxyInstance it(arguments) }
        when {
            method.name == "toString" -> "fake ${type.simpleName}"
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> proxy === arguments.firstOrNull()
            method.returnType == Void.TYPE -> null
            method.returnType == java.lang.Boolean.TYPE -> false
            else -> throw UnsupportedOperationException("${type.simpleName}.${method.name} non prévu par le test")
        }
    } as T
}

/** Contexte de l'application de test (ressources et assets de l'app). */
internal val testContext: Context get() = RuntimeEnvironment.getApplication()

/**
 * Un vrai champ de saisie Android ([EditText]) et la connexion que le système donnerait au clavier : filtres, sélection et
 * actions de l'éditeur se comportent comme sur un appareil.
 */
internal class TestField(configure: EditText.() -> Unit = {}) {
    val view = EditText(testContext).apply(configure)
    val info = EditorInfo()
    val connection: InputConnection = checkNotNull(view.onCreateInputConnection(info)) { "pas de connexion de saisie" }

    val text: String get() = view.text.toString()

    fun setText(text: String, cursor: Int = text.length) {
        view.setText(text)
        view.setSelection(cursor)
    }

    fun select(start: Int, end: Int) = view.setSelection(start, end)
}
