# Mesure d'usage anonyme (Aptabase)

Taipo envoie quelques événements d'usage à [Aptabase](https://aptabase.com) (serveur de l'UE, clé `A-EU-…` dans
`BuildConfig.APTABASE_APP_KEY`) avec le **SDK Kotlin d'Aptabase** (`com.github.aptabase:aptabase-kotlin:0.1.0`, via JitPack, dépôt
déclaré dans `settings.gradle.kts` et limité à son groupe). Le SDK est initialisé dans `TaipoApplication`, et `Analytics`
(`app/.../analytics/Analytics.kt`) est la seule porte d'entrée vers lui. Le catalogue et le filtrage des propriétés sont dans
`:core` (`fr.junade.taipo.analytics`) et testés en JVM. Le SDK s'occupe de l'envoi, de la session et du mode debug/release.

## Garanties

- **Aucun texte** : une propriété ne peut être qu'un nombre, un booléen ou un mot-clé court (`[a-z0-9_.-]`, 24 caractères au
  plus). `AnalyticsProps.of` écarte le reste (phrase, mot saisi, nom de fichier, majuscules, espaces) : même une erreur de
  code ne peut pas faire partir un texte.
- **Aucun identifiant** d'appareil ou de personne. La session Aptabase (gérée par le SDK) est anonyme et renouvelée après 1 h sans
  événement.
- **Champs de mot de passe** : `Analytics.sensitiveFieldActive` (tenu par `TaipoIme`) coupe tout événement tant qu'un champ
  sensible est affiché.
- **Désactivable** : Paramètres du clavier > Données d'utilisation (« Partager les données d'utilisation »). Activée par défaut. Désactivée, `Analytics.track` ne rappelle plus le SDK.
- **Jamais bloquant** : d'après la doc du SDK, les envois se font sur un pool de fils d'arrière-plan, jamais sur le fil principal.
- **Types** : le SDK n'accepte que des `String` et des `Int` ; `AnalyticsProps.forSdk()` convertit les booléens en `"true"` / `"false"`
  (mots-clés) et les `Long` en `Int`.
- **Debug** : `Analytics.initialize` passe le mode explicitement (`TrackingMode.asDebug` si `BuildConfig.DEBUG`, sinon `asRelease`) :
  les builds debug vont au tableau de bord de test d'Aptabase, séparé de la production.

Propriétés système envoyées avec chaque événement (par le SDK) : version et build de l'app, version d'Android, langue et **modèle
de l'appareil** (la doc du SDK le cite ; l'ancien client maison ne l'envoyait pas).

## Catalogue

| Événement | Propriétés | Où |
|---|---|---|
| `model_download_started` / `_done` | `model` (`ultra_leger`, `leger`, `equilibre`, `performant`, `voice`) | `ModelDownloadWorker`, `VoiceModelDownloadWorker` |
| `model_download_failed` | `model`, `reason` (`network`, `server`, `no_space`, `corrupted`, `other`) | idem |
| `ai_action` | `type` (`correct`, `generate`, `transcribe`), `model`, `duration` (tranche), `success` (`true` / `false`) | `CorrectionAiController`, `PromptModeController`, `AiTranscriptionController` |
| `ai_action_failed` | `type`, `model`, `cause` (`model_file`, `out_of_memory`, `model_incompatible`, `text_too_long`, `unexpected`) | idem (en plus de `ai_action` avec `success = false`) |
| `voice_input` | `mode` (`tap`, `hold`), `duration` (tranche d'écoute) | `VoiceController.stopRecording` |
| `autocorrect_undone` | aucune | `TaipoIme` (champ et prompt) |
| `clipboard_used` | `action` (`paste`, `pin`) | `ClipboardController` |
| `undo_used` | aucune | `UndoController` |
| `keyboard_language` | `lang`, `layout` (`azerty`, `qwerty`, `qwertz`), `auto` | `TaipoIme.startInputView`, une fois par changement de langue |
| `setting_changed` | `key`, `value` (`true` / `false` ou mot-clé) | `KeyboardSettingsActivity` |
| `first_open` | aucune | `MainActivity.onCreate` via `Analytics.firstOpen`, une fois par installation |

Tranches de durée : `ai_action` `lt_1s`, `1_3s`, `3_10s`, `10_30s`, `gt_30s` ; `voice_input` `lt_5s`, `5_15s`, `15_60s`, `gt_60s`.

`ai_action` n'est pas envoyé pour une action annulée par l'utilisateur ni pour une génération interrompue (partielle).
Le mode chat est la génération par prompt : il compte sous `type = generate`.

## Compter les nouveaux utilisateurs (`first_open`)

Aptabase n'a pas d'identifiant d'appareil : le nombre d'événements `first_open` du tableau de bord EST le nombre de nouvelles
installations ouvertes (filtrer par période, version de l'app, langue ou pays du tableau de bord pour le détail).

- Envoyé à la première ouverture de l'application (écran d'accueil), une seule fois : le réglage `first_open_handled`
  (`AnalyticsPreferences`) est mémorisé après l'envoi. Règles pures dans `FirstOpenPolicy` (`:core`, testées).
- **Mise à jour** : si l'écart entre la date d'installation et la dernière mise à jour dépasse 60 s, l'application est
  considérée comme déjà installée avant l'événement : rien n'est envoyé (sinon tous les utilisateurs existants seraient comptés
  comme nouveaux), et le réglage est mémorisé.
- **Mesure désactivée ou SDK absent** : rien n'est envoyé ni mémorisé ; la prochaine ouverture réessaie.
- **Limites** : une désinstallation puis réinstallation sans restauration de sauvegarde recompte l'utilisateur (pas
  d'identifiant pour le reconnaître) ; le réglage suit la sauvegarde automatique d'Android, donc un changement de téléphone
  avec restauration ne recompte pas. Une personne qui active le clavier sans jamais ouvrir l'application n'est pas comptée.
- **Tester** : désinstaller puis réinstaller (les dates d'installation et de mise à jour doivent être égales) ; « Effacer les
  données » ou une mise à jour par-dessus ne rejoue pas l'événement, car l'application est alors vue comme mise à jour.

## Pas encore là

- `premium_paywall_shown` / `premium_purchased` : à ajouter avec l'achat intégré.
- `clipboard_used` avec `ai_attach` : le bouton « Action IA » du Smart Clipboard n'existe pas encore dans le code.
- `onboarding_step` : retiré à la demande d'Adrien (14/10/2026).

## À mettre à jour hors du code avant la publication

- Fiche Play, section « Sécurité des données » : données collectées « Activité de l'application » (interactions) et
  « Informations sur l'application et ses performances » (version), non liées à l'identité, chiffrées en transit, facultatives
  (désactivables). À confirmer contre le formulaire Play en vigueur.
- Politique de confidentialité du site (page de Taipo) : même texte que les sections 2 et 3 de l'écran Confidentialité.

## À vérifier sur appareil (rien n'a été compilé ni exécuté)

0. Au premier build : la résolution de `com.github.aptabase:aptabase-kotlin:0.1.0` sur JitPack, et les imports `com.aptabase.*`
   (`Aptabase`, `InitOptions`, `TrackingMode`) dans `Analytics.kt`, écrits sans avoir pu lire le code du SDK. Le comportement hors ligne
   du SDK (réessai ou non) n'est pas documenté : le test 6 ci-dessous le dira.
1. Installer le debug, faire une correction IA, un collage, un Annuler, changer un réglage.
2. Ouvrir le tableau de bord Aptabase en mode debug : les événements doivent apparaître sous quelques secondes.
3. Désactiver la mesure, refaire une action : plus aucun événement.
4. Ouvrir un champ de mot de passe, utiliser le clavier : aucun événement.
5. (first_open) Désinstaller le debug, le réinstaller, l'ouvrir : un événement `first_open` dans le tableau de bord debug ; le
   fermer et le rouvrir : aucun second événement.
6. Mode avion, faire des actions, rétablir le réseau, faire une action : les événements en attente partent.
