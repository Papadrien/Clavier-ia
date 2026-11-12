# Écran « Langues » (piste C)

Non compilé ni exécuté dans l'environnement de développement : à valider au premier build.

## Parcours
Paramètres du clavier > ligne « Langues » (sous-titre = langues utilisées) > `LanguagesActivity` : une ligne par langue
(nom, disposition, « langue de l'appareil », pastille « À télécharger » si le dictionnaire manque) + « Ajouter une langue ».
Un appui sur une langue ouvre `LanguageDetailActivity` : disposition (8 choix avec aperçu, dont les trois QWERTY nordiques depuis le 11/11/2026), carte « Dictionnaire »,
« Retirer cette langue » (seulement s'il en reste une autre).

## Modèle
- Plus d'interrupteur multilingue : le clavier est multilingue dès 2 langues. `LanguageSetup` / `LanguageList` (module `:core`)
  gardent cohérents les trois réglages existants (langue choisie, langues cochées, mode) ; réglages inchangés, donc pas de migration.
- Retirer jusqu'à une seule langue éteint le mode ; la langue restante est « automatique » si c'est celle du système.
- Disposition par langue : `KeyboardPreferences.letterArrangementOf / setLetterArrangement` (clé `letter_arrangement_<code>`,
  absente = disposition d'origine : FR AZERTY ; DE, CS, HU QWERTZ ; ES QWERTY (Ñ) ; SV, FI QWERTY (Å Ä Ö) ; DA QWERTY (Æ Ø Å) ;
  NB QWERTY (Ø Æ Å) ; autres QWERTY).
- `Keyboards.lettersOf / layoutOf(..., arrangement)` : dispositions d'origine inchangées, autres construites une fois et mises en
  cache. Accents = ceux de la langue ; l'espagnol hors « QWERTY (Ñ) » saisit ñ par appui long sur N.
- Colemak : la maquette montrait 9 touches en haut ; l'apostrophe prend la 10e place (à la place du « ; ») pour garder
  10 unités par rangée et les chiffres 1 à 0 en appui long.
- Changer de disposition vide le dictionnaire chargé de la langue (`DictionaryLoader.evict`) : la proximité des touches en dépend ;
  il est relu à l'ouverture du prochain champ.

## À valider
- [ ] Ajout / retrait de langues, appui long sur Entrée (globe) en multilingue.
- [ ] Chaque disposition sur appareil, dont Colemak (apostrophe en haut à droite) et l'espagnol hors Ñ.
- [ ] TalkBack sur la liste des dispositions (lue comme boutons radio).

## Quinze langues : ce que chacune sait faire (12/11/2026)
Huit langues ajoutées le 11/11/2026 (pl, ro, cs, hu, sv, da, nb, fi) : voir `dictionaries/SOURCES.txt` pour les données et
`docs/dictionnaires-distants.md` pour les fichiers à déposer sur le serveur.

| | fr, en | es, de, it, pt, nl | pl, ro, cs, hu, sv, da, nb, fi |
|---|---|---|---|
| Dictionnaire complet (téléchargé), prénoms | oui | oui | oui, **une fois les fichiers déposés sur R2** |
| Liste de démarrage dans l'APK (corrige avant le téléchargement) | oui | non | oui (12/11/2026) |
| Bigrammes (mot précédent) | oui | it, pt, nl | oui |
| Règles de contexte (homophones, accords, participes) | oui | oui | **non** |
| Règles de flexion (pluriels réguliers absents des listes) | oui | es, de, pt, nl | **non** (formes fléchies prises dans les listes) |
| Réglage de décision mesuré sur un banc | oui | oui | oui, sans contexte (`CorrectionConfidence.LATIN`, références du 12/11/2026) |
| Emoji proposés d'après le mot | oui | **non** | oui (12/11/2026) |
| Dictée vocale (modèle Nemotron multilingue, langue détectée seule) | oui | prise en charge par le modèle, **à tester** | prise en charge par le modèle, **à tester** |
| Nombres dictés écrits en chiffres (« zwanzig » -> « 20 ») | oui | oui (12/11/2026) | oui (12/11/2026) |
| IA (correction, chat, dictée IA Gemma 4) | oui | multilingue, non mesuré | multilingue, non mesuré |
| Interface traduite | oui | oui (à relire) | oui (à relire) |

Mesures du banc (`./gradlew :core:run --args="--lang=<code>"`, fautes de frappe aléatoires sur les mots courants) : bien corrigées
pl 95,8 % ; ro 92,1 % ; cs 93,7 % ; hu 95,7 % ; sv 93,3 % ; da 93,3 % ; nb 92,6 % ; fi 95,2 % (nl 95,9 % pour comparer). Aucune
phrase juste changée (phrases écrites pour le banc et phrases Tatoeba mises de côté).

## Tests sur appareil des langues ajoutées (à faire, rien n'a été vérifié sur un téléphone)
Prérequis : les 24 fichiers déposés sur R2 (`docs/dictionnaires-distants.md`), sinon seule la liste de démarrage est utilisée.

Pour chaque langue (pl, ro, cs, hu, sv, da, nb, fi) :
- [ ] Ajouter la langue dans « Langues » : la carte « Dictionnaire » passe de « Téléchargement… » à « Installé » (Wi-Fi), sans
      erreur d'empreinte ; avant la fin du téléchargement, la correction marche déjà (liste de démarrage).
- [ ] Disposition par défaut affichée et utilisable : QWERTY (pl, ro), QWERTZ (cs, hu), QWERTY (Å Ä Ö) (sv, fi), QWERTY (Æ Ø Å) (da),
      QWERTY (Ø Æ Å) (nb).
- [ ] Appui long sur les lettres accentuées de la langue (ex. pl : ł, ż / ź ; ro : ș, ț ; cs : ř, ů ; hu : ő, ű).
- [ ] Autocorrection à l'espace sur quelques fautes courantes (ex. pl « juz » → « już », ro « daca » → « dacă », cs « dekuji » →
      « děkuji », hu « koszonom » → « köszönöm », sv « ar » → « är », da « hvordn » → « hvordan », nb « pa » → « på »,
      fi « etta » → « että ») ; et rien de changé sur les paires de deux mots justes (pl « ze », cs « byt », hu « meg », sv « sa »,
      fi « ala »).
- [ ] Majuscule des prénoms (ex. « piotr » → « Piotr », « laszlo » → « László »).
- [ ] Emoji proposé sur un mot de la liste (ex. pl « pizza », sv « katt », fi « kahvia »).
- [ ] Interface de l'application dans la langue (réglages Android > Langues de l'application) : rien de tronqué ni coupé au milieu
      d'un mot, en particulier les pastilles de couleur, les cartes de thème et les sélecteurs segmentés.

Dispositions nordiques (11 touches par rangée) :
- [ ] Les touches restent assez larges pour taper ; å, ä, ö (ou æ, ø) saisis directement.
- [ ] Rangée de chiffres désactivée : appui long sur la rangée du haut = 1 à 0 ; la 11e touche (å) n'a pas de chiffre.
- [ ] Rangée de chiffres activée, mode une main, clavier flottant : rien ne déborde.
- [ ] Bulle d'accents sur la 11e touche et sur les lettres du bord droit : la bulle reste dans l'écran.
- [ ] Zones de frappe adaptatives (réglage « Précision de frappe ») : pas de touche inatteignable.

Dictée vocale (pour chacune des 13 langues autres que fr et en : es, de, it, pt, nl, pl, ro, cs, hu, sv, da, nb, fi) :
- [ ] Dicter une phrase simple dans la langue, clavier réglé sur cette langue : le texte sort dans la bonne langue, avec ses
      lettres (ł, ș, ř, ő, å, ø...), sans passage au français ou à l'anglais en cours de phrase.
- [ ] Aucune étiquette de langue (`<pl-PL>`, `<sv-SE>`...) dans le texte inséré : le modèle en a dans son vocabulaire, et le code ne
      les retire pas.
- [ ] Nombres : dire « vingt-trois », « deux mille vingt-six », « trois virgule cinq » et « vingt pour cent » dans la langue
      (ex. de « dreiundzwanzig », pl « dwa tysiące dwadzieścia sześć », sv « tre komma fem », fi « kaksikymmentä prosenttia ») :
      ils s'écrivent en chiffres (« 23 », « 2026 », « 3,5 », « 20 % ») ; et un article seul reste en lettres (de « ein Haus »,
      sv « en katt », hu « egy ház »). Noter la forme exacte renvoyée par le modèle si un nombre reste en lettres : en un mot
      ou en plusieurs, avec ou sans trait d'union.
- [ ] Dicter juste après avoir basculé de langue (globe) : la dictée suit, et les nombres sont lus dans la nouvelle langue.

Multilingue :
- [ ] Basculer (appui long sur Entrée, globe) entre le français et une langue nordique : la disposition et le dictionnaire suivent.
- [ ] Langue de l'appareil en polonais (ou autre) : la langue « automatique » du clavier est bien celle-ci au premier lancement.

