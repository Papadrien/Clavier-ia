# Clavier-ia

Clavier Android natif minimal en français (AZERTY) : on tape simplement sur les touches pour écrire.

- Lettres AZERTY (avec touche Maj, une seule lettre en majuscule)
- Chiffres et symboles (bascule `123` / `ABC`) ; **rangée de chiffres** optionnelle au-dessus des lettres (Paramètres du clavier) ; si elle est désactivée, les chiffres restent accessibles via `123` ou par **appui long** sur les touches de la rangée du haut (1 à 0)
- Ponctuation (`?`, `,`, `.`, `!`, accents français `é è ç à`, apostrophe, parenthèses…)
- **Dictionnaire personnel** (écran « Dictionnaire personnel ») : mots ajoutés à la main, jamais corrigés par l'autocorrection locale et utilisés comme candidats de correction ; stocké dans une base **Room chiffrée par SQLCipher**
- **Appui long sur une lettre** : bulle d'accents et caractères spéciaux (é è ê ë, à â æ, ç, ô œ, ù û ü, ñ, ß…), sélection en glissant le doigt puis en le relevant sur le caractère voulu ; les lettres du haut proposent leur chiffre en premier quand la rangée de chiffres est désactivée ; les caractères passent en majuscule avec Maj
- **Barre espace** : glisser le doigt dessus déplace le curseur (un caractère par pas), sans saisir d'espace
- **Hauteur du clavier** réglable (Paramètres du clavier) : 5 niveaux de 80 % à 120 % de la hauteur de référence (100 % par défaut), appliqués à la hauteur des touches ; la marge basse (zone système) n'est pas modifiée
- **Mode paysage** : clavier plus bas (rangées et marge basse raccourcies, réglage de hauteur plafonné à 100 % pour laisser de la place à l'application), sans mode plein écran : le champ de saisie reste visible ; les bulles ouvertes sont fermées à la rotation
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
- Tests ciblés sur la logique pure : layouts (`KeyboardLayout`), contrôleur de saisie (`KeyboardController`), dictionnaires, dépôt du dictionnaire personnel (DAO en mémoire)

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