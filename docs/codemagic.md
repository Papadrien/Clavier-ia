# CI sur Codemagic

Mise en place le 16/11/2026, parce que le quota de GitHub Actions est épuisé. `codemagic.yaml` (racine du dépôt) reprend
`.github/workflows/android.yml` pour les builds **debug** : tests JVM (`testDebugUnitTest :core:test`), lint Android, ktlint et
detekt (`qualityCheck`), APK + AAB debug. La release signée et l'envoi sur Google Play restent sur GitHub Actions : Codemagic n'a
aucun secret, et ne touche pas au versionCode.

## Workflows

| Workflow | Déclencheur | Ce qu'il fait |
|---|---|---|
| `pr` | pull request vers `main` ou `develop` | tests, lint, ktlint/detekt, APK + AAB debug |
| `develop` | push sur `develop` | idem |
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
2. Aucune variable ni aucun secret à créer.
3. Lancer une fois `develop` à la main pour vérifier la machine (Java 21, SDK Android, cache).

## Points à vérifier au premier build (non testés ici)

- Java 21 (`environment: java: 21`) et le SDK Android préinstallé sur Mac M2 (`ANDROID_SDK_ROOT`), lus par Gradle sans `local.properties`.
- Robolectric sur Mac M2 (Apple Silicon) : les tests JVM ne lancent pas d'émulateur, ils devraient passer comme sur Linux.
- `instrumented-tests` : le nom de l'émulateur préinstallé (`emulator`) sur les machines Linux de Codemagic.
