# Abweichungen und Zusatzentscheidungen (W-DATA)

Die Schnittstellen aus `docs/CONTRACTS.md` (`EbookApi`, `AccountStore`, Repositories, `SyncEngine`, `DownloadManager`) sind unverändert. Zusätzlich gilt:

## Erweiterungen (additiv)
- **Room-DAOs** (`data/db/Daos.kt`): zusätzliche Abfragen (Beobachten von Tags/Fortschritt/Pending-Edits je Konto, `DownloadItemRow`-Join für die Offline-Liste, Aufräumabfragen). Kein Schema-Änderung, kein Versionssprung, `schemas/1.json` unverändert.
- **`LoginFlowClient.awaitLogin`** (Erweiterungsfunktion in `data/api/LoginFlowClientImpl.kt`): Polling alle 2 s, Abbruch nach 20 Minuten bzw. durch Coroutine-Abbruch. Die UI kann sie statt einer eigenen Schleife nutzen.
- **`DownloadManagerImpl.cancelAccount`**, **`ApiClientFactoryImpl.forget`**: werden vom Container beim Entfernen eines Kontos aufgerufen (über den `beforeRemove`-Hook des `AccountStoreImpl`).
- **Neue Ressourcen** `res/values*/strings_data.xml` (Benachrichtigungstexte), damit `strings.xml` (W-UI) nicht angefasst wird.
- **Manifest**: Berechtigungen `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`; `SystemForegroundService` mit `foregroundServiceType="dataSync"`. `POST_NOTIFICATIONS` muss auf Android 13+ zur Laufzeit angefragt werden (UI), sonst erscheint die Benachrichtigung nicht (Downloads laufen trotzdem).
- **`app/build.gradle.kts` / `libs.versions.toml`**: Testabhängigkeit `androidx.work:work-testing`.
- **`App.onCreate`** startet `DefaultAppContainer.startBackgroundWork()` (periodischer Sync folgt Einstellungen und Kontenliste, unterbrochene Downloads werden fortgesetzt); unter Robolectric wird das übersprungen.

## Verhalten, das die Verträge offen lassen
- **Pins** (Buch/Regal/Serie) liegen in den `download`-Zeilen (`pinnedBy` + `pinRef`). Ein Regal/eine Serie ohne ladbares Mitglied zum Zeitpunkt des Anpinnens hinterlässt keine Zeile und wird nicht gemerkt. Pro Buch gibt es einen Pin; „Buch“ hat Vorrang vor „Regal“ und „Serie“.
- **`DownloadManager.cancel`** löscht die Zeile (Pins über Regal/Serie werden beim nächsten Sync neu eingereiht, der richtige Weg ist `removeOffline`).
- **`DownloadManager.localFile`**: ist die Datei verschwunden, wird die Zeile gelöscht (statt erneut geladen).
- **`acceptRemote`** speichert `clientUpdatedAt = remoteUpdatedAt` (Serverzeit), weil `ProgressConflict` die Client-Zeit nicht trägt; dadurch gilt die übernommene Position nie wieder als „neuer auf dem Server“.
- **Sync** überspringt Bücher mit ausstehender Offline-Bearbeitung (sie werden nach dem Upload vom Server zurückgeliefert); nach einem Vollsync (leerer oder ungültiger Cursor) werden Bücher entfernt, die der Server nicht mehr listet.
- **Geändertes Buch auf dem Server** (`mtime`/`size`): eine fertige lokale Datei wird auf `QUEUED` zurückgesetzt und neu geladen; die alte Datei bleibt bis zum atomaren Ersetzen liegen, ist in der Zwischenzeit aber nicht als offline verfügbar markiert.
- **Fortsetzen von Downloads** nur mit starkem ETag der ersten Antwort; ohne ETag wird neu begonnen.
- **Gradle-Daemon:** Parallele Worker teilen sich `GRADLE_USER_HOME`; `gradlew --stop` eines Workers beendet die Daemons der anderen. Läufe mit `--no-daemon` sind davon unabhängig.
