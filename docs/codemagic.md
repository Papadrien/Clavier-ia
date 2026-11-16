# CI sur Codemagic

Mise en place le 16/11/2026, parce que le quota de GitHub Actions est épuisé. `codemagic.yaml` (racine du dépôt) reprend
`.github/workflows/android.yml` étape par étape : tests JVM (`testDebugUnitTest :core:test`), lint Android, ktlint et detekt
(`qualityCheck`), APK + AAB, signature et envoi sur Google Play.

## Workflows

| Workflow | Déclencheur | Ce qu'il fait |
|---|---|---|
| `pr` | pull request vers `main` ou `develop` | tests, lint, ktlint/detekt, APK + AAB debug |
| `develop` | push sur `develop` | idem |
| `release` | push sur `main` (merge d'une PR) | idem en release signée, puis envoi de l'AAB et de `mapping.txt` dans le canal **alpha** de Play |
| `instrumented-tests` | à la main uniquement | tests instrumentés sur émulateur (`connectedDebugAndroidTest`) |

Un nouveau commit sur la même branche annule le build en cours (`cancel_previous_builds`), pour économiser les minutes.

## Minutes

- Plan gratuit : **500 minutes par mois, uniquement sur Mac M2** (`mac_mini_m2`), remises à zéro le 1er du mois. Elles ne
  s'appliquent pas à une « Team » Codemagic : connecter le dépôt depuis le compte personnel.
- Les machines Linux sont payantes à la minute. L'émulateur Android ne tourne pas sur Mac M2 (pas de virtualisation imbriquée sur
  Apple Silicon) : `instrumented-tests` est donc sur `linux_x2` et ne part jamais tout seul. Le lancer exige d'activer la facturation.
- Une PR mergée dans `develop` coûte deux builds (la PR, puis le push sur `develop`). Pour économiser, on peut supprimer le workflow
  `develop` : la PR a déjà tout vérifié.

## Mise en place (une seule fois, dans l'interface de Codemagic)

1. Se connecter avec GitHub sur codemagic.io, ajouter l'application `Papadrien/Clavier-ia`, type « Other » / `codemagic.yaml`.
   Codemagic installe lui-même les webhooks GitHub (push et pull request).
2. **Environment variables** de l'application, deux groupes, chaque variable cochée **Secure** :
   - `android_signing` : `ANDROID_KEYSTORE_BASE64` et `ANDROID_KEY_PROPERTIES` (mêmes valeurs que les secrets GitHub, voir
     `docs/release.md`) ;
   - `google_play` : `PLAY_SERVICE_ACCOUNT_JSON` (même valeur que le secret de l'environnement GitHub `play`).
   Seul le workflow `release` importe ces groupes : une pull request ne peut pas les lire. Sans `android_signing`, le build release
   échoue franchement ; sans `google_play`, l'envoi sur Play est sauté.
3. Lancer une fois `develop` à la main pour vérifier la machine (Java 21, SDK Android, cache).

## versionCode

`VERSION_CODE` = `VERSION_CODE_BASE` (1000) + numéro de build Codemagic, lu par `app/build.gradle.kts`. La base dépasse les numéros
d'exécution GitHub déjà envoyés sur Play (243 au 16/11/2026) : Play refuse un versionCode déjà utilisé ou plus petit.

**Si l'on revient sur GitHub Actions** pour la release, ses numéros d'exécution (vers 250) seraient refusés par Play : fixer
`VERSION_CODE` au-dessus du dernier code envoyé par Codemagic, ou désactiver la release de l'un des deux.

## Points à vérifier au premier build (non testés ici)

- Java 21 (`environment: java: 21`) et le SDK Android préinstallé sur Mac M2 (`ANDROID_SDK_ROOT`), lus par Gradle sans `local.properties`.
- Robolectric sur Mac M2 (Apple Silicon) : les tests JVM ne lancent pas d'émulateur, ils devraient passer comme sur Linux.
- `instrumented-tests` : le nom de l'émulateur préinstallé (`emulator`) sur les machines Linux de Codemagic.
