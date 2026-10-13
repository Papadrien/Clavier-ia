# Sécurité — décisions et état (lot 3.4 de la revue, 04/10/2026)

Non compilé ni exécuté dans l'environnement de revue : les vérifications manuelles sont en bas.

## S1 — Chiffrement du modèle « mot suivant »

Constat de la revue : `aesKey()` = SHA-256 du secret. **Nuance vérifiée** : le secret est un tirage aléatoire de
256 bits protégé par le Keystore ; le SHA-256 d'un tel secret n'était pas exploitable. Le changement est de
l'hygiène (dérivation standard, séparation par usage, format versionné), pas la correction d'une faille.

- `NextWordCrypto` (logique pure, testée en JVM) : clé AES-256 = **HKDF-SHA256** (RFC 5869, vecteurs 1 et 3 testés)
  avec le libellé `fr.junade.taipo/next-word-model/aes-256-gcm/v2` ; fichier = `TNW\u0002` + IV + chiffré, l'en-tête
  est authentifié (donnée associée GCM).
- **Migration sans perte** : même secret Keystore, seul le format change. Un fichier de l'ancien format est relu
  (clé SHA-256), puis réécrit au format actuel dès le chargement (écriture atomique : l'ancien fichier reste
  intact si l'écriture échoue). Si un fichier est illisible dans les deux formats, comportement inchangé :
  suppression et repartir de zéro.
- Pas de rotation du secret Keystore (non demandée : elle forcerait une réinitialisation du modèle appris).

## S2 — Sauvegarde et direct boot

- `android:allowBackup="true"` déclaré **explicitement** (comportement inchangé, il était implicite).
- **Écart avec le plan** : la revue disait les exclusions complètes ; elles ne l'étaient pas pour les modèles.
  Les dossiers `filesDir/models/` (jusqu'à ~3,9 Go) et `filesDir/voice-model/` n'étaient pas exclus : la
  sauvegarde automatique est plafonnée à 25 Mo (au-delà, elle est désactivée pour toute l'app) et le transfert
  d'appareil les aurait copiés. Ils sont maintenant exclus dans les trois sections.
- Conséquence : après restauration sur un autre appareil, les préférences (`ai_model_prefs`) peuvent encore
  nommer un fichier de modèle dont la copie et l'autorisation d'accès n'existent plus. L'app affiche alors son
  message « permission perdue ? à re-sélectionner » ; il suffit de re-sélectionner les fichiers. Ce fichier de
  préférences contient aussi l'invite de correction personnalisée : il n'a pas été exclu pour ne pas la perdre.
- **D5 (direct boot) : décision documentée, non activée.** Le clavier n'est pas `directBootAware`. Avant le
  premier déverrouillage, Android affiche son clavier système. Raison : toutes les données de Taipo sont dans le
  stockage protégé par les identifiants. Garde-fou : `BackupRulesTest` échoue si un composant devient
  `directBootAware`.
- `BackupRulesTest` vérifie les trois sections de règles à partir des constantes du code.

## S3 — Empreinte des modèles locaux

- À la sélection d'un fichier de modèle IA, son **SHA-256 est calculé en arrière-plan** (flux, blocs de 1 Mo,
  progression affichée, annulé si un autre fichier est choisi pour le même modèle), enregistré avec le fichier
  et affiché (texte sélectionnable) pour comparaison avec la page du dépôt Hugging Face.
- `AiModel.sha256` (référence attendue) est **null pour tous les modèles** : je n'ai pas pu relever les
  empreintes officielles (pas d'accès réseau) et ne les devine pas. Dès qu'une valeur est renseignée, un écart
  affiche un avertissement (`checksumWarning`). À ne renseigner que si une seule variante du modèle est supportée.
- Le modèle vocal (4 fichiers sherpa-onnx) n'est pas concerné par ce lot.
- Non fait, volontairement : vérifier l'empreinte pendant la copie interne du modèle (`ModelFileResolver`). C'est
  le chemin qui charge l'IA ; à faire avec un compilateur sous la main.

## S4 — Téléchargement des modèles (épopée 8)

- **Permission `INTERNET` ajoutée** (elle était absente jusque-là). Elle ne sert qu'à `ModelDownloadWorker`, qui fait un
  GET HTTPS d'un fichier de modèle : aucun texte saisi, copié ou dicté n'est envoyé, aucun autre code réseau. Le
  message de confidentialité (« 100 % local ») reste vrai pour l'usage, mais « aucune permission Internet » ne l'est
  plus : à reporter dans la fiche Play (déclaration de sécurité des données) et dans la politique de confidentialité.
- HTTPS obligatoire, y compris après redirection (contrôlé sur l'URL finale). Pas de reprise (Range) : V2.
- Fichier écrit dans `<id>.litertlm.tmp`, SHA-256 calculé pendant le flux, comparé à `AiModel.sha256`, puis rename
  atomique. Sans empreinte de référence, le téléchargement n'est permis qu'en debug (relever l'empreinte).
- La taille du fichier installé est recontrôlée à chaque chargement du moteur (`ModelFileResolver.resolve`).
- Permissions liées : `ACCESS_NETWORK_STATE` (Wi-Fi ou données mobiles), `FOREGROUND_SERVICE` +
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (Android 13+, refusable sans bloquer le téléchargement).

## À valider sur l'appareil
- [ ] `./gradlew testDebugUnitTest` (nouveaux : `NextWordCryptoTest`, `Sha256Test`, `BackupRulesTest`).
- [ ] Migration réelle : installer la version précédente, taper des phrases (apprentissage), mettre à jour par-dessus :
      les suggestions de mot suivant sont conservées. Puis forcer l'arrêt et rouvrir : elles le sont toujours.
- [ ] Écran « Modèle IA » : choisir un fichier ; la progression s'affiche, puis l'empreinte. Comparer avec celle
      du dépôt. Choisir un autre fichier pendant le calcul : pas de plantage, l'empreinte affichée est la dernière.
- [ ] `adb shell bmgr backupnow fr.junade.taipo` (ou `adb shell dumpsys backup`) : pas d'erreur de taille.

## Transcription IA (épopée 14, story 14.6)

- **Audio traité sur l'appareil** : l'enregistrement est envoyé au modèle Gemma 4 embarqué (LiteRT-LM), jamais à un serveur.
  La permission Internet ne sert qu'au téléchargement des modèles.
- **Aucun fichier audio** : l'enregistrement (120 s au plus) reste dans la mémoire du processus et est jeté à la fin, à
  l'annulation, en cas d'erreur ou si le champ change.
- **Journaux et statistiques** : ni l'audio ni le texte transcrit ne sont écrits dans `AppLog` (seulement durées, tailles et
  trace d'erreur) ; l'application n'envoie aucune statistique (pas d'Aptabase ni d'autre outil de mesure dans le projet).
- **Champs de mot de passe** (texte masqué, visible, web, et code PIN numérique) : le bouton est grisé (voir « Champs sensibles »).
  Si le champ change pendant un traitement, le texte n'est pas inséré.
- **Erreurs** : une phrase par cause (fichier du modèle, mémoire, modèle incompatible, enregistrement trop long, inattendu), jamais
  de nom de classe ni de message brut (`InferenceFailureClassifier`, story 8.6).
- Repris dans le menu « Confidentialité » (story 10.2, `PrivacyContent`).

## Champs sensibles (épopée 10, story 10.1)

- **Règle** : `SensitiveFieldPolicy.isSensitive` : champ de mot de passe texte (masqué, visible, web) ou champ numérique de type mot de passe
  (code PIN). Les autres champs (e-mail, URL, téléphone, terminal, texte libre) ne changent pas.
- **Dans un champ sensible** : Corriger, Générer, Vocal, Transcription IA et le bouton Smart Clipboard sont grisés (opacité réduite) mais
  restent touchables : un appui affiche « Désactivé dans les champs de mot de passe… » et n'exécute rien. Le texte du champ n'est pas lu.
  Le micro n'est pas ouvert.
- **Puce de collage** : déjà bloquée dans ces champs (`SuggestionPolicy.allowsPasteSuggestion`).
- **Navigation privée** (`IME_FLAG_NO_PERSONALIZED_LEARNING`) : non traitée comme sensible, elle coupe seulement l'apprentissage.

## Menu « Confidentialité » (épopée 10, story 10.2)

- Écran texte seul (accueil > À propos, en dernier), 5 sections : traitement local, connexions Internet, aucune collecte, champs de
  mot de passe, données conservées sur l'appareil. **Aucune bascule** : il n'y a pas de télémétrie à désactiver.
- Texte écrit d'après le code au 12/10/2026 : seules connexions = téléchargement des modèles, depuis `taipo-worker.junade-models.workers.dev`
  (Gemma 3 et modèles vocaux) et `huggingface.co` (Gemma 4). Aucun SDK de mesure d'audience, de plantage ou de publicité.
- `PrivacyContentTest` échoue si un modèle est téléchargé depuis un domaine que le texte ne cite pas. **Si Aptabase (ou tout autre outil)
  est ajouté, mettre à jour les sections 2 et 3, la fiche Play et la politique du site** (le test refuse aujourd'hui le mot « Aptabase »).
- À garder cohérent avec la fiche Play (section « Sécurité des données ») et la politique de confidentialité du site : non vérifié ici.

## Sauvegarde et restauration (épopée 10, story 10.3)

- **Contenu du fichier** (JSON, `formatVersion` 1, `BackupCodec`) : dictionnaire personnel, éléments épinglés du Smart Clipboard avec
  leurs étiquettes, réglages du clavier (rangée de chiffres, retour haptique, hauteur, emojis récents, apprentissage des habitudes), modèle
  IA choisi (identifiant) et prompts système personnalisés. **Exclus** : modèles IA, historique du presse-papiers (12 h, peut contenir des
  copies sensibles), données apprises (mot suivant, habitudes de correction : dérivées de la saisie), clés de chiffrement, acceptation des licences
  (le consentement se redonne sur le nouvel appareil), état des téléchargements.
- **Non chiffré** (décision du 12/10/2026) : l'écran avertit avant le choix de l'emplacement. Le fichier est écrit via le sélecteur de fichiers du
  système (aucune permission de stockage) ; l'application ne l'envoie nulle part.
- **Restauration par fusion** : mots et éléments épinglés s'ajoutent (doublons ignorés, plafonds respectés : 50 épingles, limite du dictionnaire),
  rien n'est supprimé ; les réglages du fichier remplacent les réglages actuels. Le modèle IA du fichier n'est repris que s'il est déjà installé
  sur l'appareil. Un fichier d'un format plus récent, d'un autre programme ou de plus de 5 Mo est refusé.
- Ni le contenu du fichier ni les données ne sont écrits dans les journaux.
