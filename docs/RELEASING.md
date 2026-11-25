# Releases (APK und Google Play)

Die App ist bei Google Play veröffentlicht: [Ebook Reader for Nextcloud](https://play.google.com/store/apps/details?id=com.somecatcode.ebookreader).

Ein Tag `vX.Y.Z` (oder `vX.Y.Z-rc.N` für Vorabversionen) startet [release.yml](../.github/workflows/release.yml):

1. Tests und Lint
2. signiertes **AAB** (für Google Play) und **APK** (zum direkten Installieren)
3. GitHub-Release mit APK und SHA-256 (Vorabversion bei `-rc.N`), Text aus `CHANGELOG.md`
4. Upload des AAB nach **Google Play** in den Track aus `PLAY_TRACK` (ohne Variable: `internal`, interner Test), mit R8-Mapping, nativen Debug-Symbolen und „Neuerungen“ aus dem Changelog

`versionName` kommt aus dem Tag, `versionCode` wird daraus berechnet und steigt immer:
`Major·1000000 + Minor·10000 + Patch·100 + RC` (finale Version: RC = 99).
Beispiele: `v0.1.0-rc.1` → 10001, `v0.1.0` → 10099, `v0.2.0` → 20099. Minor/Patch höchstens 99.

## Einmalige Einrichtung

### 1. Upload-Schlüssel

Google Play signiert die App selbst (Play App Signing). Hochgeladen wird mit einem eigenen **Upload-Schlüssel**.
Falls du für den ersten internen Test schon ein AAB hochgeladen hast, nimm **denselben** Schlüssel, sonst lehnt Play den Upload ab.
Neu anlegen (Passwort selbst wählen und sicher aufbewahren, der Schlüssel gehört nicht ins Repository):

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Verlorene Upload-Schlüssel lassen sich in der Play Console zurücksetzen (Einrichtung → App-Integrität), das dauert aber einige Tage.

### 2. Dienstkonto für die Play-API

1. Google Cloud Console: Projekt wählen oder anlegen, **Google Play Android Developer API** aktivieren.
2. IAM → Dienstkonten → Dienstkonto anlegen → Schlüssel → JSON herunterladen.
3. Play Console → **Nutzer und Berechtigungen** → Nutzer einladen: die E-Mail des Dienstkontos, App-Berechtigung für „Ebook Reader for Nextcloud“ (`com.somecatcode.ebookreader`) mit „Releases verwalten“ (für Tests: „Releases für Test-Tracks freigeben“ reicht).

Die **erste** Version muss in der Play Console von Hand hochgeladen werden, erst danach kennt die API die App.

### 3. GitHub-Secrets

Repository → Settings → Environments → **`play`** anlegen (optional: nur Tags `v*` erlauben) und dort als Secrets eintragen:

| Secret | Inhalt |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 upload.jks` (unter Windows: `certutil -encode upload.jks out.txt`, dann nur die Zeilen zwischen BEGIN/END ohne Umbrüche) |
| `ANDROID_KEYSTORE_PASSWORD` | Passwort des Keystores |
| `ANDROID_KEY_ALIAS` | z. B. `upload` |
| `ANDROID_KEY_PASSWORD` | Passwort des Schlüssels (oft gleich dem Keystore-Passwort) |
| `PLAY_SERVICE_ACCOUNT_JSON` | kompletter Inhalt der JSON-Datei des Dienstkontos |

Optionale Variablen: `PLAY_TRACK` (Standard `internal`), `PLAY_RELEASE_STATUS` (`completed` oder `draft`), `PLAY_RELEASE_NOTES_LANG` (Standard `de-DE`, muss eine Sprache des Store-Eintrags sein).

**Achtung:** Der Job läuft im Environment `play`. Variablen des Environments haben Vorrang vor Variablen des Repositorys. Stand November 2026:

| Ort | `PLAY_TRACK` | `PLAY_RELEASE_STATUS` |
|---|---|---|
| Environment `play` (wirksam) | `internal` | `draft` |
| Repository | `alpha` | nicht gesetzt |

Ein Tag lädt deshalb einen **Entwurf** in den **internen Test** hoch. Gib den Entwurf in der Play Console frei und übernimm ihn in den nächsten Track.

Ohne `PLAY_SERVICE_ACCOUNT_JSON` baut der Workflow trotzdem und legt AAB/APK als Artefakt und GitHub-Release ab.

## Branches und Testbuilds

- Neue Arbeit kommt per Pull Request in den Branch **`dev`**, nicht direkt nach `main`.
- Jeder Push auf `dev` läuft durch die CI (Lint, Tests, Debug-Build). Im Actions-Lauf liegt unter *Artifacts* `ebookreader-app-dev-<commit>` mit der Debug-APK (30 Tage). Die Debug-APK hat die eigene Paket-ID `….debug`. Sie lässt sich mit `adb install -r` neben der Play-Version installieren.
- Für einen Test über Google Play lässt sich auf `dev` ein Vorab-Tag `vX.Y.Z-rc.N` setzen.

## Release erstellen

1. Branch `release/X.Y.Z` von `dev` anlegen.
2. In `CHANGELOG.md` den Abschnitt `## [Unreleased]` in `## X.Y.Z – JJJJ-MM-TT` umbenennen. Darüber einen leeren Abschnitt `## [Unreleased]` anlegen.
3. In `gradle.properties` `appVersionName` und `appVersionCode` setzen. Die Werte gelten für lokale Builds.
4. Commit „Prepare X.Y.Z“ erstellen. Pull Request nach `dev` mergen.
5. Pull Request „Release X.Y.Z“ von `dev` nach `main` mergen.
6. Auf `main` taggen und den Tag pushen:
   ```bash
   git tag -a vX.Y.Z -m "X.Y.Z" && git push origin vX.Y.Z
   ```
7. Der Workflow lädt das AAB hoch (Track und Status: siehe „GitHub-Secrets“). Der Workflow installiert das NDK aus `ndkVersion`, damit das AAB die nativen Debug-Symbole enthält.
8. In der Play Console: den Release prüfen, freigeben und in den nächsten Track übernehmen („Release übernehmen“), zuletzt in die Produktion.

**Hinweis:** Lade denselben Tag nicht ein zweites Mal über **Actions → Release → Run workflow** hoch. Google Play lehnt einen bereits verwendeten `versionCode` ab.
