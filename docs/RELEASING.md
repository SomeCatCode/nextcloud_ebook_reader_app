# Releases (APK und Google Play)

Ein Tag `vX.Y.Z` (oder `vX.Y.Z-rc.N` für Vorabversionen) startet [release.yml](../.github/workflows/release.yml):

1. Tests und Lint
2. signiertes **AAB** (für Google Play) und **APK** (zum direkten Installieren)
3. GitHub-Release mit APK und SHA-256 (Vorabversion bei `-rc.N`), Text aus `CHANGELOG.md`
4. Upload des AAB nach **Google Play**, standardmäßig in den Track **internal** (interner Test), inklusive R8-Mapping und „Neuerungen“ aus dem Changelog

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
3. Play Console → **Nutzer und Berechtigungen** → Nutzer einladen: die E-Mail des Dienstkontos, App-Berechtigung für „E-Book Reader“ mit „Releases verwalten“ (für Tests: „Releases für Test-Tracks freigeben“ reicht).

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

Optionale Variablen (Settings → Variables): `PLAY_TRACK` (Standard `internal`), `PLAY_RELEASE_STATUS` (`completed` oder `draft`), `PLAY_RELEASE_NOTES_LANG` (Standard `de-DE`, muss eine Sprache des Store-Eintrags sein).

Ohne `PLAY_SERVICE_ACCOUNT_JSON` baut der Workflow trotzdem und legt AAB/APK als Artefakt und GitHub-Release ab.

## Branches und Testbuilds

- Neue Arbeit kommt per Pull Request in den Branch **`dev`**, nicht direkt nach `main`.
- Jeder Push auf `dev` läuft durch die CI (Lint, Tests, Debug-Build). Im Actions-Lauf liegt unter *Artifacts* `ebookreader-app-dev-<commit>` mit der Debug-APK (30 Tage), installierbar per `adb install -r` neben der Play-Version (eigene Paket-ID `….debug`).
- Für einen Test über Google Play lässt sich auf `dev` ein Vorab-Tag `vX.Y.Z-rc.N` setzen (interner Test).
- Ist `dev` getestet: Version vorbereiten (Schritt 1 unten, `appVersionName`/`appVersionCode` in `gradle.properties` als Standard für lokale Builds), `dev` per Pull Request nach `main` mergen und auf `main` taggen.

## Release erstellen

1. In `CHANGELOG.md` den Abschnitt `## [Unreleased]` in `## X.Y.Z – JJJJ-MM-TT` umbenennen und darüber einen leeren `## [Unreleased]` anlegen.
2. Committen, taggen, pushen:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
3. Die Version erscheint im internen Test. Weitergeben an geschlossene/offene Tests oder Produktion in der Play Console („Release übernehmen“) oder per **Actions → Release → Run workflow** mit dem Tag als Ref und dem gewünschten Track.

**Hinweis:** Solange die App noch nie veröffentlicht wurde (Status „Entwurf“), akzeptiert die Play-API nur Releases mit Status `draft`. Dann `PLAY_RELEASE_STATUS=draft` setzen und den Release in der Play Console freigeben.
