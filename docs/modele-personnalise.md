# Modèle IA personnalisé (pack premium)

Non compilé ni exécuté dans l'environnement de développement : à valider au premier build.

## Principe
L'utilisateur fournit son propre fichier `.litertlm` depuis le système de fichiers (sélecteur Android, permission SAF persistante) et
s'en sert à la place des modèles de Taipo pour Corriger, Chat et la transcription IA. Le fichier doit pouvoir être chargé par la version
de LiteRT-LM intégrée (`CustomModelAccess.LITERT_LM_VERSION`, aujourd'hui 0.17.1, vérifiée contre `gradle/libs.versions.toml` par
`CustomModelAccessTest` : à changer avec la dépendance).

Accès (`CustomModelAccess.allowed`) : pack premium actif, ou build debug (pour essayer un modèle avant de l'ajouter au catalogue d'une
version publiée, sans dépendre du forçage du pass). Sans le pack, la ligne de l'accueil porte un cadenas et ouvre l'écran du pack ; un
modèle déjà choisi reste enregistré mais n'est plus « installé » (`ModelPreferences.isInstalled`), donc les actions IA redirigent vers
l'écran « Modèle IA » comme sans modèle (story 8.5).

## Technique
- `AiModel.CUSTOM` (id `personnalise`) : hors catalogue (`entriesOrdered`, `entriesForDisplay`), sans téléchargement, empreinte, taille
  de référence ni licence (`license` = null). Même stockage que « Modèle IA local » : copie interne `models/personnalise.litertlm` au
  premier chargement (`ModelFileResolver`).
- Page `CustomModelActivity` (accueil > IA > « Modèle personnalisé ») : avertissement (compatibilité, responsabilité), choix du
  fichier (avertissement si l'extension n'est pas `.litertlm`), « Tester le modèle » (ferme le moteur du clavier, copie et charge le
  fichier avec LiteRT-LM, referme), « Utiliser ce modèle » (`activateCustomModel` retient le modèle d'avant, `deactivateCustomModel` le
  rétablit), « Ce modèle accepte l'audio » (`ModelPreferences.acceptsAudio`, remplace `AiModel.hasAudioInput` pour le bouton de
  transcription IA), « Oublier ce modèle ».
- Prompts : `SystemPromptPreferences.forCustomModel()` (clés `custom_model_<clé>`), un par fonction. Quand le modèle personnalisé est
  actif, `get()` renvoie son prompt s'il existe, sinon le prompt de la page « Prompts système » (avec le pack), sinon le défaut. Exportés
  par la sauvegarde sous `custom_model_correction`, `custom_model_generation`, `custom_model_transcription`.

## Limites / à valider
- Le test ne fait que charger le modèle : il ne vérifie pas que ses réponses suivent les prompts (format attendu par la correction).
- Pas de réglages propres au modèle (taille de contexte, température) : ceux du moteur s'appliquent.
- [ ] Choisir un fichier, Tester (succès et échec avec un fichier qui n'est pas un modèle), Utiliser, Corriger/Chat dans le clavier.
- [ ] Sans pack (release) : cadenas, écran du pack, modèle actif ignoré.
