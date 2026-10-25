# Écran « Licences » (audit 1.9)

Accessible depuis l'accueil, section « À propos ». `LicensesActivity` affiche :

1. **Modèles IA** : cartes Gemma 3 (Gemma Terms of Use) et Gemma 4 (Apache 2.0, texte complet dépliable) écrites dans
   `activity_licenses.xml` (story 8.11) ; puis **Nemotron** (modèle vocal).
2. **Dictionnaires** : FrequencyWords / OpenSubtitles 2018, CC BY-SA 4.0, avec les modifications apportées.
3. **Bibliothèques et police** : LiteRT-LM, sherpa-onnx, ONNX Runtime, SQLCipher, AndroidX, Kotlin/coroutines, Aptabase, Open Sans.

Les mentions de 1 et 3 (hors Gemma) sont déclarées dans `licenses/LicenseNotice.kt` (`LicenseNotices.all`), une carte chacune,
textes dans `strings.xml` (clés `licenses_*`, EN + FR). **Ajouter une dépendance** = ajouter une entrée dans cette liste
(+ ses deux chaînes `licenses_usage_*`). `LicenseNoticesTest` vérifie unicité, liens HTTPS, parité EN/FR et présence du texte OFL.

## Vérifié le 15/10/2026 (fiches officielles)
| Composant | Licence | Source |
|---|---|---|
| Nemotron 3.5 ASR Streaming | OpenMDW-1.1 (« "Nemotron" model family © NVIDIA CORPORATION & AFFILIATES ») | fiche Hugging Face `nvidia/nemotron-3.5-asr-streaming-0.6b` |
| FrequencyWords | contenu CC BY-SA 4.0, code MIT | README du dépôt `hermitdave/FrequencyWords` |
| LiteRT-LM, sherpa-onnx | Apache 2.0 | dépôts GitHub |
| SQLCipher Community Edition | licence BSD-style de Zetetic ; **exige attribution et reproduction de la licence dans l'interface de l'app** (écran « à propos » accepté) | zetetic.net/sqlcipher/license |
| Aptabase SDK Kotlin | MIT | dépôt GitHub |
| Open Sans | SIL OFL 1.1 | `licenses/OpenSans-OFL.txt` (copie dans `assets/licenses/open-sans-ofl.txt`) |

## À faire / à vérifier avant la soumission
- **Texte complet OpenMDW-1.1** : la licence demande de conserver une copie du texte dans la distribution. Je n'ai pas pu le
  récupérer mot pour mot (accès réseau limité), donc il n'est pas dans l'APK. Déposer le texte officiel
  (https://openmdw.ai/license/1-1/) dans `app/src/main/assets/licenses/openmdw-1.1.txt` : le bouton « Afficher le texte complet »
  apparaît tout seul sur la carte Nemotron. Penser aussi à le joindre au bucket qui héberge les fichiers du modèle vocal.
- **MIT (ONNX Runtime, Aptabase) et SQLCipher** : les licences demandent de reproduire la ligne de copyright et le texte. L'écran
  donne le nom, un lien et (ONNX Runtime, SQLCipher) la ligne de copyright, sans le texte complet ni l'année ; les lignes exactes
  (année comprise) n'ont pas pu être relues. À compléter avec les `LICENSE` des dépôts si tu veux le texte intégral dans l'app.
- **Fichiers NOTICE Apache 2.0** : non vérifiés pour LiteRT-LM, sherpa-onnx, AndroidX. Si un dépôt en fournit un, ajouter son contenu.
- **Export du modèle vocal** : la mention suppose les poids NVIDIA (OpenMDW-1.1). Si le fichier hébergé vient d'un export tiers,
  vérifier que ce dépôt ne change pas la licence ni la mention.
- **CC BY-SA 4.0 et partage à l'identique** : la mention dit que les listes adaptées sont partagées sous CC BY-SA 4.0. Ce que
  cela implique pour le reste de l'app (et le statut des sous-titres d'origine) est une question juridique : avis d'un juriste.
- Hors écran, non traités : bibliothèque Play In-App Review (conditions Google), emojis (liste maintenue à la main).
