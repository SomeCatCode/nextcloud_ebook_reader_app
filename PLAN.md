# E-Book Reader für Android – Projektplan

Begleit-App zur Nextcloud-App **E-Book Reader** (`ebookreader`, Repo `SomeCatCode/nextcloud_ebook_reader`).

| | |
|---|---|
| Paket-ID | `com.somecatcode.ebookreader` (nicht änderbar nach Play-Store-Veröffentlichung) |
| Herausgeber | SomeCatCode |
| Lizenz | Proprietär, nicht quelloffen (seit 2026-10-05; der Reader-Kern stammt aus dem Server-Repo desselben Rechteinhabers). Kein Werbe-/Tracking-SDK, nur ein optionaler Spendenlink außerhalb von Google Play |
| minSdk / targetSdk | 26 (Android 8.0) / 36 |
| Sprachen | Deutsch, Englisch |

## 1. Ziele (v1)

1. **Mehrere Clouds:** beliebig viele Nextcloud-Konten (auch verschiedene Server), Anmeldung per **Login Flow v2** (App-Passwort, kein Passwort in der App). Bibliotheken pro Konto, umschaltbar oder zusammengeführt.
2. **Bibliothek synchronisieren:** Bücher, Genres/Tags, Regale, Serien, Lesefortschritt per Delta-Sync (`/sync?cursor=`), offline durchsuchbar.
3. **Bücher offline:** einzelne Bücher, ganze Regale oder Serien „offline verfügbar“ machen; Downloads per WebDAV mit Fortsetzen; Speicherverwaltung.
4. **Lesen:** alle Formate des Servers (EPUB, MOBI, AZW3, FB2, FBZ, CBZ, CBR, CB7, CBT) mit demselben Reader-Kern wie im Web (foliate-js + `reader-core`) in einer WebView; online (seitenweise vom Server) oder offline.
5. **Lesestand synchronisieren:** in beide Richtungen, offline-fähig (Warteschlange, `client_updated_at`, Konflikt-Dialog „Neuere Position von … – springen?“).
6. **Minimale Bearbeitung:** Titel, Autoren, Serie + Band, Genres, Tags, Bewertung, Lesestatus (über `PATCH /books/{id}/metadata` bzw. `/app-data`; offline vorgemerkt).

**Nicht in v1:** Editor für Seiten/Kapitel, Konvertierung, Upload, Markierungen/Notizen, OPDS, eigene Server-Unterstützung ohne die Nextcloud-App.

## 2. Architektur

```
┌────────────────────────── Android-App ───────────────────────────┐
│ UI (Jetpack Compose, Material 3)                                 │
│  Konten · Bibliothek · Regale · Serien · Detail/Bearbeiten ·     │
│  Downloads · Einstellungen · Reader-Screen (WebView)             │
│        │                         │                               │
│ ViewModels (Kotlin Coroutines/Flow)                              │
│        │                                                         │
│ Repository-Schicht ── Room (Offline-Datenbank pro Konto)          │
│        │            └ DataStore (Einstellungen)                  │
│ Sync-Engine (WorkManager: periodisch + bei Start + manuell)       │
│ Download-Manager (WorkManager, Range-Resume, Speicherorte)        │
│ API-Client (OkHttp + kotlinx.serialization, OCS + WebDAV)         │
│ Konto-Speicher (App-Passwörter verschlüsselt, Android Keystore)   │
│                                                                  │
│ Reader: WebView ── WebViewAssetLoader (https://appassets…/reader)│
│   reader-web Bundle (reader-core + foliate-js aus dem Server-Repo)│
│   Anfragen /api/* werden in Kotlin abgefangen und mit Auth an den │
│   Server weitergeleitet ODER aus der lokalen Datei bedient        │
│   JS-Bridge: relocate → Fortschritt, toc, Einstellungen           │
└──────────────────────────────────────────────────────────────────┘
                     │ HTTPS (Basic Auth mit App-Passwort)
              Nextcloud + E-Book Reader (≥ 0.5.0)
```

**Wichtige Entscheidungen**
- **Kein Zugangsdaten-Kontakt im JavaScript:** Der Reader in der WebView ruft nur relative URLs auf (`/api/book`, `/api/comic/...`, `/api/item/...`); Kotlin fängt sie per `shouldInterceptRequest` ab und bedient sie aus der Offline-Datei oder leitet sie mit Auth-Header an den Server weiter.
- **Reader-Kern wiederverwenden:** `reader-web/` (Vite) baut aus `packages/reader-core` des Server-Repos (Git-Submodule, festgenagelter Commit) ein eigenständiges Bundle nach `app/src/main/assets/reader/`. Gleiche Sicherheitsmaßnahmen wie im Web (Sandbox, CSP pro Kapitel, SVG-Bereinigung, externe Links nur nach Rückfrage → hier: Android-Intent nach Bestätigung).
- **Locator = Server-Format** (Readium-artig, `href` + `locations`), damit Web und App sich die Position teilen.
- **Ein Room-Datenbank-Satz pro Konto** (Konto-ID im Primärschlüssel), damit Konten sauber getrennt und löschbar sind.

## 3. Datenmodell (Room)

| Tabelle | Inhalt |
|---|---|
| `account` | id, serverUrl, loginName, userId, displayName, serverVersion, appVersion, lastSyncCursor, lastSyncAt |
| `book` | accountId+fileId (PK), format, path, size, title, authors(JSON), series, seriesIndex, description, language, publisher, isbn, rating, readStatus, hasCover, coverEtag, fileEtag, mtime, updatedAt, deleted |
| `book_tag` | accountId, fileId, type (genre/tag), name |
| `progress` | accountId+fileId, locator(JSON), percentage, device, clientUpdatedAt, updatedAt, dirty (noch nicht hochgeladen) |
| `shelf` / `shelf_book` | Regale inkl. smarter Regale (Anzeige über gespeicherte Abfrage → Server) |
| `download` | accountId+fileId, state (queued/running/done/failed), bytes, total, localPath, fileEtag, pinnedBy (book/shelf/series) |
| `pending_edit` | accountId, fileId, patch(JSON), createdAt (Offline-Bearbeitungen) |

## 4. Server-Schnittstellen (vorhanden, ≥ 0.5.0)

- Login Flow v2: `POST /index.php/login/v2`, Polling `POST …/login/v2/poll` → `server`, `loginName`, `appPassword`
- Capabilities: `GET /ocs/v2.php/cloud/capabilities` → `ebookreader.apiVersion/formats` (App installiert? Version?)
- OCS (`OCS-APIRequest: true`, Basic Auth): `/books`, `/books/{id}`, `/sync`, `/facets`, `/series`, `/shelves`, `/progress/{id}`, `/progress/batch`, `/books/{id}/metadata` (PATCH), `/books/{id}/app-data` (PATCH)
- Nicht-OCS (Basic Auth): `/apps/ebookreader/cover/{id}?size=`, `/apps/ebookreader/comic/{id}/pages`, `/comic/{id}/page/{i}?w=`, `/apps/ebookreader/archive/{id}/entries`, `/apps/ebookreader/item/{id}?id=`
- WebDAV: `/remote.php/dav/files/{userId}/{path}` (Download mit `Range`, ETag)
- App-Passwort beim Abmelden widerrufen: `DELETE /ocs/v2.php/core/apppassword`

Fehlt der App-Server oder ist er älter als 0.5.0 → klare Meldung im Konto-Screen.

## 5. Sicherheit & Datenschutz
- App-Passwörter verschlüsselt (Android Keystore, AES-GCM), nie im Klartext in Logs/Backups (`android:allowBackup` für die Schlüssel ausgeschlossen, `dataExtractionRules`).
- Nur HTTPS; HTTP nur nach expliziter Bestätigung (Heimnetz) per Network-Security-Config-Ausnahme? → **v1: nur HTTPS**, Benutzerzertifikate (selbst signiert) über System-Vertrauensspeicher erlaubt.
- Keine Analyse/Tracking, keine Drittanbieter-SDKs mit Datenerhebung → einfache Play-„Data safety“-Angaben.
- Reader-WebView: JavaScript nur für das eigene Bundle, keine Datei-/Content-Zugriffe (`allowFileAccess=false`), externe Links nur nach Bestätigung.

## 6. Projektstruktur

```
nextcloud_ebook_reader_app/
├── app/                         Android-App (Kotlin, Compose)
│   └── src/main/java/com/somecatcode/ebookreader/
│       ├── data/  (api, db, repo, sync, download, account)
│       ├── reader/ (WebView-Host, Bridge, Request-Proxy)
│       ├── ui/    (screens, components, theme, navigation)
│       └── App.kt, MainActivity.kt
├── reader-web/                  Vite-Bundle: reader-core → assets/reader
├── third_party/nextcloud_ebook_reader/   Git-Submodule (Server-Repo, gepinnt)
├── fastlane/metadata/android/{de-DE,en-US}/  Store-Texte, Screenshots
├── .github/workflows/ (ci.yml, release.yml)
├── gradle/libs.versions.toml, settings.gradle.kts, build.gradle.kts
├── PRIVACY.md, README.md, CHANGELOG.md, LICENSE
```

## 7. Play-Store-Veröffentlichung
- Signierung: Upload-Key (Keystore) als GitHub-Secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); Play App Signing aktiv.
- Release-Workflow bei Tag `vX.Y.Z`: Tests → signiertes **AAB** + APK → GitHub-Release; optional Upload in den Play-Store (Track `internal`) per Service-Account-Secret `PLAY_SERVICE_ACCOUNT_JSON`.
- `versionCode` aus dem Tag (z. B. 1.2.3 → 10203), `versionName` = Tag.
- Store-Eintrag (fastlane-Format, de/en), Datenschutzerklärung (`PRIVACY.md` → GitHub Pages-URL), Inhaltsfreigabe, Data-safety-Formular.
- Was **du** tun musst: Play-Console-Konto (einmalig 25 $), App anlegen, Upload-Key erzeugen (Anleitung in README), Secrets hinterlegen.

## 8. Phasen

| # | Phase | Abnahme |
|---|---|---|
| 0 | Grundgerüst: Gradle (Version Catalog), Compose, Theme, Navigation, CI, Lizenz, reader-web Build-Pipeline | `./gradlew assembleDebug` + Tests grün, App startet im Emulator |
| 1 | Konten: Login Flow v2, mehrere Konten, Keystore-Speicher, Capability-Check, Abmelden (App-Passwort widerrufen) | Anmeldung an echter Nextcloud möglich |
| 2 | Sync & Bibliothek: API-Client, Room, Delta-Sync, Bibliothek (Raster/Liste, Suche, Filter Genres/Tags/Status, Regale, Serien), Cover-Cache | Bibliothek offline sichtbar, Pull-to-refresh |
| 3 | Downloads: offline verfügbar für Buch/Regal/Serie, Fortsetzen, Speicheranzeige, automatisches Nachladen bei neuen Bänden | Buch im Flugmodus öffnbar |
| 4 | Reader: WebView + reader-web, Request-Proxy (online/offline), Fortschritt hoch/runter, Konflikt-Dialog, Reader-Einstellungen, E-Ink-Modus | Position Web ↔ App identisch |
| 5 | Bearbeiten: Titel, Autoren, Serie, Genres/Tags, Bewertung, Status; Offline-Warteschlange | Änderung erscheint im Web |
| 6 | Feinschliff & Store: i18n de/en, Barrierefreiheit, Fehlerzustände, Release-Workflow, Store-Texte, Datenschutz | signiertes AAB im GitHub-Release |
