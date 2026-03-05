## gradle-wrapper.jar manquant

Le fichier `gradle-wrapper.jar` n'est pas inclus dans ce zip car il est un binaire (non versionnable).

### Solution : Android Studio le génère automatiquement

Quand tu ouvres le projet dans Android Studio pour la première fois :
1. Android Studio détecte le `gradle-wrapper.properties`
2. Il télécharge et installe automatiquement Gradle 8.4
3. Le `gradle-wrapper.jar` est créé dans ce dossier

**Tu n'as rien à faire manuellement.**

### Alternative si Android Studio ne le génère pas

Dans le terminal Android Studio (View → Tool Windows → Terminal) :

```
gradle wrapper --gradle-version 8.4
```

Ou télécharge le JAR directement :
https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar
