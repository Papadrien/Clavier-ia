# Premier build — validation sur appareil

Statut (03/10/2026, retour d'Adrien) : le socle IA (LiteRT-LM : correction, génération en streaming)
et la dictée (sherpa-onnx) ont tourné et ont été vérifiés sur appareil. Les avertissements
« à vérifier au premier build » ont donc été retirés des commentaires du code (lot 1.1 du plan de revue).

Reste à faire à la main (impossible depuis l'environnement de revue, sans build) :

- [ ] Committer `app/schemas/*.json` exportés par Room (voir `room { schemaDirectory(...) }`).
- [ ] Relever la version résolue de `litertlm-android` (`./gradlew :app:dependencies --configuration debugRuntimeClasspath`)
      et la figer dans `app/build.gradle.kts` (lot 1.2).
