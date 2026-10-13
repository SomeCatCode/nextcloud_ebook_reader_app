# Changelog

Alle nennenswerten Änderungen an dieser App werden hier dokumentiert.
Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

### Behoben
- „Weiterlesen“ zeigt jetzt alle Bücher mit dem Lesestatus „Wird gelesen“ (zuletzt gelesene zuerst), auch ohne Lesefortschritt – dieselbe Auswahl wie der Statusfilter und die Web-App ab Server 0.11.1.

## 0.5.0 – 2026-10-13

### Hinzugefügt
- **Lokale Bibliotheken: Bücher löschen.** In den Buchdetails über „⋮“ → „Buch löschen“ (mit Rückfrage). Die Datei wird aus dem Ordner auf dem Gerät entfernt, Lesestand und Markierungen ebenso. Bei Nextcloud-Konten gibt es den Eintrag nicht.
- **Speicherplatz-Prüfung beim Kopieren/Verschieben auf das Gerät:** Vor dem Download prüft die App, ob im Zielordner genug Platz für das Buch plus 50 MB Reserve frei ist. Sonst bricht sie ab, bevor etwas geladen oder gelöscht wird, und nennt benötigten und freien Platz.
- **Vier neue Sprachen:** Die App ist jetzt auch auf Französisch, Arabisch, Türkisch und Dari verfügbar (zusätzlich zu Deutsch, Englisch, Spanisch und Japanisch). Arabisch und Dari werden von rechts nach links dargestellt; die Lesegesten im Reader bleiben an den Bildschirmseiten ausgerichtet.
- **Sprache einstellbar:** Einstellungen → „Sprache“ mit „Systemstandard“ und allen acht Sprachen, jede in ihrem eigenen Namen (Deutsch, English, Español, Français, 日本語, العربية, Türkçe, دری). Ohne Auswahl folgt die App der Systemsprache (nicht unterstützte Sprachen zeigen Englisch). Die Sprache erscheint auch in den Android-Systemeinstellungen unter „App-Sprachen“. Technisch über die AppCompat-Per-App-Sprache (MainActivity ist jetzt eine `AppCompatActivity`).
- Datenschutzerklärung und Play-Store-Texte gibt es zusätzlich auf Französisch, Arabisch, Türkisch und Dari (`docs/privacy/`, `fastlane/metadata/android/`).
- Test, der prüft, dass alle Übersetzungen exakt dieselben String-Namen und Platzhalter wie die englischen Texte haben.

### Geändert
- Einstellungen: Der Eintrag „Datenschutzerklärung“ zeigt nur noch den Titel und öffnet die Erklärung direkt in der App (wie „Lizenzen von Drittanbietern“), in der App-Sprache (Deutsch, Englisch, Spanisch, Japanisch; sonst Englisch). Quelle sind die Texte unter `docs/privacy/`, der Build kopiert sie in die App.

### Behoben
- **Kopieren/Verschieben zwischen Konten:** Brach der Download vom Nextcloud-Server unterwegs ab, entstand im lokalen Ordner eine abgeschnittene Datei ohne Fehlermeldung (z. B. eine CBZ mit 51 statt 147 MB: kein Cover, ließ sich nicht öffnen). Die App setzt einen abgebrochenen Download jetzt an der richtigen Stelle fort (HTTP-Range) und prüft jede Kopie: Bei lokalen Zielen müssen Dateigröße und SHA-256-Prüfsumme mit dem Original übereinstimmen, bei Nextcloud-Zielen die Dateigröße auf dem Server. Stimmt etwas nicht, wird die Kopie gelöscht, das Original bleibt erhalten (auch beim Verschieben) und die App meldet einen Fehler.
- „Weiterlesen“ zeigt nur noch begonnene Bücher mit dem Lesestatus „Wird gelesen“ und einem Fortschritt über 0 %; Bücher mit „Ungelesen“ erscheinen dort nicht mehr (wie in der Web-App).

## 0.4.0 – 2026-10-12

### Hinzugefügt
- **Lokale Bibliotheken:** Die App funktioniert jetzt auch ohne Server. Unter „Konto hinzufügen“ → „Ordner auf diesem Gerät“ wird ein Ordner (inkl. Unterordnern, auch auf der SD-Karte) zur eigenen Bibliothek; jeder Ordner ist ein eigenes Konto. Titel, Autoren, Serie, Beschreibung, Sprache, Verlag, ISBN, Genres und Cover werden aus EPUB, FB2/FBZ und CBZ gelesen, sonst gilt der Dateiname. Lesestand, Markierungen, Bewertung und Bearbeitungen bleiben auf dem Gerät. „Neu einlesen“ (bzw. Synchronisieren) nimmt neue und geänderte Dateien auf und entfernt gelöschte. Keine Speicherberechtigung nötig (Android-Ordnerauswahl). Beim Entfernen bleiben die Dateien im Ordner erhalten.
- **Kopieren und Verschieben zwischen Konten:** In den Buchdetails über „⋮“ → „In anderes Konto kopieren/verschieben“, in jede Richtung (lokal ↔ Nextcloud, Nextcloud ↔ Nextcloud). Bei Nextcloud-Zielen wählbarer Bibliotheksordner, belegte Dateinamen bekommen „(2)“. Lesestand, Status, Bewertung und Markierungen ziehen mit. Verschieben löscht das Original erst nach erfolgreicher Kopie (Nextcloud: in den Papierkorb); Bücher, die andere mit dir teilen, lassen sich nur kopieren.
- Datenbank Version 4 (Kontoart und Dateiverweis lokaler Bücher), bestehende Konten bleiben unverändert.
- **Anleitung beim ersten Start:** kurze Einführung in fünf Seiten (Willkommen, Bibliothek einrichten mit Ordner oder Nextcloud, Bücher finden, Lesen und Gesten, Offline/Kopieren/Verschieben), am Ende direkt „Bibliothek hinzufügen“. Erscheint einmal (auch nach dem Update) und ist unter Einstellungen → „Anleitung“ jederzeit wieder aufrufbar.

### Geändert
- Beschreibungen (Play Store, README, Datenschutzerklärungen) nennen die App nicht mehr nur als Nextcloud-Begleiter: Sie läuft auch eigenständig mit lokalen Ordnern.

### Behoben
- Anmeldung an Nextcloud musste manchmal zweimal gemacht werden: Während man im Browser bestätigte, fragte die App im Hintergrund weiter nach; auf Geräten, die Hintergrund-Apps das Netz sperren (z. B. Samsung), brach die Anmeldung nach wenigen Sekunden mit einem Netzwerkfehler ab. Jetzt pausiert die Abfrage im Hintergrund und läuft beim Zurückkehren sofort weiter.
- Textfelder (Suche, Serveradresse, Gerätename, „Buch bearbeiten“) ließen den Cursor beim Tippen nach vorne springen, weil der Wert verzögert aus dem Zustand zurückkam. Die Felder halten den Text jetzt lokal.

## 0.3.0 – 2026-10-11

### Hinzugefügt
- **Gesten im Reader:** Nach oben wischen öffnet das Menü mit Fortschrittsleiste, nach unten wischen schließt es bzw. schließt das Buch (die Position wird vorher gespeichert). Links/rechts tippen oder wischen blättert wie bisher.
- **Fortschrittsregler:** Die Fortschrittsleiste im Reader-Menü lässt sich ziehen, um schnell im Buch zu springen (Vorschau der Zielposition in Prozent, bei Comics als Seitenzahl).
- **Gesten-Erklärung** beim ersten Öffnen eines Buchs; später jederzeit über die Reader-Einstellungen („Gesten anzeigen“).
- **Neue Reiter „Geteilt“ und „Ordner“** (Reihenfolge wie in der Web-App: Bücher, Serien, Geteilt, Ordner, Regale; benötigt Server-App 0.10.0, auf älteren Servern bleiben sie ausgeblendet). „Geteilt“ zeigt die Bücher als Raster oder Liste, umschaltbar zwischen „Alle“, „Mit mir geteilt“ und „Von mir geteilt“. „Ordner“ durchsucht die Ordnerstruktur der Bibliothek (Pfadleiste, Hoch-Taste und Zurück-Geste, Unterordner mit Anzahl, optional inklusive Unterordnern). Beides wird lokal aus der Datenbank berechnet und funktioniert deshalb auch offline.
- **Teilen-Symbol** auf Buchcovern, Listenzeilen und Serien: „Von dir geteilt“ bzw. „Mit dir geteilt von …“ (mit Inhaltsbeschreibung für Screenreader).
- Datenbank (Version 3): neue Buchfelder `shared`, `owner` und `sharedOut`; beim Update startet automatisch ein vollständiger Abgleich, der sie füllt.

### Behoben
- Beim Drehen des Geräts sprang der Reader an die Position zurück, an der das Buch geöffnet wurde, und speicherte diese als Lesestand. Jetzt bleibt die aktuelle Seite erhalten (auch geänderte Reader-Einstellungen).

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
