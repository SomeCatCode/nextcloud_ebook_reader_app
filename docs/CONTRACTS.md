# Technische Verträge (verbindlich für W-DATA, W-UI, W-READER)

Fachliche Grundlage: `PLAN.md`. Diese Datei legt die **technischen Verträge** der Phase 0 fest. Die Kotlin-Interfaces sind echter Code (ohne Implementierung) und die maßgebliche Quelle; dieses Dokument erklärt sie und regelt Zuständigkeiten. Wer abweichen muss, dokumentiert das in `docs/DEVIATIONS.md` (Begründung) und ändert Signaturen nie still: Änderungen an Dateien eines anderen Workers nur per Absprache bzw. als eigener, klar benannter Commit.

Server-Quellen der Datenformate (Schwesterrepo `nextcloud_ebook_reader`, hier als Submodule `third_party/nextcloud_ebook_reader`): `src/types.ts`, `lib/ResponseDefinitions.php`, `openapi.json`, `docs/CONTRACTS*.md`. Zeitstempel sind überall **Millisekunden seit Epoch**.

## 1. Grundlagen

| Thema | Festlegung |
|---|---|
| Paket | `com.somecatcode.ebookreader`, minSdk 26, compile/targetSdk 36, Kotlin, Compose, Material 3 |
| DI | Manuell: `AppContainer` (Interface) / `DefaultAppContainer`. Kein Hilt. ViewModels bekommen ihre Abhängigkeiten per `ViewModelProvider.Factory` aus dem Container (`(application as App).container`). Tests setzen `App.container` auf ein Fake. |
| Threading | Alle `suspend`-Funktionen sind main-safe (wechseln selbst auf `Dispatchers.IO`). Flows sind kalt und kommen aus Room. |
| JSON | `ApiJson` (kotlinx.serialization; `ignoreUnknownKeys`, `explicitNulls=false`) für Server und JSON-Spalten, `BridgeJson` für die Reader-Bridge. |
| Konto-ID | Zufällige UUID (String), unabhängig von URL und Benutzer. Buch-Schlüssel = `BookKey(accountId, fileId)`. |
| Sprache | UI-Texte in `res/values` (en) und `res/values-de`; keine hartkodierten Strings in Composables. |
| Fehler | Netzwerkfehler erreichen die UI nie als Exception aus Repositories, sondern als Zustand (`SyncState`, `DownloadState`, `EditFailure`). Nur `EbookApi` wirft `ApiException`. |
| Lizenz-Header | Kotlin-Dateien benötigen keinen Header (Repo-Lizenz AGPL-3.0-or-later); TS-Dateien in `reader-web/src` tragen den SPDX-Header. |

## 2. API-Client (`data/api`)

Dateien: `Dtos.kt` (alle Wire-Typen), `EbookApi.kt` (Interfaces), `ApiException.kt`. Die DTOs spiegeln `src/types.ts` bzw. `EbookReaderBook` & Co. 1:1 (inkl. `hasMore` im Sync, `downloadable`, `overrides`, `hasSidecar`).

**OCS.** Basis `{server}/ocs/v2.php/apps/ebookreader/api/v1`. Header `OCS-APIRequest: true`, `Accept: application/json`, Query `format=json`, Basic Auth. Antwort `{"ocs":{"meta":{status,statuscode,message},"data":…}}` wird zu `data` entpackt (`OcsEnvelope<T>`). Fehlerabbildung: 401 `Unauthorized`, 403 `Forbidden`, 404 `NotFound`, 400/422 `BadRequest`, 429 `RateLimited`, 5xx/Parsefehler `Server`, IO `Network`. **Ausnahme:** `PUT /progress/{id}` mit 409 liefert `ProgressPutResult.Conflict(current)` statt Exception.

**Endpunkte** (alle in `EbookApi`; Rate-Limits des Servers beachten, v. a. Fortschritt 240/min, Batch 60/min):

| Methode in `EbookApi` | HTTP | Antwort-DTO |
|---|---|---|
| `capabilities()` | `GET /ocs/v2.php/cloud/capabilities` | `CapabilitiesResponse` (`capabilities.ebookreader` fehlt = App nicht installiert) |
| `currentUser()` | `GET /ocs/v2.php/cloud/user` | `CloudUser` |
| `checkCompatibility()` | Capabilities + Probe `GET /series` (404 = älter als 0.5.0, die Capabilities enthalten keine App-Version) | `ServerCompatibility` |
| `books(BookQuery)` | `GET /books?search&include[]=type:name&exclude[]&match&status&sort&order&inSeries&limit&offset` | `BookListDto` |
| `book(id)` | `GET /books/{id}` | `BookDto` |
| `sync(cursor)` | `GET /sync?cursor=` (max. 500 je Liste, solange `hasMore`) | `SyncDto` |
| `facets()` | `GET /facets` | `FacetsDto` |
| `series(query)` | `GET /series` | `List<SeriesDto>` (aus `{series:[…]}`) |
| `shelves()` | `GET /shelves` | `List<ShelfDto>` (aus `{shelves:[…]}`) |
| `recentBooks(limit)` | `GET /progress/recent` | `List<BookDto>` |
| `progress(id)` | `GET /progress/{id}` | `ProgressDto?` (`data: null` = nie geöffnet) |
| `putProgress(id, body)` | `PUT /progress/{id}` | `Stored` / `Conflict` (409) |
| `putProgressBatch(items)` | `POST /progress/batch` (max. 100, Client teilt auf) | `List<ProgressBatchResultItem>` (`ok|conflict|error`) |
| `patchAppData(id, patch)` | `PATCH /books/{id}/app-data` (Rating `null` = löschen, nur wenn `setRating`) | `BookDto` |
| `patchMetadata(id, patch)` | `PATCH /books/{id}/metadata` (nur gesetzte Schlüssel, `JsonNull` = Feld leeren) | `SaveResultDto` |
| `comicPages(id)` | `GET /index.php/apps/ebookreader/comic/{id}/pages` | `ComicPagesDto` |
| `archiveEntries(id)` | `GET /index.php/apps/ebookreader/archive/{id}/entries` | `ArchiveEntriesDto` |
| `coverUrl / comicPageUrl / itemUrl / davFileUrl` | `/cover/{id}?size=small\|large`, `/comic/{id}/page/{i}?w=`, `/item/{id}?id=`, `/remote.php/dav/files/{userId}/{path}` | URL-Builder ohne I/O |
| `revokeAppPassword()` | `DELETE /ocs/v2.php/core/apppassword` | – |
| `http: AuthenticatedHttp` | rohe Aufrufe mit Auth (Cover via Coil, WebDAV, Reader-Proxy) | – |

Pfade in WebDAV-URLs werden segmentweise prozentkodiert. Cover-Aufrufe senden `If-None-Match` mit `coverEtag`. Kein Folgen von Redirects auf andere Hosts mit Auth-Header.

**Login Flow v2** (`LoginFlowClient`): `start(serverInput)` normalisiert die Eingabe (nur HTTPS) und ruft `POST {server}/index.php/login/v2`; die UI öffnet `LoginFlowStart.login` im Browser (Custom Tab). `pollOnce(poll)` ruft `POST poll.endpoint` mit `token=…` und liefert `null` bei 404, bis der Nutzer fertig ist; Poll-Intervall 2 s, Abbruch nach 20 Minuten. Danach `ApiClientFactory.forCredentials(...)` → `checkCompatibility()` → `currentUser()` → `AccountStore.add(...)`.

**Mindestversion:** `ServerCompatibility.AppMissing` und `AppTooOld` müssen im Konto-Screen klare Meldungen erzeugen (PLAN.md Abschnitt 4).

## 3. Konten (`data/account`)

`AccountStore` (add/list/get/remove, `updateCredentials`, `credentials`) und `CredentialCipher`. Metadaten in Room, App-Passwort **verschlüsselt** in `Context.noBackupFilesDir` (nie im Backup): AES-256-GCM, nicht exportierbarer Schlüssel im Android Keystore (Alias `ebookreader_credentials`), 12-Byte-IV pro Wert, Konto-ID als Associated Data, Speicherformat Base64 von `iv || ciphertext || tag`. Ist der Schlüssel ungültig, liefert `credentials()` `null` und das Konto zeigt „Neu anmelden“ (`updateCredentials` behält Daten und Downloads). `Credentials.toString()` maskiert das Passwort; Passwörter und `Authorization`-Header dürfen nie in Logs.

`remove(accountId)`: Passwort löschen, Room-Zeilen (Kaskade), lokale Dateien. App-Passwort beim Server widerrufen ist Sache des Aufrufers **vorher** (`revokeAppPassword`, Fehler darf das lokale Löschen nicht verhindern, aber Hinweis anzeigen).

## 4. Datenbank (`data/db`)

Echter Code: `Entities.kt`, `Daos.kt`, `AppDatabase.kt` (Version 1, Schema-Export nach `app/schemas`, wird eingecheckt). **Eine** Datenbank für alle Konten; jede Tabelle trägt `accountId`, Fremdschlüssel mit `ON DELETE CASCADE` vom `account` aus (Konto löschen = alles weg). Tabellen laut PLAN.md Abschnitt 3: `account`, `book`, `book_tag`, `progress`, `shelf`, `shelf_book`, `download`, `pending_edit`.

Festlegungen: JSON-Spalten (`authors`, `overrides`, `locator`, `query`, `coverFileIds`, `patch`) sind `ApiJson`-Strings. `book.deleted` ist ein Soft-Delete (Server meldet gelöschte `fileId`s); `purgeDeleted` erst nach Entfernen der Downloads. `progress.dirty` = lokale Änderung noch nicht hochgeladen. Manuelle Regale: Mitgliedschaft in `shelf_book` (Server hat keinen Mitglieder-Endpunkt; Ermittlung über `GET /books?include[]=shelf:<Name>`), smarte Regale werden über die gespeicherte Abfrage ausgewertet. Schemaänderungen brauchen Versionssprung + Migration; **kein** `fallbackToDestructiveMigration` (nach dem ersten Release).

## 5. Repositories, Sync, Downloads

Interfaces in `data/repo/Repositories.kt`: `LibraryRepository`, `ProgressRepository`, `DownloadRepository`, `EditRepository`, `SettingsRepository` (+ UI-Modelle `LibraryBook`, `ShelfInfo`, `SeriesInfo`, `LibraryFilter`, `ProgressConflict`, `OfflineItem`, `OfflineTarget`, `AppSettings`). ViewModels sprechen **nur** mit diesen Interfaces (und `AccountStore`, `SyncEngine`), nie mit DAOs oder `EbookApi`.

**Fortschrittsregel (Konflikte):** Schreiben lokal immer sofort (`dirty=true`, `clientUpdatedAt=now`). Beim Hochladen gewinnt der größere `clientUpdatedAt` (Server-Regel, 409 → `current`). Vor dem Öffnen eines Buchs online ruft die UI `checkRemote`; ein Ergebnis ≠ `null` löst den Dialog „Neuere Position von *Gerät* – springen?“ aus (`acceptRemote` / `keepLocal`). Offline wird still lokal geöffnet. `device` = `AppSettings.deviceName ?: Build.MODEL`.

**Bearbeiten:** optimistisch in Room, Zeile in `pending_edit` (spätere Änderungen derselben Art und desselben Buchs werden zusammengeführt), Upload über `flushPending` (nach jedem Sync, bei Konnektivität). Dauerhaft abgelehnt (400/403/404) → verwerfen und über `EditRepository.failures` melden.

**`SyncEngine`** (`data/sync/SyncEngine.kt`): Ablauf, Cursor-Regeln, WorkManager-Namen (`sync-<accountId>`, `sync-periodic`) und Fehlerabbildung (`SyncError`) stehen im KDoc. Cursor erst nach vollständig geschriebener Seite speichern (eine Room-Transaktion pro Seite); ungültiger Cursor (HTTP 400) → Vollsync.

**`DownloadManager`** (`data/download/DownloadManager.kt`): WebDAV-Download mit `.part`-Datei, `Range` + `If-Range`, atomarem Umbenennen, max. 2 parallel, Speicherort `getExternalFilesDir("books")/<accountId>/<fileId>.<format>`. Fortschritt/Status ausschließlich über die `download`-Tabelle.

## 6. Reader (`reader/`, `reader-web/`)

Echter Code: `ReaderBridge.kt` (Nachrichten, `BridgeJson`), `ReaderRequestProxy.kt`, `ReaderHost.kt`. Das Bundle `reader-web` lädt `createReader` aus `third_party/.../packages/reader-core/index.ts`, wird nach `app/src/main/assets/reader/` gebaut (nicht eingecheckt; Gradle-Task `buildReaderWeb`, übersprungen mit `-PskipReaderWeb`) und über `WebViewAssetLoader` unter `https://appassets.androidplatform.net/reader/index.html` geladen.

**Transport.** Kotlin → JS: `webView.evaluateJavascript("EbookReaderHost.receive(" + json + ")", null)` mit einer `HostToReader`-Nachricht. JS → Kotlin: `AndroidBridge.postMessage(jsonString)` (ein `@JavascriptInterface`-Objekt, Aufruf auf einem Binder-Thread) mit einer `ReaderToHost`-Nachricht. Diskriminator ist die Eigenschaft `type`. Kotlin sendet erst nach `ready`.

| Richtung | `type` | Inhalt |
|---|---|---|
| Kotlin → JS | `open` | `book` (`accountId`, `fileId`, `format`, `title`), `source`, `initialLocator` (Server-Locator oder `null`), `settings` (`ReaderSettings`) |
| Kotlin → JS | `goTo` | `locator` oder `href` (Inhaltsverzeichnis) |
| Kotlin → JS | `next` / `prev` | – |
| Kotlin → JS | `setSettings` | `ReaderSettings` (live: Theme, Schrift, Layout, E-Ink) |
| Kotlin → JS | `destroy` | Reader abbauen |
| JS → Kotlin | `ready` | `protocol: 1` – `EbookReaderHost` ist verfügbar |
| JS → Kotlin | `opened` | `info` (`title`, `authors`, `language`, `isComic`, `fixedLayout`, `rtl`, `pageCount`) |
| JS → Kotlin | `relocate` | `locator` (Server-Format), `percentage` 0..1, optional `label`, `page{current,total}` |
| JS → Kotlin | `toc` | `items[{label, href, subitems}]` |
| JS → Kotlin | `externalLink` | `url` – Kotlin fragt nach und startet `ACTION_VIEW` (nur http/https/mailto) |
| JS → Kotlin | `tap` | `zone` = `left|center|right` |
| JS → Kotlin | `error` | `code` (`open-failed`, `unsupported-format`, `network`, `unauthorized`, `reader`), `message` |

`open.source` (Kotlin entscheidet, JS baut daraus die `ReaderSource` von reader-core): `file` (ganze Datei von `/api/book` → `File`), `remote-zip` (EPUB/FBZ eintragsweise: `entriesUrl`, `itemUrl`), `remote-comic` (Seiten einzeln: `pagesUrl`, `pageUrl` mit `{index}`/`{width}`). Offline ist es immer `file`. Der **Locator** ist exakt das Server-Format (`href` + `locations{progression,totalProgression,position,cfi}`), damit Web und App dieselbe Position teilen.

**Request-Proxy.** Die Seite spricht nur `https://appassets.androidplatform.net/api/...`; `ReaderRequestProxy.intercept` (aus `shouldInterceptRequest`) bedient diese Pfade aus der lokalen Datei oder leitet sie mit Auth an den Server weiter (`/api/book` → WebDAV mit `Range`, `/api/archive/entries`, `/api/item?id=`, `/api/comic/pages`, `/api/comic/page/{i}?w=`; Tabelle im KDoc). Der Proxy ist per `bind(accountId, fileId, online)` an **ein** Buch gebunden; fremde Hosts und unbekannte Pfade werden mit 403/404 beantwortet. Fehler: Netzwerk → 504 + `X-Reader-Error: network`, 401 → 401 + `X-Reader-Error: unauthorized`. JavaScript sieht nie Zugangsdaten.

**WebView-Härtung** (Pflicht, PLAN.md Abschnitt 5): `allowFileAccess=false`, `allowContentAccess=false`, kein Mixed Content, Navigation weg von `appassets` blockieren, externe Links nur nach Bestätigung, Safe Browsing an, `@JavascriptInterface` nur auf dem Bridge-Objekt (ProGuard-Regel vorhanden).

## 7. UI (`ui/`)

Navigationsgraph und Routen: `ui/navigation` (`Routes`, `AppNavHost`). Platzhalter-Screens in `ui/screens/Screens.kt` sind der **Navigationsvertrag** (Signaturen der Composables); W-UI ersetzt jeden durch eine eigene Datei samt ViewModel. Theme (`EbookReaderTheme`: dynamische Farben ab Android 12, feste Palette davor, hell/dunkel) bleibt in `ui/theme`. Wiederverwendbare Komponenten nach `ui/components`.

## 8. Dateibesitz der drei nächsten Worker

| Pfad | Besitzer | Hinweis |
|---|---|---|
| `app/src/main/java/.../data/api/**` (Implementierungen `EbookApiImpl`, `LoginFlowClientImpl`, `ApiClientFactoryImpl`) | **W-DATA** | Interfaces/DTOs in `EbookApi.kt`, `Dtos.kt`, `ApiException.kt` sind Vertrag |
| `.../data/db/**` | **W-DATA** | Schemaänderungen nur mit Migration + `schemas/`-Update |
| `.../data/repo/**`, `.../data/sync/**`, `.../data/download/**`, `.../data/account/**` | **W-DATA** | Implementierungen + Unit-Tests (MockWebServer, Room in-memory) in `app/src/test` |
| `.../AppContainer.kt`, `App.kt` | **W-DATA** | trägt die echten Implementierungen ein (`notYet(...)` ersetzen); W-UI/W-READER dürfen nur Zeilen für ihre eigenen Objekte ergänzen |
| `.../ui/**`, `MainActivity.kt`, `res/values*/strings.xml`, `res/drawable`, `res/mipmap-*` | **W-UI** | Screens, ViewModels, Komponenten, Theme, Strings (de + en) |
| `reader-web/**` | **W-READER** | Bundle und Bridge-Seite (JS), `package.json`, `vite.config.ts` |
| `.../reader/**` (Kotlin: `ReaderHostImpl`, `ReaderView`, `ReaderRequestProxyImpl`) | **W-READER** | `ReaderBridge.kt` ist Vertrag (Protokoll-Änderungen nur gemeinsam mit `reader-web/src/main.ts`) |
| `ui/screens/ReaderScreen.kt` | **W-UI** (bindet `ReaderView` von W-READER ein) | bis dahin Platzhalter |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | geteilt | nur Abhängigkeiten ergänzen, nie Versionen anderer still ändern |
| `docs/**`, `README.md`, `CHANGELOG.md`, `.github/**`, `third_party/**` | Orchestrator | Worker liefern Texte für `CHANGELOG.md` im Abschnitt `## [Unreleased]` |

**Parallelität:** Jeder Worker arbeitet in eigenen Verzeichnissen; gemeinsame Dateien (`AppContainer.kt`, `strings.xml`, `libs.versions.toml`) nur mit kleinen, additiven Änderungen. Alles muss mit `./gradlew.bat assembleDebug test lint` grün bleiben (`-PskipReaderWeb` für schnelle Läufe ohne npm).

## 9. Qualitätsregeln

- Jede Änderung bringt Tests (JUnit; Compose-/Robolectric-Tests für UI; MockWebServer für den API-Client; Room mit `inMemoryDatabaseBuilder`).
- Keine Drittanbieter-SDKs mit Datenerhebung; kein Logging von Zugangsdaten, Locator-Inhalten oder Buchtiteln in Release-Builds.
- Nur HTTPS (`usesCleartextTraffic=false`, Network-Security-Config); Benutzer-CAs sind erlaubt.
- Release-Version aus Gradle-Properties `appVersionCode` / `appVersionName`; Signierung nur über die Umgebungsvariablen `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
