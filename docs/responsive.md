# Responsive et tailles d'écran (refonte graphique, lot 19)

Vérification faite par calcul (formules du code + métriques réelles d'Open Sans), pas sur appareil. À confirmer sur le Pixel 9
et, si possible, sur un petit écran (320 dp) et en paysage.

## Algorithmes non modifiés
`KeyboardMetrics`, `KeyboardWidth`, `KeyboardHeight`, `KeyboardLayout` : aucune modification (aucune nécessité démontrée).

## Ce qui tient déjà (gardes existantes)
- Touches : l'ombre est plafonnée à 15 % de la hauteur, le texte et les icônes à 62 % de la face. Face minimale calculée : 20,8 dp
  (paysage, réglage « compact ») ; le rond d'Entrée suit la plus petite dimension de la face.
- Indice d'appui long (10 sp) et libellé : pas de chevauchement vertical en portrait à police 1,0 (3,8 dp d'écart). À police 1,3, le
  recouvrement est d'environ 0,7 dp : traité au lot 20 (indice passé en dp, voir `accessibilite.md`).
- Largeur ≥ 600 dp : zone des touches plafonnée à 720 dp et centrée ; la barre du haut suit la même zone.

## Corrigé au lot 19
| Constat (calcul) | Correction |
| --- | --- |
| Écran d'accueil non défilant : contenu ≈ 536 dp pour 336 dp utiles en paysage ; débordement aussi sur 320 × 568 | `activity_main.xml` dans un `ScrollView` |
| Dialogues du presse-papiers non défilants : ≈ 255 dp de contenu, ≈ 116 dp à 229 dp disponibles clavier ouvert (paysage, petit téléphone) | racine dans un `ScrollView` (`ClipboardEditActivity`, `ClipboardLabelActivity`) |
| Barre du haut, état « Chargement du modèle… » pendant la saisie : zone de mots à −64 dp (320 dp) et −24 dp (360 dp), boutons de droite coupés | `taipo_bar_text_button_max_width` (136 dp), texte sur une ligne tronqué par « … » (`BarStyle.styleButton`) |

## Non modifié, à surveiller
- `activity_personal_dictionary` : en paysage avec le clavier à l'écran, la liste de mots devient très basse (elle défile).
- Police agrandie : voir `accessibilite.md` (lot 20).

## Grille de test sur appareil
Petit téléphone (≈ 320 dp) · standard (≈ 360–412 dp) · grand · forte densité · paysage · tablette ou pliable déplié (≥ 600 dp).
Pour chacun : les 5 réglages de hauteur, la barre du haut (repos avec texte, saisie, chargement du modèle, écoute), le panneau
emoji, le presse-papiers, les deux dialogues avec le clavier ouvert, l'écran d'accueil.
