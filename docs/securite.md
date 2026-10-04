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

## À valider sur l'appareil
- [ ] `./gradlew testDebugUnitTest` (nouveaux : `NextWordCryptoTest`, `Sha256Test`, `BackupRulesTest`).
- [ ] Migration réelle : installer la version précédente, taper des phrases (apprentissage), mettre à jour par-dessus :
      les suggestions de mot suivant sont conservées. Puis forcer l'arrêt et rouvrir : elles le sont toujours.
- [ ] Écran « Modèle IA » : choisir un fichier ; la progression s'affiche, puis l'empreinte. Comparer avec celle
      du dépôt. Choisir un autre fichier pendant le calcul : pas de plantage, l'empreinte affichée est la dernière.
- [ ] `adb shell bmgr backupnow fr.junade.taipo` (ou `adb shell dumpsys backup`) : pas d'erreur de taille.
