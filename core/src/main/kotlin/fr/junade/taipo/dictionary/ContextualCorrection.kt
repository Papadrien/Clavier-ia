package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.WordText
import java.util.Locale

/**
 * Corrections de contexte du français ([FrenchContextCorrector] : a / à, homophones, participes, traits d'union) soumises
 * aux habitudes de l'utilisateur (phase 5) :
 * - une correction annulée deux fois n'est plus appliquée : le **même** mécanisme que pour le dictionnaire ;
 * - une correction que les habitudes contredisent dans ce contexte précis (voir [HabitContext.keepsTyped]) n'est pas appliquée.
 *
 * C'est aussi ici que la bande de suggestions lit la correction du mot en cours de frappe ([lastWordFix]) : elle montre
 * ce que l'autocorrection appliquera à l'espace. Logique pure, testée en JVM par `ContextualCorrectionTest`.
 */
object ContextualCorrection {

    /**
     * Comme [FrenchContextCorrector.correct], mais sans la correction que l'utilisateur refuse (annulée deux fois) ou que ses
     * habitudes contredisent. [habitsFor] : les habitudes pour le dernier mot du texte qu'on lui passe (null : aucune).
     * Quand la correction ne change pas exactement un mot (un trait d'union entre deux mots...), rien ne peut être
     * comparé aux habitudes : elle est appliquée telle quelle, comme avant.
     */
    fun apply(
        text: String,
        textMayBeTruncated: Boolean,
        allowRetro: Boolean,
        frequencyOf: (String) -> Long,
        habitsFor: (String) -> HabitContext?,
        language: KeyboardLanguage = KeyboardLanguage.FR,
    ): String? {
        val corrected = correctWithoutHabits(language, text, textMayBeTruncated, allowRetro, frequencyOf) ?: return null
        var common = 0
        val limit = minOf(text.length, corrected.length)
        while (common < limit && text[common] == corrected[common]) common++
        val changed = HabitSignals.changedWord(text, text.length - common, corrected.substring(common)) ?: return corrected
        val habits = habitsFor(textUpToChangedWord(text, common)) ?: return corrected
        if (habits.isRefused(changed.first, changed.second) || habits.keepsTyped(changed.first, changed.second)) return null
        return corrected
    }

    /** La correction de contexte de [language] seule (chaque langue du clavier a sa table), sans habitudes ; null : rien à changer. */
    fun correctWithoutHabits(
        language: KeyboardLanguage,
        text: String,
        textMayBeTruncated: Boolean,
        allowRetro: Boolean,
        frequencyOf: (String) -> Long,
    ): String? = when (language) {
        KeyboardLanguage.FR -> FrenchContextCorrector.correct(text, textMayBeTruncated, allowRetro, frequencyOf)
        KeyboardLanguage.EN -> EnglishContextCorrector.correct(text, textMayBeTruncated, allowRetro, frequencyOf)
        KeyboardLanguage.ES -> SpanishContextCorrector.correct(text, textMayBeTruncated, allowRetro, frequencyOf)
        KeyboardLanguage.DE -> GermanContextCorrector.correct(text, textMayBeTruncated, allowRetro, frequencyOf)
        KeyboardLanguage.IT -> ItalianContextCorrector.correct(text, textMayBeTruncated, allowRetro, frequencyOf)
        KeyboardLanguage.PT -> PortugueseContextCorrector.correct(text, textMayBeTruncated, allowRetro, frequencyOf)
        KeyboardLanguage.NL -> DutchContextCorrector.correct(text, textMayBeTruncated, allowRetro, frequencyOf)
    }

    /**
     * La correction que l'autocorrection appliquera au dernier mot de [text] (le texte avant le curseur, ou jusqu'à la fin
     * du mot si le curseur est dedans), ou null : voir [ParticipleFix]. C'est [apply] sans la révision du mot précédent
     * (la bande ne montre que le mot en cours), puis, à défaut, le participe qu'on pourrait seulement proposer. Rien si la
     * correction toucherait aussi un autre mot (« est ce » -> « est-ce »).
     */
    fun lastWordFix(
        text: String,
        textMayBeTruncated: Boolean,
        frequencyOf: (String) -> Long,
        habitsFor: (String) -> HabitContext?,
        language: KeyboardLanguage = KeyboardLanguage.FR,
    ): ParticipleFix? {
        val corrected = apply(text, textMayBeTruncated, allowRetro = false, frequencyOf = frequencyOf, habitsFor = habitsFor, language = language)
        if (corrected != null) {
            val typed = WordText.trailingWord(text)
            val fixed = WordText.trailingWord(corrected)
            val onlyLastWord = typed.isNotEmpty() && fixed.isNotEmpty() && text.dropLast(typed.length) == corrected.dropLast(fixed.length)
            if (onlyLastWord && !fixed.equals(typed, ignoreCase = true)) return ParticipleFix(fixed.lowercase(Locale.ROOT), automatic = true)
        }
        // Le participe proposé seul (accent oublié) n'existe qu'en français.
        if (language != KeyboardLanguage.FR) return null
        return FrenchContextCorrector.participleFor(text, frequencyOf)?.takeUnless { fix ->
            fix.automatic && habitsFor(text)?.let { it.isRefused(WordText.trailingWord(text), fix.form) } == true
        }
    }

    /** Le texte jusqu'à la fin du mot que touche l'indice [index] (début du changement) : c'est lui que les habitudes lisent. */
    private fun textUpToChangedWord(text: String, index: Int): String {
        var end = index.coerceIn(0, text.length)
        while (end < text.length && WordText.isWordChar(text[end])) end++
        return text.substring(0, end)
    }
}
