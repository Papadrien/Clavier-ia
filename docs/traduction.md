# Traduction dans le clavier (plan, non commencé)

État : **plan seulement**, rien n'est codé. Décision produit à prendre avant le lot 1 (voir « Questions ouvertes »).

## Ce que font Gboard et SwiftKey

Le parcours est presque le même chez les deux :

1. Un bouton **Traduire** dans la barre du haut.
2. Un **bandeau au-dessus des touches** : langue source (« Détecter » par défaut), flèche ⇄ pour inverser, langue cible (la
   dernière paire est retenue).
3. **On tape dans une zone interne au clavier**, pas dans le champ de l'application. La **traduction s'écrit en direct dans le
   champ** : à chaque pause, le texte inséré précédemment est remplacé par la nouvelle version.
4. Actions secondaires : SwiftKey traduit le presse-papiers et le texte sélectionné ; Gboard propose « Traduire » sur un texte
   copié.

Les deux passent par le cloud (Google Translate, Microsoft Translator). **Exclu pour Taipo** : le texte tapé ne quitte jamais
l'appareil (README, `docs/securite.md`).

## Ce qui existe déjà

| Besoin | Existant |
|---|---|
| Zone de saisie interne au clavier | `PromptInputBuffer`, `PromptModeController` (mode prompt) |
| Moteur local partagé, arrêt d'une inférence | `LlmEngineHost`, `CorrectionEngine.cancel()` |
| Retrouver / remplacer / annuler le texte inséré | `SpanLocator`, `FieldUndo`, `UndoHistory` |
| Pas de traduction dans les champs sensibles | `SensitiveFieldPolicy` |
| Langues du clavier, dictionnaire de la langue source | `ImeLanguages`, `KeyboardLanguage` |
| Protection des URL, e-mails, mots personnels | `ProtectedWords` |
| Garde-fou sur la sortie du modèle | `CorrectionSafeguard` |
| Choix du modèle selon l'appareil | `ModelRecommendation` |

## Moteur : trois options locales

| Option | Qualité | Rapidité | Coût d'intégration |
|---|---|---|---|
| **Gemma, déjà présent** | Bonne avec E2B/E4B, moyenne avec 1B, insuffisante avec 270M | Lente (1 à 3 s par phrase) | Presque nul |
| **Bergamot / Marian** (moteur des traductions de Firefox, open source) | Très bonne pour les paires européennes | Rapide : vrai « en direct » | Bibliothèque native à intégrer (comme sherpa-onnx) ; modèles de 15 à 40 Mo par paire, à héberger sur R2 |
| **ML Kit Translation** | Moyenne | Rapide | Dépendance Google, contraire à la ligne du projet |

**Recommandation : commencer avec Gemma**, derrière une interface `TranslationEngine`, et ajouter Bergamot plus tard si la
latence gêne à l'usage, sans toucher à l'interface.

## Conception

### 1. Mode Traduction (`TranslationModeController`), variante du mode prompt
- Bandeau : source (Auto ou une langue du clavier), ⇄, cible. Paire retenue dans `KeyboardPreferences`.
- Zone de saisie : `PromptInputBuffer`, autocorrection dans la langue source.
- La traduction est écrite dans le champ et suivie (position, contenu) pour être remplacée à chaque nouvelle version.
- En quittant le mode, la traduction devient **une seule action** de `UndoHistory`.

### 2. Rythme du « direct », adapté à un LLM
- Traduction après environ 600 ms de pause ou à une ponctuation de fin de phrase, pas à chaque frappe.
- Découpage en phrases et **cache des phrases déjà traduites** : seule la phrase en cours est retraduite. C'est ce qui rend
  Gemma utilisable ici.
- Une nouvelle frappe annule l'inférence en cours.

### 3. Prompt dédié (`TranslationPrompt`)
- Consigne : « Translate from {src} to {dst}. Output only the translation. Keep names, emojis, URLs and the register (tu/vous). »
- `ProtectedWords` pour les URL, e-mails et mots du dictionnaire personnel.
- Garde-fou dans l'esprit de `CorrectionSafeguard` : retirer les « Voici la traduction : », rejeter un écart de longueur aberrant
  ou une réponse restée dans la langue source.

### 4. Actions secondaires (à la SwiftKey)
- **Traduire la sélection** : même circuit que Corriger, remplacement sur place, annulable.
- **Traduire une copie** depuis le panneau presse-papiers.

### 5. Limites
- Pas de traduction dans les champs sensibles.
- Pas de traduction avec Gemma 3 270M : renvoi vers le choix du modèle, E2B recommandé.
- Aptabase : utilisation et paire de langues seulement, jamais le texte (`docs/analytics.md`).

### 6. Tests unitaires (logique pure)
Découpage en phrases et cache, nettoyage de la sortie, mémorisation de la paire et ⇄, construction du prompt.

## Découpage

1. **Lot 1** : `TranslationEngine` avec Gemma, prompt, garde-fou, tests.
2. **Lot 2** : mode Traduction (bandeau, zone, écriture en direct, annulation).
3. **Lot 3** : traduire la sélection et le presse-papiers.
4. **Lot 4, si besoin** : moteur Bergamot pour un vrai direct.

## Questions ouvertes
- Fonction gratuite ou réservée au pass premium ?
- Le délai de Gemma (1 à 3 s par phrase) est-il acceptable, ou faut-il viser Bergamot dès le départ ?
- Quelles paires de langues en priorité (FR ⇄ EN d'abord ?) ?
