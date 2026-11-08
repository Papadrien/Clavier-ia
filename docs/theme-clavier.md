# Thèmes du clavier (plan et état, 01/11/2026, après le lot T7)

## Périmètre

- Le thème ne concerne que **le clavier** (touches, barre du haut, suggestions, panneaux emoji / Smart Clipboard, mode prompt).
- Les **écrans de l'application restent toujours sombres** (`KeyboardTheme.wrap` force le mode nuit), de même que les fenêtres de
  dialogue lancées par le clavier (`ClipboardEditActivity`, `ClipboardLabelActivity`).
- minSdk reste **26** (décision du 01/11/2026).
- Première étape : thèmes **Clair**, **Sombre**, **Système, automatique**. « Couleur dynamique » (Android 12+) vient ensuite.
  « Mes thèmes » et « Couleurs » de la page Thème sont repoussés (personnalisation = pack premium, voir le contexte projet).

## Mécanisme

- Deux palettes de **mêmes noms** : `res/values-night/colors.xml` (sombre, valeurs d'avant) et `res/values/colors.xml` (claire).
- `ThemeChoice` (`SYSTEM`, `LIGHT`, `DARK`) est le réglage, stocké dans `KeyboardPreferences.themeChoice` (clé `theme_choice`).
  `KeyboardTheme.choice` en garde la valeur pour toutes les vues ; `KeyboardTheme.mode` donne la palette effective (en
  `SYSTEM`, d'après la configuration globale de l'appareil).
- `KeyboardTheme.color` / `themeColor()` résolvent avec une configuration où le mode nuit est forcé selon `mode` ;
  `KeyboardTheme.themed(context)` donne un contexte équivalent pour charger une ressource XML qui contient une couleur depuis le
  service du clavier (icône non teintée, thème de dialogue).
- Nouveaux rôles : `text_on_accent` (blanc dans les deux palettes : texte et icônes sur fond violet ou rouge), `accent_on_key`
  (accent dessiné **sur** une touche ou un bouton gris : violet clair `#C58BFF` en sombre, violet plein `#8C00FF` en clair),
  `splash_background` et `ic_launcher_background` (fixes `#0E0E0E` : résolus par le système, hors du thème du clavier).
- Contenu sur une face colorée (T6) : `FaceContent.colorOn(face)` décide (blanc sur accent, envoi, danger, sélection de la bulle,
  étiquette et action principale du presse-papiers ; couleur du texte du thème sinon). `BarStyle.contentTint(face)` en donne la teinte
  d'icône ; les boutons dont la face change (Vocal, robot, Smart Clipboard, Chat du prompt, bouton d'envoi, `LabeledIconButton`)
  la reposent à chaque changement d'état.

## Couleur dynamique (T7)

- `ThemeChoice.DYNAMIC` (clé `dynamic`) : claire ou sombre selon l'appareil, comme `SYSTEM`, mais avec les tons du fond d'écran. Proposée seulement dès Android 12 (`ThemeChoice.DYNAMIC_MIN_SDK = 31`) ; un réglage dynamique lu sur un appareil plus ancien se comporte comme `SYSTEM` (`usesDynamicColors`).
- Les dossiers `values-desk-v31` et `values-desk-night-v31` ne redéfinissent que la section « Charte » (11 couleurs) en `@android:color/system_*`. Les rôles suivent seuls.
- **Qualificatif détourné** : les écrans de l'application doivent rester violets, or `v31` seul s'appliquerait partout. `KeyboardTheme.configuration` force donc le type d'interface à « desk » pour la couleur dynamique et à « normal » pour tout le reste (écrans compris, même sur un appareil réellement en station d'accueil) ; seul le clavier en couleur dynamique choisit ces dossiers.
- Tons : clair = fond neutral1_100, touches neutral1_10 / neutral1_200, texte neutral1_900 ; sombre = fond neutral1_1000, touches accent1_800 / neutral1_800, texte neutral1_50 ; accent = accent1_600 dans les deux (texte blanc dessus). `accent_on_key` en clair reprend l'accent, en sombre accent1_200.
- `KeyboardTheme.look` (mode + dynamique) remplace `mode` pour décider de recréer les vues ; le cache de ressources est vidé à chaque changement de configuration (un changement de fond d'écran passe par là).
- Page « Thème » : cartes par rangées de trois ; la quatrième (Couleur dynamique) est seule sur la deuxième rangée. L'aperçu montre moitié claire, moitié sombre, tirées du fond d'écran.
- Les pastilles Bitwarden gardent leur fond fixe (clair `#CDCDD3` ou sombre `#2B2B2B`) : elles sont lues hors du thème du clavier.
- Les tons dépendent du fond d'écran : les contrastes ne se testent pas hors appareil (`AccessibilityContrastTest` vérifie seulement la structure des deux fichiers).

## Palette claire (valeurs)

Accents violets inchangés (`#8C00FF`, ombre `#6800AB`, clair `#C58BFF`). Fond `#E4E4E8` ; touches `#F2F2F4` (ombre `#B8B8C0`) ;
touches secondaires `#CDCDD3` (ombre `#A4A4AC`) ; texte `#1B1B1F` ; texte atténué `#B31B1B1F` ; barre d'espace `#5F5F66`.
Contrastes vérifiés par `AccessibilityContrastTest` sur les deux palettes (4,5:1 pour le texte, 3:1 pour les éléments graphiques).

## Lots

| Lot | Contenu | État |
|---|---|---|
| T1 | Séparer clavier et écrans ; `ThemeChoice`, réglage, `KeyboardTheme.mode` dynamique, cache invalidé au changement | fait (non compilé) |
| T2 | Réglage `themeChoice` + lecture au démarrage (`TaipoApplication`) | fait (non compilé) |
| T3 | Palette claire, `text_on_accent`, fonds fixes du lanceur et du splash, tests des deux palettes | fait (non compilé) |
| T4 | Recomposition de l'IME : à l'ouverture d'un champ si le thème effectif a changé (`TaipoIme.recomposeIfThemeChanged`) ; au passage clair / sombre de l'appareil, par la recréation de vue qu'Android fait déjà dans `onConfigurationChanged` (le service donne d'abord sa configuration à `KeyboardTheme.updateSystemConfiguration`) | fait (non compilé) |
| T5 | Page « Thème » (`ThemeActivity`) : ligne dans l'accueil (sous-titre = thème choisi), trois cartes d'aperçu avec coche (Système automatique, Clair, Sombre), choix enregistré tout de suite ; `ThemePreviewView` dessine un mini clavier avec les couleurs réelles de chaque palette (`KeyboardTheme.colorIn`) | fait (non compilé) |
| T6 | Passage en clair du clavier : texte et icônes sur violet ou rouge en `text_on_accent` (touche Entrée et son globe, bouton d'envoi, bulle de l'utilisateur dans le chat, cellule sélectionnée de la bulle d'appui long, étiquette d'épingle, action principale du presse-papiers, boutons violets ou rouges de la barre), `accent_on_key` au lieu de `taipo_purple_light` sur les touches claires (chiffres d'appui long, trait de la barre d'espace, étincelles), pastilles d'auto-remplissage claires (`#CDCDD3`) ; puis `ThemeChoice.DEFAULT = SYSTEM` | fait (non compilé) |
| T7 | Couleur dynamique (Android 12+, carte masquée en dessous) : `ThemeChoice.DYNAMIC`, palettes `values-desk-v31` (claire) et `values-desk-night-v31` (sombre) en tons `system_accent1_*` / `system_neutral1_*`, quatrième carte de la page « Thème » | fait (non compilé) |

## Page « Thème » (T5)

- Accès : accueil > carte « Clavier » > ligne « Thème » (entre « Paramètres du clavier » et « Dictionnaire personnel »).
- Trois cartes de même largeur ; la carte choisie a un contour violet et une coche. « Système » montre les deux palettes côte à côte (clair à gauche, sombre à droite). TalkBack lit chaque carte comme un bouton radio (« Système, automatique », « Clair », « Sombre »).
- Le choix s'applique à la prochaine ouverture d'un champ (T4) ; la page reste sombre.
- Mesure d'usage : `setting_changed` avec `key = theme_choice` et le mot-clé du thème (voir `docs/analytics.md`).
- Géométrie de l'aperçu testée par `ThemePreviewGeometryTest` ; le dessin lui-même est à juger à l'écran.

## Couleur d'accent (section « Couleur », 08/11/2026)

- Neuf palettes (`AccentPalette`) : le violet de la charte (par défaut) et Indigo, Bleu vif, Lagon, Émeraude, Ambre, Framboise,
  Magenta, Graphite. Chacune donne les cinq tons que la charte tire du violet : principale (`taipo_purple`), secondaire
  (`taipo_purple_shadow`), accent sur touche sombre (`taipo_purple_light`), touche sombre et son ombre (`taipo_key`,
  `taipo_key_shadow` de la palette sombre, gris teintés de l'accent).
- Mécanisme : `KeyboardTheme.color` passe chaque couleur résolue dans `AccentPalette.recolor`, qui remplace un ton du violet par celui
  de la palette. Les rôles suivent sans être énumérés. La palette fait partie de `KeyboardTheme.Look` : la changer recrée les vues du
  clavier à la prochaine ouverture d'un champ, comme le thème. Sans effet en couleur dynamique. Les écrans de l'application restent violets.
- Contrastes gardés par `AccentPaletteTest` : blanc sur principale ≥ 4,5:1, principale sur touche claire ≥ 3:1, accent sur touche
  sombre ≥ 4,4:1 (niveau du violet).
- Pass premium : toutes les couleurs sauf le violet sont réservées (`AccentPalette.requiresPremium`), voir `docs/pass-premium.md`.
- Réglage `accent_palette` (préférences, sauvegarde `accentPalette`, mesure d'usage `setting_changed`).

## Limites connues (après T6)

- `ThemeChoice.DEFAULT` est **Système, automatique** : un utilisateur qui n'a jamais ouvert la page « Thème » voit le clavier suivre le thème de l'appareil (clair le jour, sombre la nuit), donc change d'aspect à la mise à jour s'il utilise l'appareil en clair.
- Contrastes mesurés (voir `AccessibilityContrastTest`) : `accent_on_key` en clair fait 5,3:1 sur une touche normale et 3,7:1 sur une touche secondaire (grise, lettres) : suffisant pour un élément graphique, pas pour un texte long ; ce ne sont que des indices (chiffres) et un trait.
- Le thème est dans la sauvegarde locale (`themeChoice` de `BackupKeyboardSettings`, clé de stockage ; format toujours en version 1, la clé est facultative et une sauvegarde plus ancienne la laisse absente). Une clé inconnue est ignorée ; « dynamic » restauré sur un appareil d'avant Android 12 se comporte comme « system ».
- Le splashscreen suit le thème de l'appareil pour son fond mais ce fond est fixe (`#0E0E0E`) : aucun saut de couleur.

## À vérifier sur l'appareil (lot 7)

- Android 12+ : la page « Thème » montre quatre cartes (la dernière, seule sur sa rangée) ; avant Android 12, trois.
- « Couleur dynamique » : le clavier prend les couleurs du fond d'écran, clair ou sombre selon l'appareil ; changer de fond d'écran puis rouvrir un champ le repeint.
- Contrastes avec plusieurs fonds d'écran (jaune, bleu, rouge, très sombre) : texte blanc sur l'accent, libellés sur les touches, indices, trait de la barre d'espace.
- Les écrans de l'application restent violets et sombres, y compris avec le thème dynamique choisi.
- Passer le téléphone en mode nuit clavier ouvert : le clavier dynamique se recrée en sombre.

## À vérifier sur l'appareil (lot 6)

- Thème « Clair » : l'icône Entrée (et son globe en mode multilingue) est blanche sur la pilule violette.
- Barre du haut en clair : robot (menu ouvert), Smart Clipboard (panneau ouvert), Corriger en cours, Transcription IA en traitement et Vocal en écoute ont une icône blanche sur le violet ou le rouge ; au repos, l'icône est sombre sur le gris.
- Mode prompt : flèche d'envoi blanche sur le violet, icône sombre sur le gris pendant la génération (stop) ; bouton Chat violet avec icône blanche ; bulle de l'utilisateur violette avec texte blanc.
- Appui long sur une lettre : la cellule sélectionnée est violette avec le symbole blanc (et le globe de la touche Entrée, blanc lui aussi).
- Chiffres d'appui long en haut des touches, glissement de la barre d'espace (étirement du trait) : violet plein lisible sur les touches claires.
- Smart Clipboard : étiquette d'une carte épinglée (texte blanc sur violet) et bouton « Épingler » (blanc sur violet).
- Champ de mot de passe avec Bitwarden en clair : pastilles grises `#CDCDD3`, titre sombre, sous-titre gris foncé.
- Sans toucher à la page « Thème » (installation neuve ou réglage jamais écrit) : le clavier suit le thème de l'appareil.

## À vérifier sur l'appareil (lot 5)

- La ligne « Thème » est dans la carte « Clavier » de l'accueil et son sous-titre suit le choix au retour de la page.
- Les trois aperçus montrent bien clair, sombre, et clair | sombre ; la coche et le contour passent à la carte touchée.
- Choisir « Clair », ouvrir un champ : le clavier est clair (texte sur violet encore à corriger en T6).
- TalkBack : chaque carte est annoncée comme un bouton radio coché ou non.
- Petit écran / grande police : les libellés des trois cartes restent lisibles.

## À vérifier sur l'appareil (lot 4)

- Changer le réglage clavier masqué, puis rouvrir un champ : le clavier doit avoir le nouveau thème dès la première frappe.
- En `SYSTEM`, basculer le thème de l'appareil clavier ouvert : le clavier se recrée dans la nouvelle palette.
- Mode prompt actif, panneaux emoji et Smart Clipboard : ils reviennent aux touches après une recomposition (comme à une rotation).
