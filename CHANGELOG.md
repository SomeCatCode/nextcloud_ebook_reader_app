# Changelog

Alle nennenswerten Änderungen an dieser App werden hier dokumentiert.
Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

### Hinzugefügt
- Datenschicht (Phase 1/2/3): API-Client für die Nextcloud-App (OCS-Hülle, Basic-Auth mit App-Passwort, Fehlerabbildung, Fortschritts-Konflikt 409, Sync-Seiten), Login Flow v2, Versionsprüfung (Server-App fehlt oder älter als 0.5.0) und Widerruf des App-Passworts beim Abmelden.
- Konten: App-Passwörter AES-GCM-verschlüsselt (Android Keystore) in `noBackupFilesDir`; Entfernen eines Kontos löscht Datenbankzeilen und lokale Bücher.
- Bibliothek offline aus Room (Suche, Genres/Tags, Regale inkl. smarter Regale, Serien), Delta-Sync mit Cursor pro Seite in einer Transaktion (Löschmarker, ungültiger Cursor → Vollsync), periodische Synchronisation und manueller Sync über WorkManager.
- Lesefortschritt lokal zuerst mit Konfliktregel („neuere Position von …“), Offline-Bearbeitung von Metadaten, Bewertung und Lesestatus mit Warteschlange und Zusammenführung.
- Downloads über WebDAV mit Fortsetzen (`Range`/`If-Range`), `.part`-Datei, atomarem Umbenennen, höchstens zwei parallelen Downloads, Fortschrittsbenachrichtigung bei großen Dateien und automatischem Nachladen neuer Bände angepinnter Regale und Serien.
- Projektgrundgerüst (Phase 0): Gradle mit Version Catalog, Kotlin, Jetpack Compose mit Material 3 (dynamische Farben ab Android 12), Navigation mit Platzhalter-Bildschirmen (Konten, Bibliothek, Buchdetails, Reader, Downloads, Einstellungen).
- Technische Verträge (`docs/CONTRACTS.md`) mit Kotlin-Schnittstellen für API-Client, Konto-Speicher, Room-Datenbank, Repositories, Sync, Downloads und Reader-Bridge.
- Build-Pipeline für den Reader (`reader-web`, Vite) auf Basis von `reader-core` aus dem Server-Repo (Git-Submodul).
- CI mit Lint, Tests und Debug-Build.
