# Refonte UX de l'accueil — lot 1 (06/10/2026)

Fait :
- Logo horizontal en tête de l'accueil (`drawable-nodpi/taipo_logo_horizontal.webp`, WebP sans perte, 1120 × 288,
  réduit depuis l'original 3354 × 862 : 110 Ko au lieu de 1,4 Mo). Largeur plafonnée à 280 dp
  (`taipo_home_logo_max_width`), centré. Description d'accessibilité : « Taipo ».
- Titre : « Clavier IA 100% local » (`main_title`), centré, en dessous du logo.
- Texte d'explication : la mention « Clavier Android minimal » est retirée (elle contredisait les actions IA) ;
  les étapes d'activation sont inchangées.
- Exception documentée à la règle « aucune image raster dans `drawable*` » : le logo (dégradés et textures non
  vectorisables sans perte). `RefonteResourcesRegressionTest` l'autorise par son nom, rien d'autre.

## Lot 2 — onboarding en 3 étapes (06/10/2026)

- Trois cartes remplacent les deux boutons et la liste de texte : **1. Activer le clavier**, **2. Choisir Taipo**,
  **3. Essayer**. Chaque carte a une pastille : numéro (à faire), coche violette (faite), contour discret (pas encore
  accessible, carte atténuée à 60 %).
- État lu dans Android à chaque retour sur l'écran (`onResume`) et à la fermeture du sélecteur
  (`onWindowFocusChanged`) : Taipo activé = présent dans `enabledInputMethodList` ; choisi = méthode par défaut
  (`Settings.Secure.DEFAULT_INPUT_METHOD`). Logique pure dans `OnboardingState` (testée : `OnboardingStateTest`).
- Étape 2 : le bouton ouvre maintenant le **sélecteur de méthode de saisie** d'Android (`showInputMethodPicker`) ;
  il ouvrait avant l'écran « sous-types de méthode de saisie », qui ne sert pas à choisir un clavier.
- Étape 3 : un champ d'essai apparaît quand Taipo est sélectionné. L'étape n'est jamais cochée (rien ne permet de
  savoir si l'essai est fait).
- Étapes faites : la carte se réduit à son titre (« Clavier activé », « Taipo est sélectionné »).
- Texte d'explication réduit à la phrase sur le bilinguisme (les étapes sont dans les cartes). Bouton de l'étape 1 :
  « Ouvrir les réglages » (le titre dit déjà « Activer le clavier »).

À valider sur Pixel 9 : désinstaller/désactiver Taipo dans les réglages puis revenir (étape 1 repasse à faire) ;
activer sans choisir (étape 2 à faire, champ d'essai caché) ; choisir via le sélecteur (étapes cochées, champ visible) ;
changer de clavier par défaut pendant que l'accueil est ouvert puis revenir.

## Lot 3 — réglages en lignes groupées (06/10/2026)

- Les cinq boutons secondaires sont remplacés par deux cartes de lignes (titre, sous-titre, chevron) :
  **Clavier** (Paramètres du clavier, Dictionnaire personnel) et **Intelligence artificielle** (Modèle IA, Modèle vocal,
  Prompts système). Une ligne = `layout/view_settings_row.xml` (inclus cinq fois, textes posés par `MainActivity.bindRow`).
- Zone tactile d'au moins 48 dp par ligne ; retour visuel au toucher qui suit les coins arrondis de la carte
  (`clipToOutline`) ; les deux titres de groupe sont déclarés comme titres pour TalkBack.
- Les identifiants (`button_keyboard_settings`, etc.) sont conservés ; seules les vues deviennent des lignes.
- Sous-titres (`home_row_*_subtitle`) rédigés d'après le contenu des écrans : à relire.

À valider sur Pixel 9 : lisibilité des lignes et des séparateurs, retour visuel au toucher (coins arrondis),
police agrandie (sous-titres sur plusieurs lignes), TalkBack (navigation par titres, lecture titre + sous-titre).

## Lot 4 — statut des modèles sur l'accueil (06/10/2026)

- Une pastille à droite des lignes « Modèle IA » et « Modèle vocal » (`row_badge` dans `view_settings_row.xml`, masquée sur
  les autres lignes) : **Prêt** (fond violet, texte blanc), **À fournir** et **N/4 fichiers** (fond sombre à contour
  discret, texte d'avertissement). Le texte porte l'information (pas de couleur seule) ; TalkBack lit titre, sous-titre
  puis statut.
- Logique pure dans `ModelStatus` (testée : `ModelStatusTest`). Modèle IA : prêt si le **modèle actif** (celui du menu
  déroulant) a un fichier associé, sinon à fournir. Modèle vocal : prêt si les 4 fichiers sont associés, incomplet si
  1 à 3, sinon à fournir (`VoiceModelPreferences.providedCount`).
- Story 8.15 : la ligne « Modèle vocal » ouvre la section « Modèle vocal » de l'écran « Modèle IA » (téléchargement) et affiche *À télécharger* / *N/4 fichiers* / *Prêt* / *Mise à jour* ; en debug seulement, une ligne « Modèle vocal local » (sous la précédente) ouvre l'ancien écran de choix des 4 fichiers à la main.
- Relu à chaque `onResume` : le statut se met à jour au retour des sous-pages.
- Épopée 8 : la ligne « Modèle IA » (écran de téléchargement) affiche *Prêt* / *À télécharger* selon le modèle actif (téléchargé ou fourni à la main) et son nom en sous-titre ; l'ancien écran s'appelle « Modèle IA local » et n'a plus de pastille. Le détail d'un téléchargement (pourcentage, échec) est sur l'écran « Modèle IA », pas à l'accueil. Le calcul
  d'empreinte SHA-256 se fait dans `ModelSettingsActivity` et n'est pas exposé à l'accueil. Le statut dit « fichier
  associé », pas « fichier vérifié » (la taille et l'empreinte restent signalées dans la sous-page).

À valider sur Pixel 9 : première ouverture (les deux pastilles « À fournir »), choix d'un fichier de modèle IA puis retour
(« Prêt »), changement du modèle actif vers un modèle sans fichier (« À fournir »), modèle vocal avec 1 à 3 fichiers
(« N/4 fichiers ») puis 4 fichiers (« Prêt »), police agrandie (pastille sur une ligne, sous-titre qui passe à la ligne),
TalkBack.

Reste à faire (suite du plan UX) : en-tête commun des sous-pages.
