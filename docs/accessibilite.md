# Accessibilité (refonte graphique, lot 20)

Audit fait par calcul (WCAG 2.1, formules du code) et par relecture du code ; **rien n'a été essayé sur appareil ni avec TalkBack**
(pas d'appareil ni de compilateur dans l'environnement de développement). À confirmer sur le Pixel 9, TalkBack allumé, puis avec
la police à 130 % et 200 %. Règle du plan respectée : la palette n'est **pas** modifiée, les écarts sont documentés ici.

## 1. Contrastes (garde : `AccessibilityContrastTest`)
Texte : AA = 4,5:1. Éléments graphiques significatifs : 3:1.

| Texte | Contraste |
| --- | --- |
| Blanc sur fond / touche normale / touche secondaire / touche active | 19,3 / 11,0 / 14,2 / 15,7 |
| Blanc sur violet (Entrée, envoi, étiquette, correction) | 5,9 |
| Blanc sur rouge de danger (Vocal en écoute) | 5,4 |
| Blanc à 60 % (`taipo_text_muted`) sur touche normale (indice) / carte / fond | 5,1 / 6,1 / 7,3 |
| Orange d'avertissement, rouge clair de danger sur carte | 8,1 / 6,2 |

Tous au-dessus de 4,5:1 : **aucune couleur modifiée.** `taipo_text_muted` (« à valider au lot 20 ») est validée.

| Élément graphique | Contraste | Verdict |
| --- | --- | --- |
| Barre de l'espace sur sa touche | 5,9 | conforme (la couleur « provisoire » n'a rien d'urgent) |
| Violet sur fond (onglet emoji, commutateur coché, focus) | 3,3 | conforme |
| Pastille / piste du commutateur | 11,0 | conforme |

### Écarts documentés, non corrigés (voulus par la plaquette)
- Touche normale / secondaire **sur le fond** : 1,75 / 1,36 (sous 3:1). Les touches sont reconnaissables par leur position, leur
  libellé ou leur icône, leur ombre et leur forme, pas par la seule couleur ; WCAG 1.4.11 ne l'exige pas pour un composant dont
  le texte est lisible. À corriger seulement si les essais sur appareil le montrent (éclaircir `taipo_key_secondary`).
- Touche active (Maj, symboles) sur touche normale : 1,42. **L'état n'est donc pas porté par la couleur seule** : Maj change
  d'icône (flèche pleine / verrouillée), et TalkBack annonce « Activé / Désactivé ».
- Sélection de la bulle d'appui long (violet sur `key_popup`) : 1,86. Le choix sélectionné est aussi un disque plein, et le
  texte reste lisible (5,9). Idem pour le trait des champs de saisie au repos (1,6) : le focus est violet et plus épais (2 dp).

## 2. Zones tactiles (48 dp)
| Élément | Avant | Après |
| --- | --- | --- |
| Boutons des barres du haut / prompt / suggestions (36 dp de haut, 40 dp de large) | 36 dp | **48 dp de haut** (débord de 6 dp, `ExpandedTouchDelegate`), largeur étendue jusqu'à la moitié de l'espace entre deux boutons |
| Bouton « Ajouter le texte » du chat | 36 dp | 48 dp (fond inséré, marges négatives : le rendu ne bouge pas) |
| Touches du clavier | ≥ 51,6 dp (portrait) ; **36 dp en paysage** | inchangé : algorithmes de `KeyboardMetrics` non touchés (règle du lot 19) |
| Touches du panneau emoji (ABC, effacer) | 0,85 × rangée | inchangé |
| Cellules d'emoji | ≥ 42 dp | inchangé |
| Onglets d'emoji | 0,7 × rangée | inchangé (à surveiller en paysage, ≈ 25 dp) |
| Lignes de réglages, boutons des écrans, liste déroulante | 48 dp | inchangé |

Le rendu des barres ne change pas ; seul le toucher qui tombe dans la marge de 6 dp ou entre deux boutons est transmis au plus proche.

## 3. TalkBack
Le clavier et la grille d'emojis sont des vues dessinées au Canvas : TalkBack n'y voyait **rien**. Corrigé par un arbre
d'accessibilité virtuel (`ExploreByTouchHelper`, nouvelle dépendance `androidx.customview`) :

- **Touches** (`KeyboardAccessibility`) : chaque touche est un bouton, lu dans l'ordre (rangée par rangée). Noms parlés pour
  les icônes (Majuscule, Effacer, Entrée, Espace, Emoji) et la ponctuation (« Virgule », « Point », « Apostrophe »…, `KeyChars`) ;
  la bascule dit « Chiffres et symboles » ou « Lettres ». Maj annonce son état. Double appui = saisie.
- **Appui long** : accents, symboles du point et chiffres d'indice sont proposés comme **actions** du menu d'actions de TalkBack
  (« Saisir é »…), car le glissement de la bulle est impossible en exploration. Le geste tactile ne change pas.
- **Panneau emoji** : onglets (nom de la catégorie, état sélectionné), emojis visibles (boutons), message « aucun récent »,
  défilement par les actions de TalkBack, touches « Retour au clavier » et « Effacer ».
- **Barres** : rôle « bouton » pour les emplacements de suggestion, la puce de collage, les emojis récents, la croix du prompt (désormais à droite du bouton d'envoi) ;
  le mot de l'autocorrection annonce « Remplacera le mot à l'espace » ; les guillemets du mot tapé ne sont plus lus ; le prompt
  est lu tel que tapé (sans le trait de curseur) ; le bouton d'envoi annonce « Chargement du modèle… » (la roue est masquée).
- **Vocal** (barre normale et micro de la barre du prompt, même branchement) : le double appui de TalkBack bascule l'écoute (nouveau `VoiceController.onAccessibilityClick`) ; avant, l'écouteur
  tactile consommait tout et le bouton était inactivable. L'écoute tant que maintenu reste un geste tactile.
- **Cartes du presse-papiers** : rôle « bouton », actions nommées « Coller » (clic) et « Ouvrir le menu de l'élément » (appui long).

## 4. Taille de police
- Textes de **hauteur fixe** (barres, suggestions, panneau emoji, bouton à libellé) : échelle plafonnée à 130 %
  (`taipo_max_font_scale`, `fixedHeightTextDimen`) : au-delà, le texte ne tient plus dans ses 36 dp. Ils suivaient déjà le sp ;
  seul le plafond est nouveau.
- Indice d'appui long des touches : **passé de 10 sp à 10 dp**. En sp, il recouvrait le libellé dès 130 % (écart relevé au
  lot 19 : 0,7 dp à 1,3). Compromis assumé : l'indice est redondant (chiffre lu par TalkBack, accessible par appui long).
- Écrans de réglages, cartes, chat, dialogues : suivent la police du système sans plafond (ils défilent).
- Touches du clavier : le texte est borné à 62 % de la face, donc toujours contenu.

## 5. Navigation au clavier physique
Non traitée : l'IME n'est pas une cible de focus clavier. Les écrans de réglages utilisent des contrôles standard (focus natif).

## À vérifier sur appareil
TalkBack : explorer le clavier (lettres, symboles, Maj, point + actions), saisir une lettre au double appui, ouvrir le panneau
emoji et en choisir un, dicter par le bouton Vocal, coller depuis le presse-papiers. Police à 130 % et 200 % : barre du haut,
suggestions, panneau emoji. Zones tactiles : viser le bord haut / bas des boutons de la barre.
