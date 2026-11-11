# Dictionnaires téléchargés (hors APK)

Non compilé ni exécuté dans l'environnement de développement : à valider au premier build.

## Principe
Les listes de mots ne sont plus dans l'APK. Pour chaque langue (fr, en, es, de, it, pt, nl), 2 fichiers sont téléchargés depuis
le bucket R2 (même Worker que les modèles, `https://taipo-worker.junade-models.workers.dev/`) :
- `dictionaries/<code>.txt` (liste complète) et `dictionaries/names/<code>.txt` (prénoms).
(Les listes `<code>_50k.txt` de secours ont été supprimées le 04/11/2026 : le repli mémoire relit la liste complète avec un seuil de fréquence ; l'app efface les `_50k` déjà téléchargés. Elles peuvent être retirées du bucket une fois les anciennes versions de l'app retirées.)
(Dossier du bucket : « dictionaries », constante `DictionaryFiles.REMOTE_DIRECTORY` ; il s'appelait « dictionnaries » jusqu'au 07/11/2026.)
- Facultatif (français, anglais, italien, portugais, néerlandais) : `dictionaries/bigrams/<code>.txt` (paires de mots, voir `docs/autocorrection-roadmap.md`).
  Une langue est installée sans lui ; `BigramDownloadWorker` le télécharge au mieux (une tentative par processus, aucun message en cas
  d'échec) et le dictionnaire est relu au champ suivant. À déposer depuis `dictionaries/bigrams/`.

Stockage : `filesDir/dictionaries/` (même arborescence), exclu des sauvegardes. Une langue est « installée » quand les 2 fichiers
sont présents et non vides (rename atomique : un fichier n'apparaît qu'une fois complet).

## Quand le téléchargement démarre (`DictionaryDownloadScheduler.ensure`)
- ouverture de l'écran « Langues » (paramètres du clavier), ajout d'une langue, ouverture du détail d'une langue ;
- clavier : à l'ouverture d'un champ, si la langue active n'est pas installée (langue « Automatique » et mise à jour depuis une version qui
  embarquait les listes comprises) ; à l'arrivée des fichiers, la langue active se charge sans attendre (`SuggestionController`).
- Après un échec, seul « Réessayer » relance (pas de requête à chaque champ).
Sans connexion : le travail WorkManager attend (« En attente de connexion… »). Données mobiles : pas de confirmation (quelques Mo).

## Technique
`DictionaryDownloadWorker` réutilise `ModelFileFetcher` (HTTPS, `.tmp`, reprise `Range`, relances). **Empreinte SHA-256 vérifiée**
(audit SEC-03, 11/11/2026) : `DictionaryChecksums` fige celle de chaque liste et des bigrammes ; `DictionaryChecksumsTest` la relie aux
fichiers de `dictionaries/`. `DictionaryContentCheck` vérifie en plus la forme (« mot fréquence »). `DictionaryLoader` lit les fichiers ;
une langue non installée n'est pas mise en cache (dictionnaire vide, autocorrection absente) et se charge dès qu'elle est installée.
États : `DictionaryDownloadTracker` (Queued / Downloading % / Failed / Installed). Écran : carte « Dictionnaire » du détail d'une langue
(`LanguageDetailActivity`, `DictionaryStatusSection`) ; pastille « À télécharger » sur la ligne de la langue (`LanguagesActivity`).

## Source des fichiers
`dictionaries/` à la racine du projet (hors app) : ce qu'il faut envoyer sur R2 ; relu par `DictionaryAssetsIntegrityTest` et
`ProperNamesAssetsTest`. `tools/fetch_dictionaries.py` y écrit désormais.

## Limites / à valider
- Pas de mise à jour automatique d'une liste modifiée sur le serveur (fichier déjà installé = gardé).
- Texte de confidentialité mis à jour (section 2) ; mentionner ce téléchargement dans la fiche Play (sécurité des données) si besoin.
- [ ] `./gradlew testDebugUnitTest assembleRelease` ; taille de l'APK avant/après.
- [ ] Installation fraîche en Wi-Fi : choisir une langue, voir 0 → 100 %, puis correction active sans relancer.
- [ ] Mode avion : « En attente de connexion… » puis reprise au retour du réseau ; URL erronée : échec + Réessayer.

## Changer une liste sur le serveur (depuis l'audit du 11/11/2026)
Les versions publiées refusent un fichier dont l'empreinte a changé. Pour publier une nouvelle liste :
1. modifier `dictionaries/` et mettre à jour `DictionaryChecksums` (le test donne les nouvelles valeurs) ;
2. envoyer **tout le dossier** dans un nouveau dossier du bucket (par exemple `dictionaries-2026-12-01/`, mêmes chemins) ;
3. changer `DictionaryFiles.REMOTE_DIRECTORY`, publier la version ;
4. garder l'ancien dossier tant que des versions anciennes sont en circulation.

## Liste de démarrage embarquée (audit UX-01)
Français et anglais : les 40 000 premiers mots de la liste complète sont dans l'APK (`assets/dictionaries/starter/`, ~220 Ko
compressés chacun). Ils servent tant que la liste complète manque (installation hors connexion) ; elle les remplace à son arrivée.
Régénérer : `head -40000 dictionaries/<code>.txt > app/src/main/assets/dictionaries/starter/<code>.txt` (`StarterDictionaryTest`
le vérifie). Les autres langues restent sans dictionnaire jusqu'au téléchargement.
