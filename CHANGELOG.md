# Changelog

Alle nennenswerten Änderungen an dieser App werden hier dokumentiert.
Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

### Hinzugefügt
- **Neue Reiter „Geteilt“ und „Ordner“** (Reihenfolge wie in der Web-App: Bücher, Serien, Geteilt, Ordner, Regale; benötigt Server-App 0.10.0, auf älteren Servern bleiben sie ausgeblendet). „Geteilt“ zeigt die Bücher als Raster oder Liste, umschaltbar zwischen „Alle“, „Mit mir geteilt“ und „Von mir geteilt“. „Ordner“ durchsucht die Ordnerstruktur der Bibliothek (Pfadleiste, Hoch-Taste und Zurück-Geste, Unterordner mit Anzahl, optional inklusive Unterordnern). Beides wird lokal aus der Datenbank berechnet und funktioniert deshalb auch offline.
- **Teilen-Symbol** auf Buchcovern, Listenzeilen und Serien: „Von dir geteilt“ bzw. „Mit dir geteilt von …“ (mit Inhaltsbeschreibung für Screenreader).
- Datenbank (Version 3): neue Buchfelder `shared`, `owner` und `sharedOut`; beim Update startet automatisch ein vollständiger Abgleich, der sie füllt.

### Geändert
- Der Reiter „Serien“ zeigt weiterhin direkt alle Serien (kein Filter); die Tab-Leiste ist jetzt scrollbar.

## 0.2.1 – 2026-10-07

### Hinzugefügt
- Store-Grafiken für Google Play: App-Symbol (512 × 512) und Vorstellungsgrafik (1024 × 500) unter `fastlane/metadata/android/de-DE/images/`, erzeugt mit `scripts/store_graphics.py`.

### Geändert
- Play-Console-Leitfaden nennt keine echte Serveradresse und keinen echten Prüfer-Benutzer mehr (Platzhalter); `.gitignore` schließt Signaturschlüssel, Play-Service-Account-Dateien und `.env` aus.

## 0.2.0 – 2026-10-07

### Hinzugefügt
- Markierungen und Notizen in EPUB- und anderen Textbüchern, synchron mit der Web-App: Text auswählen, im Auswahlmenü „Markieren“ (in der zuletzt gewählten Farbe, danach Farbwahl Gelb/Grün/Blau/Pink/Lila wie im Web) oder „Notiz“ wählen; Antippen einer Markierung öffnet Farbe, Notiz und Löschen. Liste „Markierungen & Notizen“ im Reader (gruppiert nach Markierungen, Notizen und Lesezeichen, mit Kapitel und Position) zum Springen, Notiz bearbeiten und Löschen; im E-Ink-Modus als ruhige Vollbildseite ohne Animation. Comics und Fixed-Layout-Bücher haben keine Textauswahl; dort zeigt die Liste nur Lesezeichen aus der Web-App.
- Offline zuerst: Änderungen landen sofort in der Datenbank und werden beim nächsten Upload bzw. Sync hochgeladen (Upsert per UUID, Löschen als Tombstone); der Delta-Sync übernimmt Markierungen anderer Geräte samt Löschungen, bei Konflikten gewinnt wie auf dem Server der neuere `clientUpdatedAt`. Beim Öffnen eines Buchs werden seine Markierungen zusätzlich direkt vom Server aktualisiert.
- Datenbank Version 2 (Tabelle `annotation`) mit Migration; die Migration setzt den Sync-Cursor zurück, damit bereits vorhandene Markierungen beim ersten Sync ankommen (einmaliger Vollsync).
- Einstellungen: optionaler Eintrag „App unterstützen“ mit Spendenlink zu Ko-fi (https://ko-fi.com/somecatcode; nur in der APK von GitHub; im Google-Play-Build nie enthalten, da Play externe Spendenlinks von Privatentwicklern nicht erlaubt) und Seite „Lizenzen von Drittanbietern“.
- Server-Version: Die App liest bei jeder Synchronisation die Nextcloud-Version und die Version der Server-App „E-Book Reader“ (ab Server 0.8.0) und zeigt beide in den Konten an. Ist die Server-App älter als empfohlen (0.8.0), erscheint ein Hinweis in den Konten und in der Bibliothek; Funktionen lassen sich an Mindestversionen knüpfen (`ServerFeature`). Fehlt die Server-App ganz, bricht die Synchronisation mit „App nicht verfügbar“ ab.
- Regal- und Serienansicht: Umschalter zwischen Raster und Liste (gemeinsame Einstellung mit der Bibliothek) und Lesestand bei jedem Buch („Ungelesen“, Fortschritt in Prozent mit Balken, „Gelesen“ mit Häkchen).
- Sprachen: Spanisch und Japanisch (neben Deutsch und Englisch) für alle Texte der App inklusive Benachrichtigungen; Sprachauswahl pro App ab Android 13 (Systemeinstellungen → Apps → E-Book Reader → Sprache) über `locales_config.xml`.
- Zoom im Comic-Reader: mit zwei Fingern aufziehen (bis 5-fach, am Fingerpunkt verankert), vergrößerte Seite mit einem Finger verschieben, Doppeltippen wechselt zwischen eingepasst und 2,5-fach an der getippten Stelle. Vergrößert blättern weder Wischen noch Tipp-Zonen (Tippen blendet nur die Leisten ein/aus); Umblättern per Lautstärketaste, Tastatur, Inhaltsverzeichnis oder Tipp-Zone (eingepasst) setzt den Zoom zurück. Der Zoom ist rein visuell und landet nicht im gespeicherten Lesestand; im E-Ink-Modus ohne Animation.
- Reader-Einstellungen für Comics: „Ganze Seite“ oder „Seitenbreite“ einpassen (lange Seiten lassen sich bei Seitenbreite vertikal scrollen).
- Filter wie in der Web-App: Filterblatt mit Genres und Tags als Baum (Unterebenen mit „X/*“), Autoren, Serien, Formaten und „Braucht Pflege“ (fehlende Angaben); jeder Eintrag lässt sich einschließen oder ausschließen, aktive Filter als Chips (antippen wechselt ein/aus, × entfernt), „Alle/Eins muss passen“, Option „Gelesene ausblenden“ (Standard an, bleibt gespeichert).
- Smarte Regale: aktuellen Filter als smartes Regal speichern, Filter eines smarten Regals bearbeiten und zurückspeichern; smarte Regale verstehen jetzt auch Fehlend-Filter und Regal-Begriffe wie der Server.
- Regalverwaltung: Regale anlegen, umbenennen, sortieren und löschen; Bücher in den Buchdetails zu Regalen hinzufügen (auch neues Regal) und im Regal per langem Tippen entfernen; manuelle Regale zeigen die Reihenfolge des Servers.
- Buchdetails: Autor, Serie, Genres, Tags und Regale antippen filtert die Bibliothek danach.
- Synchronisation sichtbar: „Jetzt synchronisieren“ mit Zeitpunkt der letzten Synchronisation im Menü, Fortschrittsbalken während des Syncs.
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

### Geändert
- Die App ist nicht mehr quelloffen: Lizenz proprietär (alle Rechte vorbehalten) statt AGPL-3.0-or-later; Quellcode- und AGPL-Links aus den Einstellungen entfernt. Komponenten Dritter behalten ihre Lizenzen.
- Lesestatus und Lesefortschritt hängen zusammen: „Gelesen“ setzt den Fortschritt auf 100 %, „Ungelesen“ setzt ihn zurück; Lesen bis zum Ende markiert das Buch als gelesen, zurück an den Anfang als ungelesen.
- Sortierung nach Bewertung, Hinzugefügt und Zuletzt gelesen beginnt wie im Web absteigend.

### Behoben
- Regale zeigten keine Bücher: Die App hängte `format=json` an alle Anfragen an die E-Book-Reader-API an, und `GET /books` las das als Filter „nur Bücher im Format json“. Jetzt wählt nur der `Accept`-Header das Antwortformat, wie in der Web-App.
- Regale zeigten keine Bücher: die Mitglieder manueller Regale werden jetzt bei jeder Synchronisation und beim Öffnen eines Regals neu geladen (vorher wurden Änderungen übersprungen, wenn sich die Regalzeile auf dem Server nicht änderte).
