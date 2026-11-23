# Pass premium (08/11/2026)

Achat unique, à vie, **2,99 €** (produit `pass_premium` de type « achat unique » à créer dans la Play Console).

## Contenu

| | Sans pass | Avec le pass |
|---|---|---|
| Couleurs du clavier (page Thème) | Violet Taipo | Les 9 couleurs (`AccentPalette`) |
| Prompts système (correction, chat, transcription, ton, traduction) | Prompts par défaut | Prompts personnalisés |
| Historique du presse-papiers | 12 h | 7 jours |
| Éléments épinglés | 5 | 50 |
| Fond du clavier (page Apparence) | Uni | + 7 fonds animés : Rubans, Bulles, Cristal, Ondes, Cœurs, Fleurs, Processeur, et le fond « Image » (image choisie par l'utilisateur, `PhotoBackdrop`) (`KeyboardBackdrop`) |
| Forme des touches | 4 formes simples | + 5 formes (`KeyShape`) |
| Police du clavier | Open Sans | + 9 polices, dont OpenDyslexic (`KeyboardFont`) |
| Style d'animation | Ressort | + Vif, Gelée, Envol, Goutte, Glisse, Étincelles, Pixels, Bulle cœur, Éclosion (`MotionStyle`) |

Restent gratuits : frappe, autocorrection, langues et multilingue, mode une main, accessibilité, dictionnaire personnel,
sauvegarde, les 4 modèles IA.

Règles de passage gratuit ↔ premium :
- **Épinglés** : au-delà de 5 sans pass, rien n'est supprimé ; seul un nouvel épinglage est refusé (message avec « Voir le pass »).
  Une restauration de sauvegarde garde le plafond de 50 (ce sont les données de l'utilisateur).
- **Historique** : sans pass, les copies de plus de 12 h sont purgées (aucune copie n'est gardée en cachette au-delà).
- **Prompts** : un prompt personnalisé reste enregistré sans pass (et exporté par la sauvegarde), mais c'est le prompt par
  défaut qui sert ; il revient dès que le pass est actif (`SystemPromptPreferences.get`).
- **Couleur** : une couleur réservée choisie sans pass retombe sur le violet (`AccentPalette.effective`).

## Mécanisme

- `PremiumPass` : état gardé dans les préférences (`premium_prefs`), lu par le clavier sans jamais parler à Google Play.
  Posé au démarrage du processus (`TaipoApplication`), mis à jour par `premium/PremiumBilling`.
- `PremiumBilling` (Google Play Billing 8.0.0, écrans de l'application seulement) : relit les achats à chaque retour sur
  l'accueil et à l'ouverture de l'écran du pass (un remboursement retire le pass), lit le prix (promotion comprise), ouvre la
  fenêtre de paiement, confirme chaque achat (sans confirmation, Google Play rembourse au bout de 3 jours), gère les paiements
  en attente.
- **Vérification des achats** (21/11/2026, `premium/PurchaseVerifier.kt`) : un achat n'active le pack que si sa signature
  (`Purchase.signature`, SHA1withRSA sur `Purchase.originalJson`) est valide pour la clé publique de licence de l'application et
  qu'il porte le nom de paquet de Taipo. Un achat inventé par une fausse bibliothèque Google Play (outils de contournement sur
  appareil rooté) est refusé, et un pack activé par un tel achat est retiré à la relecture suivante. `ITEM_ALREADY_OWNED` relit
  et vérifie les achats au lieu d'activer le pack sur parole.
  - Clé : Play Console > Monétiser > Configuration de la monétisation > Licences, « Clé publique encodée en base64 », dans
    `BuildConfig.PLAY_LICENSE_KEY` (sources : `playLicenseKey=` dans `key.properties`, propriété Gradle `taipo.playLicenseKey`,
    variable `TAIPO_PLAY_LICENSE_KEY` ; voir l'en-tête de `app/build.gradle.kts`). Elle n'est pas secrète.
  - Sans clé : un build debug accepte les achats sans les vérifier (comptes testeurs) ; un build release est refusé
    (`checkPlayLicenseKey`). Une clé présente mais illisible ne retire jamais le pack (état connu gardé, message d'erreur à
    l'écran du pass) : une mauvaise copie de la clé ne pénalise pas les vrais acheteurs.
  - Pas de serveur : une vérification côté serveur (API Google Play Developer) ou Play Integrity resterait plus sûre.

## Mise en avant

- Écran **Pack premium** (`PremiumActivity`, revu le 22/11/2026) : bouton **Acheter** tout en haut sous le titre (message d'état juste
  dessous), prix, puis une carte par avantage, chacune avec son icône : couleurs, consignes pour l'IA (correction, chat, dictée, ton,
  traduction), modèle personnalisé, presse-papiers étendu, apparence (fonds animés, photo, formes, polices), animations (9 styles) ;
  rappel de ce qui reste gratuit, Restaurer.
- **Accueil** : carte d'offre une fois les 3 étapes faites, masquable (elle ne revient pas) ; ligne « Pass premium » dans
  sa propre carte encadrée de violet, en tête de « À propos ».
- **Thème** : cadenas sur les 8 couleurs ; en toucher une l'essaie sur les aperçus, avec « Débloquer avec le pass » ; au retour
  avec le pass, elle est appliquée.
- **Prompts système** : sans pass, la ligne de l'accueil porte un cadenas à la place du chevron et ouvre l'écran du pass
  (lecture et modification des prompts réservées) ; avec le pass, elle ouvre la page des prompts.
- **Clavier** : seulement au 6ᵉ épinglage, message avec « Voir le pass ».
- Mesure d'usage : `premium_paywall_shown` et `premium_purchased`, avec l'origine (`docs/analytics.md`).

## Debug

Builds debug : section « Debug » tout en bas de l'accueil, un bouton qui passe de « état Google Play » à « forcé actif » puis
« forcé inactif » (`PremiumPass.DebugOverride`). Absent et ignoré dans un build de publication.

## À faire hors du code

- Play Console : créer le produit `pass_premium` (achat unique, 2,99 €), ajouter des testeurs de licence.
- Clé publique de licence : renseignée le 22/11/2026 dans `gradle.properties` (`taipo.playLicenseKey`). À changer seulement si
  la Play Console en donne une autre ; sans elle, la release ne se construit pas.
- Fiche « Sécurité des données » : historique d'achats (géré par Google Play).
- Page Confidentialité : mentionner que l'achat passe par Google Play, sans donnée envoyée par Taipo.
