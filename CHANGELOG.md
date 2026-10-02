# Changelog

Alle nennenswerten Änderungen an dieser App werden hier dokumentiert.
Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

### Hinzugefügt
- Projektgrundgerüst (Phase 0): Gradle mit Version Catalog, Kotlin, Jetpack Compose mit Material 3 (dynamische Farben ab Android 12), Navigation mit Platzhalter-Bildschirmen (Konten, Bibliothek, Buchdetails, Reader, Downloads, Einstellungen).
- Technische Verträge (`docs/CONTRACTS.md`) mit Kotlin-Schnittstellen für API-Client, Konto-Speicher, Room-Datenbank, Repositories, Sync, Downloads und Reader-Bridge.
- Build-Pipeline für den Reader (`reader-web`, Vite) auf Basis von `reader-core` aus dem Server-Repo (Git-Submodul).
- CI mit Lint, Tests und Debug-Build.
