# Dictionnaires téléchargés (hors APK)

Non compilé ni exécuté dans l'environnement de développement : à valider au premier build.

## Principe
Les listes de mots ne sont plus dans l'APK. Pour chaque langue (fr, en, es, de, it, pt, nl), 3 fichiers sont téléchargés depuis
le bucket R2 (même Worker que les modèles, `https://taipo-worker.junade-models.workers.dev/`) :
- `dictionnaries/<code>.txt` (liste complète), `dictionnaries/<code>_50k.txt` (secours), `dictionnaries/names/<code>.txt` (prénoms).
(Orthographe du dossier du bucket : « dictionnaries », constante `DictionaryFiles.REMOTE_DIRECTORY`.)

Stockage : `filesDir/dictionaries/` (même arborescence), exclu des sauvegardes. Une langue est « installée » quand les 3 fichiers
sont présents et non vides (rename atomique : un fichier n'apparaît qu'une fois complet).

## Quand le téléchargement démarre (`DictionaryDownloadScheduler.ensure`)
- ouverture de l'écran « Langues » (paramètres du clavier), ajout d'une langue, ouverture du détail d'une langue ;
- clavier : à l'ouverture d'un champ, si la langue active n'est pas installée (langue « Automatique » et mise à jour depuis une version qui
  embarquait les listes comprises) ; à l'arrivée des fichiers, la langue active se charge sans attendre (`SuggestionController`).
- Après un échec, seul « Réessayer » relance (pas de requête à chaque champ).
Sans connexion : le travail WorkManager attend (« En attente de connexion… »). Données mobiles : pas de confirmation (quelques Mo).

## Technique
`DictionaryDownloadWorker` réutilise `ModelFileFetcher` (HTTPS, `.tmp`, reprise `Range`, relances), **sans SHA-256** (les listes évoluent
sur le serveur) : seul `DictionaryContentCheck` vérifie la forme (« mot fréquence ») avant installation. `DictionaryLoader` lit les fichiers ;
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
