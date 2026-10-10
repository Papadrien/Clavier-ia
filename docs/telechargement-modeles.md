# Téléchargement des modèles IA (épopée 8, premier lot)

Non compilé ni exécuté dans l'environnement de développement (pas de réseau ni de compilateur Kotlin) : à valider au
premier build.

## Ce qui est fait
- **8.1** (partiel) : écran « Modèle IA » (`ModelDownloadActivity`), 4 lignes, statuts *Télécharger / Téléchargement… % /
  Vérification / Réessayer / Installé*, choix du modèle actif, accueil mis à jour (statut et sous-titre du modèle actif).
- **8.2** : `ModelRecommendation` (pur, testé) lit `totalMem` : < 4 Go → Léger (badge « Recommandé »), de 4 à 7 Go →
  Équilibré, 7 Go et plus → Performant. Seuils = constantes nommées. **Seuil Performant (7 Go) provisoire** : à fixer
  après tests sur le Pixel 9 et un appareil milieu de gamme. Le badge passe après « Actif » / « Installé » et ne
  bloque aucun téléchargement.
- **8.3** : Wi-Fi par défaut, confirmation en données mobiles (taille affichée), arrêt vers *Réessayer* si le Wi-Fi est
  perdu sans accord, message sans connexion. Service au premier plan `dataSync` (WorkManager) avec notification et
  bouton Annuler.
- **8.4** : contrôle `StatFs` avant le téléchargement (taille habituelle + 200 Mo), refait avec le `Content-Length` réel.
- **8.5** : sans modèle utilisable, Corriger (champ entier ou texte sélectionné) et Générer ouvrent l'écran « Modèle IA »
  (`ModelScreenRedirect`, nouvelle tâche car le clavier est un service) au lieu d'afficher un message ; le texte du champ
  ou du prompt n'est pas touché. « Utilisable » = modèle actif ET fichier présent (`ModelAvailability`, testé en JVM) :
  un modèle choisi dont le fichier a disparu redirige aussi. **Vocal** : il utilise le modèle vocal (4 fichiers) ;
  sans modèle vocal complet, le bouton ouvre la section « Modèle vocal » de l'écran « Modèle IA »
  (`ModelScreenRedirect.openVoice`, nouvelle tâche, défilement automatique) au lieu d'un message (voir 8.15).
  Les chaînes `correction_no_model_selected` et `voice_no_model_selected` sont supprimées. Pour 8.13 : supprimer le dernier modèle doit retirer le modèle
  actif ou laisser `isInstalled` à faux, la redirection suit alors d'elle-même.
- **8.6** : Corriger et Générer n'affichent plus le message brut d'une exception. `InferenceFailureClassifier` (pur,
  testé en JVM) classe l'erreur en 5 causes, chacune avec sa phrase (`inference_error_*`) : fichier modèle introuvable ou
  illisible, mémoire insuffisante, modèle incompatible ou corrompu, texte trop long, erreur inattendue. Exceptions typées
  `ModelFileException` (résolveur) et `ModelLoadException` (échec d'initialisation du moteur). Le détail technique et la
  cause restent dans `AppLog` (debug seulement, sans le texte saisi). **Heuristique** : « mémoire » et « texte trop long »
  venant du moteur LiteRT-LM sont reconnus par mots-clés du message (à confirmer avec les vrais messages sur appareil) ;
  un échec de chargement qui n'est pas un manque de mémoire est présenté comme « incompatible ou corrompu ». Les chaînes
  `correction_error` et `generation_error` sont supprimées. Transcription IA (épopée 14) : même classe à réutiliser.
- **8.8** (partiel) : `AiModel.downloadUrl` et `approxDownloadBytes` ; HTTPS obligatoire ; la taille annoncée doit rester
  dans la plage du modèle (protège d'une mauvaise variante).
- **8.9** : `.tmp`, SHA-256 pendant le flux, rename atomique, aucun partiel pris pour un modèle valide, taille recontrôlée
  au chargement du moteur, fichier jamais recopié.
- **8.12** : même emplacement `models/<id>.litertlm` pour les deux origines, le dernier installé remplace l'autre. La ligne
  « Modèle IA local » (et son séparateur) n'est affichée sur l'accueil qu'en build **debug** (`LocalModelAccess`,
  `BuildConfig.DEBUG` ; release et benchmark : seule la ligne « Modèle IA »). `ModelSettingsActivity` n'est pas modifiée et reste
  déclarée au manifeste. Sur l'écran « Modèle IA », un modèle installé à la main s'affiche « Fichier local (debug) » (pas de
  vérification SHA-256).
- **8.11** : licences des modèles. `ModelLicense` (pur, testé) associe chaque modèle à sa licence : Gemma 3 (270M, 1B) →
  *Gemma Terms of Use* + *Prohibited Use Policy* ; Gemma 4 (E2B, E4B) → Apache 2.0. Avant le premier téléchargement d'un
  Gemma 3, une fenêtre affiche la mention avec les liens (`ai.google.dev/gemma/terms`, politique d'usage interdit) et
  demande d'accepter (`ModelLicenseAcceptance`, une fois par licence ; « Annuler » ne télécharge rien). Chaque ligne de
  l'écran « Modèle IA » indique sa licence. Nouvel écran **« Licences »** (`LicensesActivity`), accessible depuis une section
  « À propos » de l'accueil : mentions Gemma 3, texte complet d'Apache 2.0 (`assets/licenses/apache-2.0.txt`, à déplier).
  **Hors code** (restent à faire) : fichier « Notice » côté serveur avec les modèles Gemma 3, restrictions d'usage dans
  les conditions d'utilisation de l'app (aucun écran de conditions n'existe encore), relecture des textes officiels avant
  la soumission Play (pas un avis juridique). URLs, phrase de notice et texte Apache 2.0 vérifiés sur ai.google.dev le 10/10/2026 (`/gemma/terms`, `/gemma/prohibited_use_policy`, `/gemma/apache_2`). Les conditions Gemma exigent aussi que les restrictions d'usage figurent comme clause applicable dans les conditions de l'app (§2.2) et qu'un fichier « Notice » accompagne toute distribution (§2.2). Un modèle téléchargé en debug sans empreinte n'est pas concerné.
- **Conditions d'utilisation** (suite de la 8.11) : écran `TermsActivity` (accueil > À propos), sections dans `TermsContent` /
  `strings.xml` (`terms_section_<n>_*`). La section 4 reprend les restrictions de la Gemma Prohibited Use Policy, comme
  l'exigent les Gemma Terms of Use (§2.2). La fenêtre de licence avant téléchargement renvoie vers cet écran. **Texte rédigé
  sans éditeur, contact ni droit applicable** : à compléter, et à faire relire (pas un avis juridique). Une politique de
  confidentialité Play reste à fournir séparément.
- **8.15** : téléchargement du modèle vocal. Section « Modèle vocal » en bas de l'écran « Modèle IA » (une ligne, mêmes statuts
  *Télécharger / Téléchargement… % / Vérification / Réessayer / Installé / Mise à jour disponible*, bouton « Supprimer »
  avec confirmation ; pas de « modèle actif », il n'y en a qu'un). Le modèle vocal = 4 fichiers (`VoiceModelFile` : encoder,
  decoder, joiner, tokens), téléchargés **l'un après l'autre par un seul travail** (`VoiceModelDownloadWorker`, un seul état
  dans `DownloadTracker`, progression globale `VoiceModelDownload.overallPercent`). Chaque fichier passe par le même
  mécanisme sûr que les modèles de texte, maintenant partagé dans `ModelFileFetcher` (HTTPS jusqu'au bout, `Content-Length`,
  contrôle d'espace avec la vraie taille, arrêt propre si le Wi-Fi est perdu, `.tmp`, SHA-256 pendant le flux, rename
  atomique) ; le worker des modèles de texte l'utilise aussi (refactorisation sans changement de comportement) et la
  notification du service au premier plan est partagée (`DownloadNotification`, identifiant 4102 pour le vocal).
  Règles : Wi-Fi par défaut / confirmation données mobiles / contrôle `StatFs` sur la taille indicative de ce qu'il reste à
  télécharger (`DownloadPolicy.decideVoice`) ; en release, aucun téléchargement tant qu'**une** des 4 empreintes manque ;
  en debug, le téléchargement est permis et chaque empreinte réelle est écrite dans logcat (tag `VoiceModelDownloadWorker`,
  ligne « SHA-256 de voice/<fichier> ») pour être figée dans `VoiceModelFile.sha256`. Reprise au fichier près : « Réessayer »
  saute les fichiers déjà installés et à jour (la reprise au milieu d'un fichier reste reportée en V2). Un fichier déjà
  téléchargé dont l'empreinte enregistrée diffère de celle du catalogue passe en *Mise à jour disponible* (8.14 appliquée au
  vocal ; badge « Mise à jour » sur l'accueil) ; un fichier fourni à la main (debug) est remplacé par le téléchargement.
  Stockage : `filesDir/voice-model/<id>.onnx` (`tokens.txt`), jamais recopié ; au chargement, la taille du fichier téléchargé
  est recontrôlée (`VoiceModelFileResolver`). Les fichiers sont recréés si absents (statut *Télécharger*, accueil « À télécharger »).
  Suppression : `VoiceEngine.releaseEverywhere()` ferme d'abord les moteurs de dictée du processus (clavier compris), puis
  `VoiceModelPreferences.clearAndDeleteAll()` supprime les 4 fichiers, leurs `.tmp`/`.sha256` et les références ; ensuite le bouton
  Vocal redirige vers la section (8.5). **Debug** : l'écran de chargement à la main (`VoiceModelSettingsActivity`, « Modèle vocal
  local » sur l'accueil, visible en debug seulement via `LocalModelAccess`) est conservé ; choisir un fichier supprime la copie
  interne précédente (correction : avant, une copie déjà faite était réutilisée à la place du nouveau fichier) et ferme le moteur.
  Accueil : la ligne « Modèle vocal » ouvre la section ; pastille « À télécharger » / « n/4 fichiers » / « Prêt » / « Mise à jour ».
  **À fournir ou à valider par Adrien (rien n'a été deviné côté serveur)** :
  (1) **URL et noms de fichiers** : `VoiceModelFile` suppose `https://taipo-worker.junade-models.workers.dev/voice/v1/` +
  `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt` : à aligner sur ce qui est réellement hébergé ;
  (2) **les 4 empreintes SHA-256** (`VoiceModelFile.sha256`, null pour l'instant : la release refuse tant qu'elles manquent) ;
  (3) **tailles indicatives** (`approxBytes` : ~600 Mo / 15 Mo / 10 Mo / 100 Ko, **provisoires**, estimées sans avoir vu les
  fichiers) : elles servent au contrôle d'espace et à la barre de progression, pas à valider le fichier ;
  (4) **licence du modèle vocal** : aucune mention n'est affichée (la licence du modèle Nemotron exporté n'a pas été vérifiée ;
  sherpa-onnx est sous Apache 2.0, ce qui ne dit rien de celle des poids) : à relire avant la soumission Play, et à ajouter à
  l'écran « Licences » / à la mention avant téléchargement si elle l'exige.
- **8.13** : chaque modèle installé a un bouton « Supprimer » (écran « Modèle IA ») avec confirmation ; le message prévient si
  c'est le dernier modèle ou le modèle actif. Suppression hors du thread principal : `LlmEngineHost.releaseModelEverywhere`
  d'abord (moteur du clavier compris), puis `ModelPreferences.clearAndDeleteCopy` (fichier, `.tmp`, empreinte, cache
  XNNPACK, référence). Un toast indique l'espace libéré. Modèle actif supprimé : un autre modèle installé devient actif
  (`ActiveModelAfterDeletion`, pur, testé), sinon plus de modèle actif (`clearActiveModel`) et la redirection 8.5 s'applique.
  Aucune suppression automatique.
- **8.14** : un modèle **téléchargé** dont l'empreinte enregistrée à l'installation diffère de `AiModel.sha256` (nouvelle
  release) passe au statut *Mise à jour disponible* (`ModelUpdate`, pur, testé ; `ModelPreferences.updateAvailable`) sur
  l'écran « Modèle IA » et en badge « Mise à jour » sur l'accueil. Le modèle installé reste utilisable et actif tant que la mise
  à jour n'est pas lancée. « Mettre à jour » relance le téléchargement sûr (8.9, mêmes contrôles réseau et espace) : le
  nouveau fichier est écrit dans `.tmp`, vérifié, puis **remplace** l'ancien par rename atomique (c'est ce remplacement qui
  « supprime l'ancienne version » ; le cache XNNPACK de l'ancien fichier est supprimé juste avant). Si la mise à jour échoue,
  l'ancien fichier est intact. Les fichiers chargés à la main (debug) ne sont pas concernés. Pas de vérification automatique :
  la veille reste manuelle (décision existante). Il faut de la place pour la nouvelle version **en plus** de l'ancienne
  pendant le téléchargement.

## Sources et empreintes
Gemma 3 (270M, 1B) : serveur de l'utilisateur (Worker Cloudflare devant R2). Gemma 4 (E2B, E4B) : Hugging Face
(`litert-community`), provisoire. Les 4 empreintes SHA-256 sont figées dans `AiModel` : le téléchargement est possible en
release. Si les empreintes Gemma 4 viennent des copies de l'utilisateur et que le fichier Hugging Face a changé, le
téléchargement sera rejeté (*Réessayer*).

## À valider sur l'appareil
- [ ] 8.15 : release (ou debug avec empreintes relevées) : sans modèle vocal, Vocal ouvre l'écran « Modèle IA » défilé sur « Modèle vocal » ; « Télécharger » (Wi-Fi) : progression globale qui avance sans retomber, notification « Téléchargement du modèle vocal » écran éteint, puis *Installé* ; 4 fichiers dans `files/voice-model/`, aucun `.tmp`. Dictée avec ce modèle (aucune recopie, un seul jeu de fichiers).
- [ ] 8.15 : couper le Wi-Fi pendant le fichier 1 puis pendant le fichier 2 : *Réessayer*, aucun `.tmp` ; « Réessayer » ne retélécharge pas les fichiers déjà installés. Données mobiles : confirmation avec la taille. Mode avion : « Aucune connexion ». « Annuler » (écran et notification) : retour à *Télécharger*.
- [ ] 8.15 : « Supprimer » (confirmation) : toast d'espace libéré, ligne à *Télécharger*, `files/voice-model/` vide, accueil « À télécharger » ; Vocal redirige de nouveau. Supprimer pendant que le clavier est ouvert : pas de plantage. En debug, charger un fichier à la main puis télécharger (et inversement) : le dernier installé gagne, la dictée utilise bien le nouveau fichier.
- [ ] 8.15 : en release, la ligne « Modèle vocal local » n'existe pas sur l'accueil ; en debug elle ouvre l'ancien écran, inchangé.
- [ ] 8.14 : installer un modèle, puis changer `sha256` (et l'URL) dans `AiModel` et réinstaller l'app par-dessus : la ligne passe à *Mise à jour disponible* (badge « Mise à jour » sur l'accueil), le modèle reste utilisable pour Corriger. « Mettre à jour » : progression, puis retour à *Installé* avec la nouvelle empreinte ; couper le Wi-Fi en cours : l'ancien fichier est intact et le bouton reste proposé. Un seul fichier dans `files/models/` à la fin.
- [ ] 8.13 : « Supprimer » demande confirmation ; après suppression, toast « … libérés » et la ligne repasse à *Télécharger* ; `files/models/` ne contient plus ni fichier ni `.tmp`. Supprimer le modèle actif alors qu'un autre est installé : l'autre devient actif. Supprimer le dernier : Corriger et Générer ouvrent l'écran « Modèle IA » (8.5). Supprimer pendant que le clavier a le modèle chargé : pas de plantage.
- [ ] 8.12 : en debug, l'accueil montre « Modèle IA » et « Modèle IA local » ; en release et benchmark, seulement « Modèle IA » (aucun séparateur orphelin). Charger un modèle à la main (debug) puis le télécharger : un seul fichier `models/<id>.litertlm`, le dernier remplace l'autre ; l'écran « Modèle IA » indique « Fichier local » pour celui chargé à la main.
- [ ] 8.11 : « Télécharger » sur Ultra-léger ou Léger affiche la mention (liens ouverts dans le navigateur) ; « Annuler » ne démarre rien ; « Accepter » démarre, et la mention ne revient pas pour l'autre Gemma 3. Aucune mention sur Équilibré et Performant. Accueil > À propos > Licences : texte Apache déplié/replié, liens ouverts.
- [ ] `./gradlew testDebugUnitTest` (nouveaux : `DownloadPolicyTest`, `ModelInstallerTest`, `DownloadStateTest`, `ModelRecommendationTest`).
- [ ] Version de WorkManager (`work = "2.10.0"` dans `gradle/libs.versions.toml`) résolue par Gradle.
- [ ] Télécharger E2B en Wi-Fi : progression, écran éteint (la notification reste), puis *Installé* ; correction IA avec ce modèle.
- [ ] Couper le Wi-Fi en cours de route : *Réessayer*, aucun fichier `.tmp` dans `files/models/`.
- [ ] Données mobiles : la confirmation s'affiche avec la taille. Mode avion : message « Aucune connexion ».
- [ ] Annuler depuis l'écran et depuis la notification : retour à *Télécharger*, pas de `.tmp`.
- [ ] Android 13+ : la permission de notification est demandée une seule fois ; refusée, le téléchargement marche.
- [ ] Android 14/15 : le service `dataSync` démarre sans exception (sinon : essayer sans `setForeground`).
- [ ] Release (R8) : APK signé, mêmes tests.
- [ ] 8.6 : supprimer le fichier du modèle actif à la main puis Corriger : phrase « introuvable ou illisible » ; essayer un fichier invalide ; noter les vrais messages du moteur (mémoire, texte trop long) dans logcat pour ajuster les mots-clés.
- [ ] 8.5 : sans modèle actif (ou fichier supprimé), Corriger et Générer ouvrent l'écran « Modèle IA » ; le texte saisi reste en place ; Vocal, sans modèle vocal complet, ouvre l'écran « Modèle vocal ».
