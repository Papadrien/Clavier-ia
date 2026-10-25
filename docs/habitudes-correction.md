# Habitudes de correction

Le clavier apprend de ce que l'utilisateur tape et valide, et de ses corrections acceptées ou annulées, pour
mieux choisir entre plusieurs corrections possibles. L'étape « suggestions vocales / contexte » est reportée
à la roadmap.

## Conception

- `CorrectionHabits` (module `core`, logique pure) : trois compteurs par langue (FR / EN), insensibles à la casse.
  - **Annulations** : combien de fois « tapé → corrigé » a été annulé.
  - **Acceptations** : combien de fois cette paire est restée en place.
  - **Usage** : combien de fois un mot a été tapé et validé.
- Aucun texte conservé : seulement des mots isolés et des paires de mots avec leur compte. Un mot avec chiffre ou de
  plus de 30 caractères n'est jamais retenu (même règle que `NextWordModel`). Compteurs plafonnés à 1 000, 5 000
  entrées au plus (les entrées vues une fois sont élaguées, puis les comptes divisés par deux).
- Stockage : `correction_habits.enc`, chiffré comme le modèle « mot suivant » (`NextWordCrypto`, AES-256-GCM, HKDF)
  avec un secret Keystore propre (`taipo_correction_habits_key`). Fichier et clé exclus des sauvegardes
  (`backup_rules.xml`, `data_extraction_rules.xml`, vérifié par `BackupRulesTest`). Fichier illisible : supprimé,
  les habitudes repartent de zéro.
- Réglages (Paramètres du clavier → Apprentissage) : option « Apprendre de mes habitudes » (activée par défaut,
  `KeyboardPreferences.isHabitLearningEnabled`) et bouton « Effacer mes habitudes de correction ».
- À venir : l'apprentissage ne s'exerce que si `learningAllowed` est vrai (déjà faux dans les champs sensibles et en
  navigation privée) et si l'option est activée.

## Phases

1. **Socle** (livré, Taipo-complet-132) : `CorrectionHabits`, `CorrectionHabitsRepository`, option, bouton Effacer. Aucun changement
   de comportement : rien ne l'alimente, la correction ne le consulte pas.
2. **Apprentissage silencieux** (livré) : enregistrement des signaux (annulation, acceptation, usage) avec compteurs de
   debug dans logcat (`SuggestionController`, builds debug, filtre `SuggestionController` : annulations, acceptations,
   usages, entrées ; jamais de texte, règle d'`AppLog`). La correction n'en tient pas compte.
   - La règle est écrite une seule fois, dans `HabitSignals` (core, testé en JVM). Le clavier ne signale que trois
     événements : correction appliquée, correction annulée (retour arrière), mot terminé (`learnFromTyping`).
   - **Acceptée** : à la fin du mot suivant, le mot corrigé est toujours juste avant ce mot (lecture du texte, donc
     indépendante des ~20 endroits qui remettent `pendingAutocorrection` à zéro).
   - **Usage** : un mot terminé, sauf s'il vient d'être corrigé automatiquement (sinon les corrections se
     renforceraient elles-mêmes) ; ni nombre, ni emoji.
   - Seules les autocorrections automatiques comptent. Une suggestion choisie à la main puis annulée, ou le retour à la
     forme d'origine par la suggestion de gauche, ne sont pas comptés (à voir à l'usage).
   - Une correction qui ne change pas exactement un mot n'est pas enregistrée.
   - Correctif de la phase 3 : en phase 2, la paire était construite à partir du texte remplacé par le clavier, qui ne
     couvre parfois que la fin du mot, et l'acceptation ne pouvait pas être reconnue. Les compteurs de la phase 2
     sont donc à effacer (bouton Effacer) avant de tester la phase 3.
3. **Effet sur les annulations** (livré) : après 2 annulations de la même correction (même mot tapé, même mot
   corrigé, même langue), elle n'est plus appliquée : le mot reste tel que tapé.
   - Le contrôle est dans `SuggestionController.autocorrectionFor`, donc il couvre aussi les corrections de contexte
     (« a » / « à »...), pas seulement `Dictionary.correctionFor`. Les suggestions de la barre ne sont pas touchées.
   - Une autre correction du même mot reste possible ; seule la paire refusée est bloquée.
   - Le refus est définitif : seul « Effacer mes habitudes de correction » le lève. Option désactivée ou champ où
     l'apprentissage est interdit : le refus n'est pas appliqué.
   - Les paires sont enregistrées sur le mot entier (« jai » -> « j'ai »), pas sur la partie remplacée par le clavier
     (« ai » -> « 'ai »), ce que la phase 2 faisait à tort (voir ci-dessous).
   - Seuil : `CorrectionHabits.REFUSAL_THRESHOLD = 2`.
4. **Effet sur le départage** (livré) : à distance d'édition égale, une paire acceptée passe devant le contexte, qui passe
   devant l'usage. Ordre dans `Dictionary.correctionFor` : distance, mot personnel, habitudes, proximité des touches,
   inversion, fréquence du corpus.
   - **Paire acceptée** : la paire « tapé -> candidat » compte à partir de 2 acceptations
     (`CorrectionHabits.ACCEPTANCE_THRESHOLD`) ; plus elle est acceptée, plus elle pèse.
   - **Contexte** : combien de fois le candidat a suivi le mot qui précède, lu dans les bigrammes de `NextWordModel`
     (accesseur `bigramCount`, rien n'est dupliqué). En début de phrase, le contexte est le début de phrase ; dans une
     fenêtre de texte coupée au début, le premier mot (peut-être incomplet) ne compte pas ; un nombre juste avant coupe
     le contexte (même règle que l'apprentissage du mot suivant, `HabitContext.previousWordOf`).
   - **Usage** : combien de fois le candidat a été tapé et validé.
   - Le score (`HabitScore`) est comparé dans cet ordre ; des scores égaux (en particulier tous nuls) ne départagent
     rien et la suite (proximité, inversion, fréquence) décide comme avant.
   - **Paramètre** : `Dictionary.correctionFor(..., habits: HabitContext? = null)`. `HabitContext` réunit les compteurs,
     la langue, les bigrammes et le mot précédent ; il est construit par `SuggestionController.habitContextFor`, donc
     absent (null) si l'option « Apprendre de mes habitudes » est désactivée ou si le champ interdit d'apprendre.
     Aucun candidat n'est ajouté ni écarté, et un candidat plus loin ne passe jamais devant un plus proche.
   - **Bande de suggestions** : les trois emplacements de mots (`suggestionSlotsFor`, `placementSlotsFor`,
     `suggestionsFor`) reçoivent les mêmes habitudes, pour que la correction affichée en gras soit celle qui sera
     appliquée. (Le refus de la phase 3 reste, lui, appliqué seulement à l'autocorrection.)
   - **Non couverts** : le mot collé à une élision (« t'inscrir ») est examiné sans habitudes, seul son reste étant
     cherché ; les corrections de contexte français (« a » / « à ») ne les consultent pas ; `alternativesFor` (les
     alternatives proposées sur un mot déjà écrit) garde son classement par fréquence.
   - **Sans habitudes, rien ne change** : vérifié par `HabitTieBreakTest` (3 000 fautes tirées au hasard, avec
     `habits = null`, des habitudes vides, et des habitudes sans rapport avec les candidats : résultat identique) ;
     un second test vérifie sur des habitudes tirées au hasard que le candidat retenu est toujours à la même distance.
   - **Débogage** : en build debug, logcat (filtre `SuggestionController`) compte les fois où le départage a changé la
     correction par rapport à ce que le moteur aurait donné sans habitudes (un nombre, jamais de texte).

## Adresses e-mail

Les adresses e-mail tapées sont retenues avec les habitudes et proposées dans la bande de suggestions dès que
l'utilisateur en commence une.

- **Stockage** : dans `CorrectionHabits`, à part des trois compteurs (`recordEmail`, `emailUsage`, `suggestEmails`).
  Adresse en minuscules -> nombre de fois tapée ; l'ordre est celui de la dernière frappe. Au plus 200 adresses
  (`MAX_EMAILS`) : la moins tapée, la plus ancienne à égalité, laisse la place. Lignes `E <tab> adresse <tab> compte`
  dans le même fichier chiffré (`correction_habits.enc`) ; une ancienne version qui relirait ce fichier ignore ces lignes.
  Les adresses ne comptent ni dans `size()` ni dans `isEmpty()` (elles ne jouent pas sur la correction), mais « Effacer
  mes habitudes de correction » et l'option « Apprendre de mes habitudes » les couvrent comme le reste.
- **Lecture du texte** : `EmailText` (module `core`). Adresse simple (ASCII, extension de 2 lettres au moins, 100
  caractères au plus). Le texte lu est la fenêtre de 50 caractères avant le curseur : un jeton qui touche le début
  d'une fenêtre coupée n'est jamais lu (début peut-être manquant), donc une adresse de plus d'environ 45 caractères
  n'est ni retenue ni proposée.
- **Apprentissage** (`HabitSignals.emailCompleted`, appelé par `SuggestionController.learnFromTyping`) : à l'espace, au
  retour à la ligne et quand une suggestion est choisie, comme les mots ; en plus, à la sortie du champ
  (`SuggestionController.finishField`, appelé par `onFinishInputView`) pour une adresse tapée en dernier sans espace
  après elle (champ d'adresse, formulaire de connexion), seulement si l'utilisateur a tapé dans ce champ (pas de texte
  collé, rempli par une appli ou inséré par le mode prompt). Une même adresse n'est pas comptée deux fois tant que rien
  n'est tapé de plus (retour à la ligne puis sortie du champ).
- **Où** : tout champ de texte, y compris les champs d'adresse e-mail (là où les autres suggestions sont coupées), sauf
  mot de passe, adresse web, filtre, champs non texte, navigation privée (`IME_FLAG_NO_PERSONALIZED_LEARNING`) et option
  « Apprendre de mes habitudes » désactivée (`SuggestionPolicy.allowsEmailSuggestions`).
- **Pas des mots** : une adresse terminée ne compte pas comme usage de ses morceaux (« gmail », « com ») et
  n'apprend rien au modèle du mot suivant.
- **Suggestion** (`SuggestionController.emailChoiceFor`) : adresses retenues qui commencent comme le jeton qui touche le
  curseur (casse sans effet), les plus tapées d'abord. Pertinence : dès le 3e caractère en texte libre (pour ne pas
  encombrer la frappe d'un mot ordinaire), dès le 1er caractère dans un champ d'adresse ou dès qu'un « @ » est tapé.
  Rien n'est proposé curseur posé sur un mot ou au milieu d'une adresse.
  - Texte libre, sans « @ » : la meilleure adresse prend le dernier emplacement, les deux premiers mots restent.
  - « @ » tapé ou champ d'adresse : jusqu'à 3 adresses, seules (`WordSuggestion.Kind.EMAIL`), à la place des mots.
  - Toucher une adresse remplace le début tapé par l'adresse entière, suivie d'une espace (sans espace dans un champ
    d'adresse) ; elle compte comme une adresse tapée. Jamais d'effet à l'espace, jamais en gras.
- **Autocorrection** : une adresse en cours de frappe (« @ » après au moins un caractère) n'est jamais corrigée
  (« gmal », « com »...) ; cela évite aussi d'enregistrer une adresse déformée.
- **Non couverts** : adresses commençant par autre chose que leur début (on ne cherche pas « dupont » dans
  « jean.dupont@... ») ; adresses avec caractères non ASCII ; prédiction d'une adresse sans en avoir tapé le début.

Seuils (2 annulations, 2 acceptations) à ajuster à l'usage. Risque connu de la phase 4 : lever des ambiguïtés que le
code laissait sans correction, donc corriger un peu plus, y compris à tort. Cas inverse, plus rare : si deux candidats
favorisés par les habitudes sont à égalité parfaite (même score, même coût, même fréquence), le mot n'est pas corrigé,
alors qu'un troisième candidat, meilleur sur la seule proximité des touches, l'aurait été sans habitudes.
Contexte et usage comptent dès une occurrence : si cela corrige trop à la légère, ajouter un seuil minimum dans
`HabitContext.score`.
