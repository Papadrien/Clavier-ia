# Clavier-ia

Clavier Android natif minimal en français (AZERTY) : on tape simplement sur les touches pour écrire.

- Lettres AZERTY (avec touche Maj, une seule lettre en majuscule)
- Chiffres et symboles (bascule `123` / `ABC`) ; **rangée de chiffres** optionnelle au-dessus des lettres (Paramètres du clavier) ; si elle est désactivée, les chiffres restent accessibles via `123` ou par **appui long** sur les touches de la rangée du haut (1 à 0)
- Ponctuation (`?`, `,`, `.`, `!`, accents français `é è ç à`, apostrophe, parenthèses…)
- **Dictionnaire personnel** (écran « Dictionnaire personnel ») : mots ajoutés à la main, jamais corrigés par l'autocorrection locale et utilisés comme candidats de correction ; stocké dans une base **Room chiffrée par SQLCipher**
- **Appui long sur une lettre** : bulle d'accents et caractères spéciaux (é è ê ë, à â æ, ç, ô œ, ù û ü, ñ, ß…), sélection en glissant le doigt puis en le relevant sur le caractère voulu ; le choix présélectionné (ex. é pour E) est centré juste au-dessus de la lettre appuyée (décalé seulement si la lettre est trop près d'un bord de l'écran) ; les lettres du haut proposent leur chiffre en premier quand la rangée de chiffres est désactivée ; les caractères passent en majuscule avec Maj
- **Barre espace** : glisser le doigt dessus déplace le curseur (un caractère par pas), sans saisir d'espace
- **Hauteur du clavier** réglable (Paramètres du clavier) : 5 niveaux de 80 % à 120 % de la hauteur de référence (100 % par défaut), appliqués à la hauteur des touches ; la marge basse (zone système) n'est pas modifiée
- **Mode paysage** : clavier plus bas (rangées et marge basse raccourcies, réglage de hauteur plafonné à 100 % pour laisser de la place à l'application), sans mode plein écran : le champ de saisie reste visible ; les bulles ouvertes sont fermées à la rotation
- **Tablette / pliable (responsive)** : sous 600 dp de large (téléphone, pliable replié) le clavier occupe toute la largeur ; à partir de 600 dp (tablette, pliable déplié, multi-fenêtre) les touches profitent de la largeur, plafonnée à 720 dp, et la zone des touches est centrée (marges inertes, barre d'actions alignée). La largeur est celle de la fenêtre du clavier : elle suit le dépliage et la rotation
- **Frappe rapide (multi-touch)** : quand un doigt se pose avant que le précédent soit levé, la touche précédente est validée aussitôt, dans l'ordre, au lieu d'être perdue
- **Adaptation au type de champ** (story 1.18) : le clavier suit `EditorInfo.inputType` à chaque ouverture de champ (`FieldType`). **E-mail** : « @ » remplace la virgule (claviers de lettres et de symboles). **URL** : « / » remplace la virgule. **Numérique** et **téléphone** : pavé numérique (4 × 4 : chiffres, effacer, entrée ; numérique = `,` `.` `-` et espace ; téléphone = `+` `-` `*` `#`), sans bascule vers les lettres ni touche emoji. Dans les champs e-mail, URL, mot de passe et numériques : **pas d'autocorrection**, pas de majuscule automatique en début de champ, pas de « . » au double espace (la touche Maj reste utilisable à la main). L'indicateur `NO_SUGGESTIONS` posé par certaines applications (ex. Google Keep) est ignoré : suggestions de mots et autocorrection restent actives dans les champs de texte libre. La virgule, `@` et `/` restent accessibles par l'appui long sur le point. Les champs date/heure et inconnus gardent le clavier de texte
- **Dictionnaires FR/EN de ~50 000 mots avec fréquences** : la correction choisit d'abord le mot le plus proche, puis, à distance égale, le plus fréquent ; les mots personnels priment ; une apostrophe oubliée (jai, cest, dont) est rétablie (j'ai, c'est, don't) ; un **accent oublié** est rétabli (durees → durées, etre → être, ca → ça) et les **pluriels réguliers** absents des listes (durées, chevaux) ne sont pas « corrigés » ; les mots de 4 lettres ou moins ne sont corrigés qu'à une seule modification, et une inversion de deux lettres (teh → the) prime à distance égale
- **Suggestions de mots** (bande du haut, 3 emplacements à gauche de l'emoji) : pendant la frappe d'un mot, à partir des mêmes dictionnaires locaux et du dictionnaire personnel (pas d'IA, pas d'apprentissage). Si le mot tapé va être **corrigé à l'espace**, la bande affiche `“mot tapé”` à gauche (pour refuser la correction), la **correction au centre et en gras** (c'est elle qui remplace le mot à l'espace) et une complétion à droite. Sinon, ce sont de simples **complétions en poids normal** (mots personnels d'abord, puis fréquence décroissante). Toucher un mot le place (suivi d'une espace) ; après une autocorrection, une suppression immédiate rétablit le mot tapé. Rien n'est proposé après une espace ou une ponctuation, curseur au milieu d'un mot, avec une sélection, pendant une dictée / correction IA, ni dans les champs sans suggestions (mot de passe, e-mail, URL, nombre…)
- **Barre d'emojis récents** (champs de messagerie) : au-dessus de la barre du haut, une bande défilante reprend les emojis les plus récents (les mêmes que l'onglet « Récents » du panneau emoji). Toucher un emoji l'insère au curseur (il remplace une sélection) et le place en tête des récents ; la bande garde son ordre jusqu'à la prochaine ouverture de champ ou de panneau. Elle apparaît quand le champ est déclaré comme message (court ou long) ou que l'application est une messagerie connue (`MessagingFieldPolicy`), jamais dans les champs sans texte libre, ni quand un panneau (emoji, presse-papiers) est ouvert, ni tant qu'aucun emoji n'a été utilisé
- **Smart Clipboard, bouton d'accès** (story 2.1) : dans la barre du haut « avant saisie » (champ vide), un bouton **Presse-papiers** (icône « coller ») est visible à gauche, précédé d'une **roue crantée** qui ouvre la page d'accueil de l'application (cachée dès qu'il y a du texte et tant que le panneau est ouvert). Dès qu'il y a du texte, il est caché et la barre montre les suggestions de mots ; il reste accessible par le bouton **menu** (`···`, à gauche de la barre, visible seulement quand le champ contient du texte ou qu'un collage est proposé), et la frappe ramène aux suggestions de mots. Tant que la bande de mots est affichée, les boutons **Vocal** et **Corriger** sont rangés derrière un second menu `···`, à l'extrémité droite de la barre : l'ouvrir les affiche à la place de la bande (un seul menu est ouvert à la fois, la frappe referme les deux). Ils restent visibles pendant une écoute, une transcription ou une correction. Le bouton ouvre pour l'instant un panneau vide (coquille) sans bouton « ABC » : une croix ✕, à l'extrémité gauche de la barre du haut (les menus `···` et la roue crantée étant masqués), le referme et ramène aux touches ; le contenu du presse-papiers arrive avec les stories 2.2 et suivantes
- **Smart Clipboard, historique** (story 2.9) : les copies faites pendant que le clavier est actif (et la dernière copie relue à l'ouverture d'un champ) sont gardées **1 h après la copie**, dans la base Room chiffrée du presse-papiers (table `clip_history`, première migration Room v1 → v2). Plafond : 20 copies de 10 000 caractères au plus ; recopier un texte déjà présent le remonte en tête, sans doublon ; un contenu signalé sensible est stocké comme les autres (base chiffrée), mais son aperçu reste masqué et il ne peut pas être épinglé (story 2.10 : est sensible une copie signalée par l'application source, Android 13+, ou dont le texte entier est un numéro de carte bancaire — 13 à 19 chiffres, premier chiffre 2 à 6, clé de Luhn valide ; pas de détection de mot de passe par le contenu ; les éléments déjà épinglés ne sont pas masqués rétroactivement). Le panneau montre les copies récentes (de la plus récente à la plus ancienne), puis les éléments épinglés ; un texte déjà épinglé n'apparaît que comme carte épinglée. Chaque copie récente offre « Épingler », « Modifier » et « Supprimer ». La purge des copies expirées se fait au démarrage de la base, à chaque copie et à l'ouverture du panneau. **Navigation privée** (story 2.11) : aucun traitement spécifique, le presse-papiers fonctionne comme en usage normal ; les copies sensibles restent persistées (1 h) car la base est chiffrée
- Touches **effacer** (⌫, répétition maintenue) et **entrée** (⏎)
- Typé en Kotlin, `InputMethodService` + vue custom, sans aucune dépendance UI externe.

## Pourquoi pas un clavier existant ?

Des claviers open source AZERTY existent (FlorisBoard, HeliBoard, AnySoftKeyboard, OpenSwift/Nboard),
mais aucun n'est réellement « minimal » : ce sont de gros projets aux dépendances anciennes (Java,
AGP/Kotlin datés, AndroidX lourdes). Pour un clavier minimal avec la stack la plus à jour possible,
une implémentation propre a été préférée.

## Technique

- **Android Gradle Plugin** 9.4.0 (Kotlin intégré, AGP 9)
- **Gradle** 9.6.0 (wrapper inclus)
- **minSdk** 26, **compileSdk** 37.1, **targetSdk** 37
- **Room** 2.8.4 (KSP 2.3.11, schémas exportés dans `app/schemas/`, à commiter) + **SQLCipher** 4.18.0 ; clé aléatoire protégée par l'Android Keystore, base et clé exclues des sauvegardes
- **JUnit** Jupiter 6 / JUnit Platform 6.1.3 pour les tests unitaires
- Tests ciblés sur la logique pure : layouts (`KeyboardLayout`), largeur responsive (`KeyboardWidth`), contrôleur de saisie (`KeyboardController`), dictionnaires, dépôt du dictionnaire personnel (DAO en mémoire)

## Construction

```bash
./gradlew assembleDebug          # APK debug
./gradlew assembleRelease        # APK release (non signé)
./gradlew testDebugUnitTest      # tests unitaires
```

## CI (GitHub Actions)

`.github/workflows/android.yml` :

- **push sur `develop`** : tests unitaires + APK **debug** (artefact `apk-debug`)
- **push sur `main`** : tests unitaires + APK **release** (artefact `apk-release`)
- les tests unitaires sont lancés à chaque build