package fr.junade.taipo.bench

import com.darkrockstudios.symspellkt.api.loadUnigramTxtFile
import com.darkrockstudios.symspellkt.common.DamerauLevenshteinDistance
import com.darkrockstudios.symspellkt.common.Murmur3HashFunction
import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.impl.InMemoryDictionaryHolder
import com.darkrockstudios.symspellkt.impl.SymSpell
import kotlinx.coroutines.runBlocking

/**
 * SymSpellKt « brut » pour le banc d'essai : aucune règle Taipo (ni accents, ni apostrophes, ni dictionnaire
 * personnel, ni proximité AZERTY). Réglages par défaut de la bibliothèque. À égalité de distance, c'est le mot le
 * plus fréquent qui est choisi (jamais d'ambiguïté refusée, contrairement au moteur actuel).
 */
internal class SymSpellEngine private constructor(private val symSpell: SymSpell) {

    /** Correction de [word], ou null si le mot est inchangé (connu ou sans candidat). */
    fun correct(word: String): String? {
        val best = symSpell.lookup(word, Verbosity.Closest).firstOrNull()?.term ?: return null
        return if (best == word) null else best
    }

    companion object {
        /** Construit l'index avec les mots de [entries] de fréquence au moins [minFrequency] (même liste de candidats que le moteur actuel). */
        fun build(entries: Map<String, Long>, minFrequency: Long): SymSpellEngine {
            val text = buildString {
                for ((word, frequency) in entries) {
                    if (frequency >= minFrequency) append(word).append(' ').append(frequency).append('\n')
                }
            }
            val settings = SpellCheckSettings()
            val dictionary = InMemoryDictionaryHolder(settings, Murmur3HashFunction())
            val symSpell = SymSpell(settings, DamerauLevenshteinDistance(), dictionary)
            runBlocking { dictionary.loadUnigramTxtFile(text) }
            return SymSpellEngine(symSpell)
        }
    }
}
