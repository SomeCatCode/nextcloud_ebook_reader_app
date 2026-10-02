# Changelog

Alle nennenswerten Änderungen an dieser App werden hier dokumentiert.
Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

### Hinzugefügt
- Projektgrundgerüst (Phase 0): Gradle mit Version Catalog, Kotlin, Jetpack Compose mit Material 3 (dynamische Farben ab Android 12), Navigation mit Platzhalter-Bildschirmen (Konten, Bibliothek, Buchdetails, Reader, Downloads, Einstellungen).
- Technische Verträge (`docs/CONTRACTS.md`) mit Kotlin-Schnittstellen für API-Client, Konto-Speicher, Room-Datenbank, Repositories, Sync, Downloads und Reader-Bridge.
- Build-Pipeline für den Reader (`reader-web`, Vite) auf Basis von `reader-core` aus dem Server-Repo (Git-Submodul).
- CI mit Lint, Tests und Debug-Build.
- Reader (Phase 4, Teil W-READER): Vollbild-Reader-Seite mit der vollständigen Bridge (`open`, `goTo`, `next`, `prev`, `setSettings`, `destroy` sowie `ready`, `opened`, `relocate`, `toc`, `externalLink`, `tap`, `error`), Quellen `file`, `remote-zip` und `remote-comic` mit Rückfall auf die ganze Datei, Themes (hell, Sepia, dunkel), Schrift, Zeilenabstand, Ränder, Blättern/Scrollen, Comic-Doppelseite und Leserichtung, E-Ink-Modus (reines Schwarz/Weiß, keine Animationen), Tipp-Zonen, Wischen und Tastatur. Mindestanforderung: WebView ab Chrome 103.
- Request-Proxy (`ReaderRequestProxyImpl`): bedient `/api/…` offline aus der lokalen Datei (Range, MIME-Typ) oder online über ein kleines `ReaderBackend`-Interface mit Server-Authentifizierung; Fehlerabbildung (Netzwerk → 504, 401 → `unauthorized`, fremde Hosts → 403, unbekannte Pfade → 404).
- `ReaderHostImpl` und `ReaderView`: gehärtete WebView (kein Datei-/Content-Zugriff, kein Mixed Content, Navigation weg vom Reader blockiert, Safe Browsing, Content-Security-Policy), Bridge-Nachrichten mit Thread-Wechsel, Lebenszyklus, optionale Lautstärketasten zum Blättern. Debug-Build enthält eine Test-Activity für den Reader.
