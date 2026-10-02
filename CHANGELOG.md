# Changelog

Alle nennenswerten Änderungen an dieser App werden hier dokumentiert.
Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

### Hinzugefügt
- Release-Workflow: Tag `vX.Y.Z` baut signiertes AAB und APK, erstellt ein GitHub-Release mit APK und lädt das AAB samt R8-Mapping und „Neuerungen“ in den internen Test von Google Play (Anleitung: `docs/RELEASING.md`).
- Datenschicht (Phase 1/2/3): API-Client für die Nextcloud-App (OCS-Hülle, Basic-Auth mit App-Passwort, Fehlerabbildung, Fortschritts-Konflikt 409, Sync-Seiten), Login Flow v2, Versionsprüfung (Server-App fehlt oder älter als 0.5.0) und Widerruf des App-Passworts beim Abmelden.
- Konten: App-Passwörter AES-GCM-verschlüsselt (Android Keystore) in `noBackupFilesDir`; Entfernen eines Kontos löscht Datenbankzeilen und lokale Bücher.
- Bibliothek offline aus Room (Suche, Genres/Tags, Regale inkl. smarter Regale, Serien), Delta-Sync mit Cursor pro Seite in einer Transaktion (Löschmarker, ungültiger Cursor → Vollsync), periodische Synchronisation und manueller Sync über WorkManager.
- Lesefortschritt lokal zuerst mit Konfliktregel („neuere Position von …“), Offline-Bearbeitung von Metadaten, Bewertung und Lesestatus mit Warteschlange und Zusammenführung.
- Downloads über WebDAV mit Fortsetzen (`Range`/`If-Range`), `.part`-Datei, atomarem Umbenennen, höchstens zwei parallelen Downloads, Fortschrittsbenachrichtigung bei großen Dateien und automatischem Nachladen neuer Bände angepinnter Regale und Serien.
- Projektgrundgerüst (Phase 0): Gradle mit Version Catalog, Kotlin, Jetpack Compose mit Material 3 (dynamische Farben ab Android 12), Navigation mit Platzhalter-Bildschirmen (Konten, Bibliothek, Buchdetails, Reader, Downloads, Einstellungen).
- Technische Verträge (`docs/CONTRACTS.md`) mit Kotlin-Schnittstellen für API-Client, Konto-Speicher, Room-Datenbank, Repositories, Sync, Downloads und Reader-Bridge.
- Build-Pipeline für den Reader (`reader-web`, Vite) auf Basis von `reader-core` aus dem Server-Repo (Git-Submodul).
- CI mit Lint, Tests und Debug-Build.
- Oberfläche (Phase 1, W-UI): Material-3-Bildschirme mit ViewModels für Konten (Login Flow v2, Hinzufügen, Neu anmelden, Entfernen mit Widerruf des App-Passworts), Bibliothek (Konto-Umschalter bzw. alle Konten, Raster/Liste, Suche, Filter, Sortierung, Pull-to-Refresh, Regale und Serien, Offline-Anzeige, Fehlerbanner), Buchdetails mit minimaler Bearbeitung (Titel, Autoren, Serie, Genres, Tags, Bewertung, Lesestatus), Regal-/Serien-Details mit „Offline verfügbar“, Downloads/Speicher, Einstellungen und Reader-Bildschirm (Konflikt-Dialog „Neuere Position von …“, Inhaltsverzeichnis, Reader-Einstellungen, Bestätigung externer Links, Bildschirm anlassen, Systemleisten ausgeblendet).
- Texte auf Deutsch und Englisch, helles/dunkles Design mit dynamischen Farben, E-Ink-Modus (hoher Kontrast, keine Animationen), Zwei-Spalten-Layout auf Tablets, Benachrichtigungs-Berechtigung (Android 13+) vor dem ersten Download, Deep Link `ebookreader://downloads` für Benachrichtigungen.
- UI-, ViewModel- und Robolectric-Compose-Tests mit Fake-Repositories.
- Reader (Phase 4, Teil W-READER): Vollbild-Reader-Seite mit der vollständigen Bridge (`open`, `goTo`, `next`, `prev`, `setSettings`, `destroy` sowie `ready`, `opened`, `relocate`, `toc`, `externalLink`, `tap`, `error`), Quellen `file`, `remote-zip` und `remote-comic` mit Rückfall auf die ganze Datei, Themes (hell, Sepia, dunkel), Schrift, Zeilenabstand, Ränder, Blättern/Scrollen, Comic-Doppelseite und Leserichtung, E-Ink-Modus (reines Schwarz/Weiß, keine Animationen), Tipp-Zonen, Wischen und Tastatur. Mindestanforderung: WebView ab Chrome 103.
- Request-Proxy (`ReaderRequestProxyImpl`): bedient `/api/…` offline aus der lokalen Datei (Range, MIME-Typ) oder online über ein kleines `ReaderBackend`-Interface mit Server-Authentifizierung; Fehlerabbildung (Netzwerk → 504, 401 → `unauthorized`, fremde Hosts → 403, unbekannte Pfade → 404).
- `ReaderHostImpl` und `ReaderView`: gehärtete WebView (kein Datei-/Content-Zugriff, kein Mixed Content, Navigation weg vom Reader blockiert, Safe Browsing, Content-Security-Policy), Bridge-Nachrichten mit Thread-Wechsel, Lebenszyklus, optionale Lautstärketasten zum Blättern. Debug-Build enthält eine Test-Activity für den Reader.
