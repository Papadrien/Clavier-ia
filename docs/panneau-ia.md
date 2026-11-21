# Panneau IA, changement de ton et traduction (20/11/2026)

## Décision UX

La barre du haut ne peut pas accueillir deux boutons de plus sur un téléphone de 412 dp (Pixel 9) : elle porte déjà sept
boutons ronds. Option retenue (maquettes du 20/11/2026) : un **bouton IA** qui ouvre un **panneau IA** à la place des touches,
comme le panneau emoji, et l'**épinglage** de deux actions dans la barre.

- Panneau IA : toutes les actions IA du clavier, en cases. Corriger, Ton, Traduire, Chat, Transcription IA. La dictée Vocal
  n'est pas une action IA : elle garde sa place fixe à droite de la barre.
- Appui sur une case : l'action se lance. Appui long : elle est épinglée dans la barre (ou retirée). Deux au plus ; une
  troisième est refusée avec un message. Une punaise marque les cases épinglées.
- Bulle d'astuce en haut du panneau : tant que rien n'est épinglé, et pendant les trois premières ouvertures.
- Barre, lue depuis la droite : Vocal, bouton IA, première épinglée, seconde épinglée. Le bouton IA prend la place de Chat.
- Par défaut : Corriger et Transcription IA épinglées, donc la barre garde l'accès direct d'avant.
- Une action non épinglée lancée depuis le panneau (Corriger, Transcription) apparaît dans la barre le temps de son traitement :
  c'est son bouton qui l'annule ou l'arrête.
- Rangée de saisie (texte dans le champ) : menu du robot fermé, Corriger reste à droite de la bande de mots s'il est épinglé
  (même règle qu'avant) ; menu ouvert, les épinglées et le bouton IA.

Code : `AiAction` et `AiPins` (logique pure), `AiPanelView`, `AiPanelController`, `BarVisibility.normalRowAi` /
`typingRowAi`, `CorrectionBarView.arrangeAiButtons`. Réglage enregistré : `KeyboardPreferences.pinnedAiActions` (sauvegardé).

## Changer le ton

Inspiré de Galaxy AI, Apple Writing Tools, Grammarly et SwiftKey. Huit tons (`Tone`) : Pro, Amical, Décontracté, Poli, Concis,
Assuré, Bienveillant, Réseaux sociaux.

- Le texte visé est la sélection du champ, ou à défaut tout son texte.
- Chaque ton réécrit le texte **d'origine** (essayer plusieurs tons ne déforme pas le message) et remplace la version
  précédente. « Rétablir » remet l'original. Chaque remplacement est une action du bouton Annuler.
- Avec une sélection, la version écrite reste sélectionnée pour que le ton suivant la remplace.
- Pas avec Gemma 3 270M (il change le sens ou répond au texte) : message qui renvoie vers Colibri ou plus.

## Traduire

Comme Gboard et SwiftKey, mais sur l'appareil : le texte ne part jamais vers un service en ligne.

- Le mode Traduction réutilise la zone de saisie du mode Chat ; la zone de chat laisse la place au bandeau des langues.
- On tape dans la zone ; la traduction s'écrit dans le champ après 450 ms de pause et remplace la version précédente. Elle se fait
  phrase par phrase : les phrases déjà traduites viennent d'un cache, seule la phrase en cours repart au modèle.
  Envoyer (ou Entrée) fige le segment ; la suite s'écrit après lui. Croix ou appui dans le champ : on sort, la traduction reste.
- Bandeau (maquette du 21/11/2026) : « Traduire le texte », langue de départ (« Détecter » par défaut), ⇄, langue d'arrivée.
  Toucher une langue ouvre la liste des langues à la place des touches (`TranslationLanguagePanelView`) : « Détecter » (départ),
  récentes, puis les 43 langues de l'application par ordre alphabétique ; celles que le modèle actif ne traduit pas sont grisées
  (« Gemma 4 »). Langues de départ et d'arrivée retenues. ⇄ échange les deux ; depuis « Détecter », l'arrivée devient la langue
  du clavier.
- Animations : la liste monte en ressort, ses lignes arrivent l'une après l'autre, les touches s'effacent en descendant ; un nom
  de langue qui change rebondit ; ⇄ tourne d'un demi-tour. Le fond animé s'anime pendant toute traduction (et tout changement de
  ton), comme pour les autres actions IA.
- « Traduire le texte » (rien de tapé, du texte dans le champ) : la sélection, ou tout le texte, est traduit sur place.
- Langues proposées selon le modèle (`TranslationLanguages.forModel`) : aucune avec Gemma 3 270M, les 15 langues du clavier avec
  Gemma 3 1B (entraîné surtout en anglais), 43 langues avec Gemma 4 E2B / E4B et le modèle personnalisé.

Code : `LiveTranslator`, `TranslationBarView`, `PromptModeController.enterTranslation`, `FieldEditor.replaceTextBeforeCursor`,
`ai/TranslationPrompt.kt`.

## Moteur et prompts

`RewriteEngine` : réécriture en un tour sur le moteur partagé (même verrou, même relance sur le processeur, même arrêt natif que
`CorrectionEngine`). `RewriteSafeguard` retire l'introduction (« Voici le texte : ») et les guillemets autour de la réponse.

Prompts système modifiables (pack premium) dans « Prompts système » et « Modèle personnalisé » : `TonePromptPreferences`,
`TranslationPromptPreferences`. Ils font partie de la sauvegarde (`tone`, `translation`).

Mesure d'usage : `ai_action` avec `type` `tone` ou `translate` (jamais le texte ni la langue).

## Transitions (21/11/2026)

- **Compteur** (`LayoutRoll`, dessiné par `KeyRenderer`) entre lettres, chiffres et symboles : les touches ne bougent pas, chaque
  caractère défile hors de sa touche et le nouveau arrive à sa place, en vague depuis la touche touchée (« ?123 », « ABC »,
  « =\< »). Vers les pages suivantes les caractères montent, vers les précédentes ils descendent.
- **Store** (`PanelShade`) pour les panneaux emoji et IA : le panneau descend du haut de la zone des touches et se pose en
  ressort, les touches s'assombrissent derrière ; à la fermeture (ABC, bouton IA), il remonte. Changement de champ ou rotation :
  direct.
- Les deux suivent le ressort du style d'animation ; animations Android coupées : changement direct.

## Rapidité (21/11/2026, version 4.0.0)

- **Décodage glouton et réponse plafonnée** (`InferenceTuning`) pour la correction, le ton et la traduction : pas de tirage au
  sort, et un modèle qui part en boucle s'arrête tôt (plafond : longueur du texte / 2 + 64 jetons). Le Chat n'est pas concerné.
- **Traduction en direct phrase par phrase** et pause ramenée à 450 ms.
- Pixel 9 : le GPU échoue avec LiteRT-LM (OpenCL absent), le modèle tourne sur le processeur ; un modèle plus léger (Colibri)
  répond nettement plus vite pour la correction. Préchargement du modèle essayé puis retiré le même jour : la frappe n'était
  pas fluide pendant le chargement ; le modèle se charge de nouveau au premier appui, comme avant.

## À valider sur l'appareil

- [ ] Pixel 9 : barre par défaut (Paramètres, Smart Clipboard, Annuler, Transcription, Corriger, IA, Vocal) sans débordement.
- [ ] Panneau : appui long épingle et retire ; troisième épingle refusée ; la barre suit tout de suite.
- [ ] Ton : sélection et texte entier ; plusieurs tons à la suite ; Rétablir ; Annuler de la barre après coup.
- [ ] Traduction : saisie en direct, Envoyer, plusieurs segments, changement de langue en cours, croix ; « Traduire le texte ».
- [ ] Modèles : 270M (messages), Colibri (15 langues), E2B (liste étendue).
