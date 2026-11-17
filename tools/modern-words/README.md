# Mots et noms écrits à la main

Lus par `tools/enrich_modern_usage.py` (voir son en-tête et `dictionaries/SOURCES.txt`, 16/11/2026). Aucune source externe : ce sont des
mots que les lexiques ouverts ne connaissent pas encore, ou que leurs règles écartent (mots courts, emprunts à l'anglais).

- `<code>.txt` : mots en minuscules, un par ligne, « mot » ou « mot fréquence » (`#` = commentaire). Ils sont toujours valides (jamais
  « corrigés »). Sans fréquence : celle de wordfreq si elle est plus haute, sinon 60 % du seuil de proposition (valide, jamais proposé).
  Les abréviations de messagerie ont une fréquence fixe sous le seuil : elles ne sont pas proposées à la place d'une faute de frappe.
- `names-<code>.txt` : noms avec leur casse (marques, sigles, lieux), ajoutés à `dictionaries/names/<code>.txt`. Lettres seulement,
  majuscule initiale (contrainte de `ProperNamesAssetsTest`) : « iPhone » ne peut donc pas y figurer.

Pas de mot de 2 lettres (« tt », « nn », « ui ») : ce sont aussi des fautes de frappe de mots très courants.
