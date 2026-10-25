# quality/

Baselines ktlint et detekt (dette acceptée à la date de leur génération). Générer **une fois** :

```
./gradlew ktlintBaseline detektBaseline
```

puis committer `ktlint-baseline.xml` et `detekt-baseline.xml`. Voir `docs/qualite.md`.
