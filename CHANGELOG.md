# Changelog

Alle nennenswerten Änderungen an dieser App werden hier dokumentiert.
Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

### Hinzugefügt
- Projektgrundgerüst (Phase 0): Gradle mit Version Catalog, Kotlin, Jetpack Compose mit Material 3 (dynamische Farben ab Android 12), Navigation mit Platzhalter-Bildschirmen (Konten, Bibliothek, Buchdetails, Reader, Downloads, Einstellungen).
- Technische Verträge (`docs/CONTRACTS.md`) mit Kotlin-Schnittstellen für API-Client, Konto-Speicher, Room-Datenbank, Repositories, Sync, Downloads und Reader-Bridge.
- Build-Pipeline für den Reader (`reader-web`, Vite) auf Basis von `reader-core` aus dem Server-Repo (Git-Submodul).
- CI mit Lint, Tests und Debug-Build.
- Oberfläche (Phase 1, W-UI): Material-3-Bildschirme mit ViewModels für Konten (Login Flow v2, Hinzufügen, Neu anmelden, Entfernen mit Widerruf des App-Passworts), Bibliothek (Konto-Umschalter bzw. alle Konten, Raster/Liste, Suche, Filter, Sortierung, Pull-to-Refresh, Regale und Serien, Offline-Anzeige, Fehlerbanner), Buchdetails mit minimaler Bearbeitung (Titel, Autoren, Serie, Genres, Tags, Bewertung, Lesestatus), Regal-/Serien-Details mit „Offline verfügbar“, Downloads/Speicher, Einstellungen und Reader-Bildschirm (Konflikt-Dialog „Neuere Position von …“, Inhaltsverzeichnis, Reader-Einstellungen, Bestätigung externer Links, Bildschirm anlassen, Systemleisten ausgeblendet).
- Texte auf Deutsch und Englisch, helles/dunkles Design mit dynamischen Farben, E-Ink-Modus (hoher Kontrast, keine Animationen), Zwei-Spalten-Layout auf Tablets, Benachrichtigungs-Berechtigung (Android 13+) vor dem ersten Download, Deep Link `ebookreader://downloads` für Benachrichtigungen.
- UI-, ViewModel- und Robolectric-Compose-Tests mit Fake-Repositories.
