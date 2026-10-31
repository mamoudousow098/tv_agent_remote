# Télécommande Agent (à installer sur la TV)

Petite application Android (Kotlin, aucune dépendance) qui permet au téléphone de
piloter la TV sur le Wi-Fi. **Non compilée de mon côté** : construisez-la dans Android Studio.

## 1. Créer le projet dans Android Studio

1. *File → New → New Project → **No Activity***
   - Name : `tv_agent`
   - Package name : `sn.tvremote.agent`
   - Language : **Kotlin**, Minimum SDK : **API 24**, Build configuration language : **Kotlin DSL**
2. Copiez par-dessus le contenu de `app/src/main/` de cette archive
   (manifest, `kotlin/…`, `res/layout`, `res/values`, `res/xml`).
   Si Android Studio a créé `res/values/themes.xml` ou `strings.xml`, remplacez-les.
3. Dans `app/build.gradle.kts` (généré), alignez : `compileSdk = 34`, `minSdk = 24`,
   `targetSdk = 34`, et dans `buildTypes.release` :
   `signingConfig = signingConfigs.getByName("debug")` (voir le fichier de référence).
   Supprimez les dépendances AndroidX inutilisées si la compilation se plaint d'un thème.
4. Le projet « No Activity » n'a pas forcément d'icône : si `@mipmap/ic_launcher` manque,
   *clic droit sur `res` → New → Image Asset* pour en générer une.

```bat
gradlew assembleRelease
```
APK : `app\build\outputs\apk\release\app-release.apk`

## 2. Installer sur la TV (une seule fois, demande de naviguer sur la TV)

1. Copier l'APK sur une clé USB, la brancher sur la TV.
2. Ouvrir l'APK avec le gestionnaire de fichiers de la TV, autoriser l'installation
   depuis cette source, installer.
3. Ouvrir **Télécommande Agent**, puis « Ouvrir les réglages d'accessibilité »
   → *Télécommande Agent* → **Activer**.
4. L'écran de l'agent affiche ✅, l'adresse IP et un **code à 6 chiffres**.

Ensuite, plus besoin de toucher la TV : le service redémarre seul avec la TV.

## 3. Tester depuis le PC (même Wi-Fi), avant l'application téléphone

Remplacez l'IP et le code par ceux affichés sur la TV.

```bat
curl http://10.214.173.16:8787/info

curl -X POST http://10.214.173.16:8787/pair -d "{\"code\":\"123456\",\"client\":\"PC\"}"

set TOKEN=collez_le_jeton_ici
curl -X POST http://10.214.173.16:8787/command -H "Authorization: Bearer %TOKEN%" -d "{\"command\":\"volumeUp\"}"
curl -X POST http://10.214.173.16:8787/command -H "Authorization: Bearer %TOKEN%" -d "{\"command\":\"home\"}"
curl -X POST http://10.214.173.16:8787/command -H "Authorization: Bearer %TOKEN%" -d "{\"command\":\"down\"}"
```

## Protocole (v1)

| Requête | Corps | Réponse |
|---|---|---|
| `GET /info` | — | `{app, protocol, name, android, paired, commands[], textInput}` |
| `POST /pair` | `{"code","client"}` | `{"ok":true,"token"}` · 403 code faux · 429 bloqué 60 s après 5 erreurs |
| `POST /command` | `{"command":"up"}` | `{"ok":true}` · 401 sans jeton · 422 non disponible |
| `POST /text` | `{"text":"…"}` | écrit dans le champ texte actif |
| `POST /launch` | `{"package":"com.google.android.youtube.tv"}` | lance l'application |

Jeton : en-tête `Authorization: Bearer <token>`. Seule son empreinte SHA-256 est stockée.

## Ce que l'agent sait faire

| Disponible | Indisponible (aucune API publique Android) |
|---|---|
| Haut, bas, gauche, droite, OK (Android 13+) | Allumer / éteindre |
| Retour, Accueil, Menu (= applis récentes) | Chaînes +/− |
| Volume +/−, muet (barre de volume affichée) | Pavé numérique 0–9 |
| Lecture/pause, stop, avance/retour rapide | Choix de la source (HDMI, TV) |
| Saisie de texte, lancement d'applications | |

Lecture/pause agit sur l'application média active (YouTube, lecteur vidéo…), pas sur le tuner TV.
