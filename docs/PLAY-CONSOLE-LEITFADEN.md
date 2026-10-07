# Leitfaden: Play Console – App-Inhalte und Store-Eintrag

Schritt für Schritt durch die Checkliste der Play Console, in genau deren Reihenfolge. Zu jedem Punkt findest du die Antworten zum Anklicken und die fertigen Texte zum Einfügen.

> Stand: 5. Oktober 2026, App-Version 0.2.0. Google ändert Formulare regelmäßig. Wo die Console anders fragt, gilt die Console. Das hier ist keine Rechtsberatung.

**Grundannahmen, auf denen alle Antworten beruhen. Prüfe sie vor dem Ausfüllen:**
- Die App ist ein **Client für einen Nextcloud-Server, den die Nutzerin oder der Nutzer selbst einträgt**. Du betreibst keinen Server und bekommst keine Daten.
- Es gibt **kein Werbe-, Analyse-, Absturzbericht- oder Tracking-SDK** in der App.
- Der **Play-Build hat keinen Spendenlink**. Er wird mit `-PplayBuild=true` gebaut. Google erlaubt Privatentwicklern keine externen Spendenlinks.
- Die App selbst bietet **kein Teilen und keinen Chat**. Teilen gibt es nur in der Web-App auf dem Server; in der App erscheinen geteilte Bücher nur. Sobald die App selbst teilen kann, müssen „Einstufung“ und „Datensicherheit“ neu geprüft werden.

Material zum Kopieren:
- Datenschutzerklärung: [Deutsch](privacy/datenschutz.md), [Englisch](privacy/privacy-policy.md), [Spanisch](privacy/politica-de-privacidad.md), [Japanisch](privacy/privacy-policy-ja.md)
- Store-Texte: `fastlane/metadata/android/` → [`de-DE`](../fastlane/metadata/android/de-DE/), [`en-US`](../fastlane/metadata/android/en-US/), [`es-ES`](../fastlane/metadata/android/es-ES/), [`ja-JP`](../fastlane/metadata/android/ja-JP/). Die App selbst gibt es in denselben vier Sprachen.

---

## A. Wichtige Angaben zu den Inhalten deiner App

### 1. Datenschutzerklärung festlegen

**Das Problem:** Die Console braucht eine **öffentlich erreichbare URL** ohne Anmeldung, und kein PDF. Das App-Repo ist privat. Ein Link auf GitHub funktioniert für Prüfer deshalb nicht, und der Link, der heute in der App steht, führt ins Leere.

**Lösung, eine Variante wählen:**

| Variante | URL | Aufwand |
|---|---|---|
| **A (empfohlen)** Seite auf deiner Website | z. B. `https://<deine-website>/ebook-reader/datenschutz` | Text aus `docs/privacy/datenschutz.md` als Seite anlegen, idealerweise mit Impressum daneben |
| B: im öffentlichen Server-Repo | `https://github.com/SomeCatCode/nextcloud_ebook_reader/blob/main/docs/PRIVACY-ANDROID.md` | Datei dorthin kopieren. Das geht schnell, sieht aber weniger professionell aus. |
| C: GitHub Pages des Server-Repos | `https://somecatcode.github.io/nextcloud_ebook_reader/privacy-android` | Pages aktivieren und die Datei unter `docs/` ablegen |

**Danach:**
1. Die URL in der Console eintragen.
2. Mir die URL nennen. Ich trage sie in der App ein: Einstellungen → „Datenschutzerklärung“, Konstante `PRIVACY_POLICY_URL`. Die App und die Console müssen auf dieselbe Seite zeigen.

> In der Datenschutzerklärung stehen ein Name und eine Kontaktadresse (Platzhalter `[NAME]`, `[ANSCHRIFT]`). Nach DSGVO muss der Verantwortliche erreichbar sein. Eine E-Mail-Adresse allein reicht oft nicht, eine ladungsfähige Anschrift ist der sichere Weg.

### 2. Anmeldedaten („App-Zugriff“)

**Auswahl:** „Alle oder einige Funktionen meiner App sind eingeschränkt“ → „Anleitung hinzufügen“.

> **Echte Werte nie ins Repository schreiben.** Serveradresse, Benutzername und Passwort des Prüfer-Zugangs trägst du nur in der Play Console ein; hier stehen Platzhalter.

**Zuerst einen Prüfer-Nutzer auf deiner Nextcloud anlegen.** Er braucht:
- einen eigenen, nicht naheliegenden Benutzernamen (im Folgenden `<PRÜFER-NUTZER>`) und ein langes, eigenes Passwort, **ohne Zwei-Faktor-Anmeldung und ohne Ablaufdatum**. Google prüft auch spätere Updates.
- keine Admin-Rechte, wenig Speicherplatz, keine Gruppen mit deinen Freigaben
- die App „E-Book Reader“ ab 0.7.0 aktiviert, Bücherordner `/Books`
- **nur gemeinfreie Inhalte**, z. B. 3–5 EPUBs von Standard Ebooks oder Project Gutenberg und einen gemeinfreien Comic. **Keine Inhalte aus deiner Bibliothek und nichts mit Altersfreigabe über 12.** Sonst droht eine falsche Einstufung oder eine Ablehnung.
- einmal im Browser anmelden und die Bibliothek einlesen lassen, damit der Prüfer sofort Bücher sieht

**Felder in der Console:**

- **Name der Anleitung:** `Anmeldung an der Test-Nextcloud`
- **Nutzername:** `<PRÜFER-NUTZER>`
- **Passwort:** *(das Passwort des Prüfer-Nutzers; trage es direkt in der Console ein und nirgends sonst)*
- **Weitere Informationen** (auf Englisch, die Prüfer arbeiten international):

```
This app is a reader for a self-hosted Nextcloud server. To test it:
1. Open the app and tap "Add account".
2. Enter the server address: <SERVER-ADRESSE>
3. A browser page of the Nextcloud server opens (Nextcloud Login Flow). Log in with the
   user name and password given above and tap "Grant access".
4. You return to the app automatically. The library shows public-domain e-books and a comic.
5. Tap a book and then "Read". Reading position is synced with the server.
The account only contains public-domain test content. No purchase or subscription exists.
```

### 3. Anzeigen

**„Nein, meine App enthält keine Anzeigen.“**

Der Spendenlink ist keine Anzeige. Er ist im Play-Build aber ohnehin nicht enthalten.

### 4. Einstufung des Inhalts (IARC-Fragebogen)

- **E-Mail-Adresse für die Einstufung:** `it@wasmitleder.de`
- **Kategorie:** „Referenz, Nachrichten oder Bildung“. Falls es nicht passt: „Alle anderen App-Typen“ bzw. „Dienstprogramm, Produktivität, Kommunikation oder Sonstiges“.

**Antworten:**

| Frage (sinngemäß) | Antwort | Begründung |
|---|---|---|
| Gewalt, Blut, sexuelle Inhalte, Nacktheit, vulgäre Sprache, Drogen, Glücksspiel **in der App** | **Nein** (jeweils) | Die App enthält keine eigenen Inhalte. Sie zeigt nur Dateien vom Server der Nutzerin oder des Nutzers, wie ein Datei-Viewer. |
| Können Nutzer miteinander interagieren oder Inhalte austauschen (Chat, Sprache, Bilder teilen)? | **Nein** | In der App gibt es weder Chat noch Teilen. |
| Teilt die App den Standort mit anderen Nutzern? | **Nein** | |
| Können Nutzer digitale Waren kaufen? | **Nein** | |
| Uneingeschränkter Internetzugang (eingebauter Browser)? | **Nein** | Die App verbindet sich nur mit dem eingetragenen Server. Links in Büchern öffnen sich nach Rückfrage im Systembrowser. |
| Ist die App ein Webbrowser oder eine Suchmaschine? | **Nein** | |

**Erwartetes Ergebnis:** USK „ab 0“ / PEGI 3 / „Jedes Alter“. Das ist korrekt: Bewertet wird die App, nicht die privaten Dateien, die jemand selbst hineinlädt.

### 5. Zielgruppe (und Inhalte)

- **Altersgruppen:** nur **„18 und älter“** ankreuzen. Alternativ zusätzlich „16–17“, aber **nichts unter 13**. Sonst gilt die Familienrichtlinie mit deutlich strengeren Pflichten.
- **„Könnte deine App Kinder unbeabsichtigt ansprechen?“** → **Nein.** Es ist ein Werkzeug für die eigene Cloud, ohne kindgerechte Gestaltung.
- **Store-Präsenz:** Nichts ankreuzen, was Kinder anspricht.

### 6. Datensicherheit

Die Daten fließen **nur zwischen dem Gerät und dem eigenen Server**. Google wertet jede Übertragung vom Gerät weg als „Erhebung“. Die **vorsichtige, ehrliche Angabe** unten ist deshalb die sichere Wahl, und die Datenschutzerklärung sagt genau dasselbe.

**Allgemeine Fragen:**

| Frage | Antwort |
|---|---|
| Erhebt oder teilt die App erforderliche Nutzerdaten? | **Ja** |
| Werden alle Nutzerdaten bei der Übertragung verschlüsselt? | **Ja** (die App spricht nur HTTPS) |
| Bietest du eine Möglichkeit, die Löschung der Daten zu beantragen? | **Ja**: In der App „Konto entfernen“ löscht alle lokalen Daten und widerruft das App-Passwort auf dem Server. Daten auf dem Server verwaltet die Nutzerin oder der Nutzer dort selbst. |
| Konten: Kann man in der App ein Konto erstellen? | **Nein**. Die App meldet sich nur bei einem bestehenden Nextcloud-Konto an. Damit entfällt die Pflicht zur Kontolöschungs-URL. |

**Datentypen.** Bei allen gilt dasselbe:
- **Erhoben: Ja**
- **Geteilt: Nein.** Der eigene Server ist keine Weitergabe an Dritte.
- **Verarbeitung nur vorübergehend: Nein**
- **Erforderlich** (nicht optional)
- **Zweck: App-Funktionalität**

| Kategorie → Datentyp in der Console | Was genau |
|---|---|
| Personenbezogene Daten → **Nutzer-IDs** | Nextcloud-Benutzername, Server-Adresse |
| **Dateien und Dokumente** | E-Books, Comics, Cover, Metadaten (Titel, Autoren, Tags, Regale) |
| App-Aktivitäten → **Sonstige von Nutzern erstellte Inhalte** | Markierungen und Notizen in Büchern |
| App-Aktivitäten → **Sonstige Aktionen** | Lesefortschritt, Lesestatus, Bewertungen, frei wählbarer Gerätename beim Lesefortschritt |

**Nicht ankreuzen**, denn nichts davon wird erhoben:
- Standort, Kontakte, Fotos/Videos, Audio, Nachrichten, Kalender
- Finanz- und Gesundheitsdaten
- Web-Browserverlauf, Suchverlauf (die Suche in der Bibliothek läuft nur auf dem Gerät)
- installierte Apps, Absturzprotokolle, Diagnose- und Leistungsdaten
- Geräte- oder andere IDs, Werbe-ID

**Falls die Console separat nach der Werbe-ID fragt:** „Nein, meine App verwendet keine Werbe-ID.“

### 7. Behörden-Apps

**Nein.** Die App wird nicht von oder im Auftrag einer Behörde entwickelt.

### 8. Finanzfunktionen

**„Meine App bietet keine Finanzfunktionen.“** Sie hat keine Zahlungen, Kredite, Kryptowährungen oder Bankfunktionen. Der Spendenlink fehlt im Play-Build.

### 9. Gesundheit

**„Meine App hat keine Gesundheitsfunktionen.“**

### Weitere Erklärungen, die eventuell auftauchen

| Erklärung | Antwort |
|---|---|
| **Vordergrunddienste** (Berechtigung `FOREGROUND_SERVICE_DATA_SYNC`) | **Typ „Datensynchronisierung“**. Begründung: *„Große E-Book- und Comic-Dateien (bis ca. 400 MB) werden auf Wunsch der Nutzerin oder des Nutzers zum Offline-Lesen heruntergeladen. Der Vordergrunddienst stellt sicher, dass der Download nicht abbricht, wenn die App im Hintergrund ist; eine Benachrichtigung zeigt den Fortschritt.“* Dazu ein **Video**: Bildschirmaufnahme, in der du ein Buch „Offline verfügbar“ machst und die Download-Benachrichtigung erscheint. Hochladen als „nicht gelistet“ bei YouTube oder als Datei. |
| Nachrichten-App | Nein |
| COVID-19 | Nein |
| Kontaktaufnahme mit Kindern / Familienrichtlinie | entfällt (Zielgruppe 18+) |

---

## B. Organisation und Präsentation deiner App verwalten

### 10. App-Kategorie auswählen und Kontaktdaten angeben

| Feld | Eintrag |
|---|---|
| App oder Spiel | **App** |
| Kategorie | **Bücher & Nachschlagewerke** |
| Tags (bis 5) | E-Book-Reader, Comics, Bücher, Cloud-Speicher, Dateiverwaltung (aus der Liste wählen, was angeboten wird) |
| E-Mail-Adresse | `it@wasmitleder.de`. **Sie ist öffentlich im Store sichtbar.** Eine eigene Adresse wie `support@…` hält das Postfach sauber. |
| Telefonnummer | optional. Leer lassen, außer du willst sie öffentlich zeigen. |
| Website | optional, z. B. die Seite mit Datenschutzerklärung und Impressum |
| Externes Marketing | nach Wunsch. Erlaubt Google, die App außerhalb von Play zu bewerben. |

### 11. Store-Eintrag einrichten

**Name.** Den finalen Namen entscheidest du. Er darf nicht wie eine offizielle Nextcloud-App wirken: kein „Nextcloud“ am Anfang, kein Nextcloud-Logo. Vorschläge (max. 30 Zeichen):

| Vorschlag | Zeichen |
|---|---|
| `SomeCat Reader` | 14 |
| `SomeCat Reader: E-Books` | 23 |
| `Cloud Shelf – E-Book Reader` | 27 |

Der Name auf dem Startbildschirm (`app_name`, heute „E-Book Reader“) kann davon abweichen. „E-Book Reader“ ist als Store-Name zu allgemein und vermutlich schon vergeben.

**Texte:** fertig in `fastlane/metadata/android/de-DE/` und `en-US/`.

| Datei | Feld | Grenze |
|---|---|---|
| `title.txt` | App-Name | 30 Zeichen |
| `short_description.txt` | Kurzbeschreibung | 80 Zeichen |
| `full_description.txt` | Vollständige Beschreibung | 4000 Zeichen |

Standardsprache **Deutsch (de-DE)**. Über „Übersetzungen verwalten“ → „Eigene Übersetzungen hinzufügen“ kommen **Englisch (en-US), Spanisch (es-ES) und Japanisch (ja-JP)** dazu, jeweils mit den Texten aus dem passenden Ordner. Die spanischen und japanischen Texte sind maschinell übersetzt. Lass sie vor der Veröffentlichung von jemandem mit Muttersprache gegenlesen.

Die Datenschutzerklärung gibt es in allen vier Sprachen. In der Console trägst du eine URL ein; am besten eine Seite mit allen Sprachen oder die deutsche Seite mit Links auf die anderen.

**Grafiken, die du noch erstellen musst:**

| Grafik | Format | Hinweise |
|---|---|---|
| App-Symbol | 512 × 512 px, PNG, 32 Bit, max. 1 MB | das Launcher-Icon in groß, ohne Nextcloud-Logo, ohne Rand und Schatten (Google rundet selbst) |
| Feature-Grafik | 1024 × 500 px, JPG oder 24-Bit-PNG, ohne Transparenz | Pflicht. Wichtiges mittig platzieren, wenig Text. |
| Smartphone-Screenshots | 2 bis 8 Stück, Seitenverhältnis 9:16, mind. 1080 px Kantenlänge | Vorschläge unten |
| 7"- und 10"-Tablet-Screenshots | optional | nur wenn die App auf Tablets beworben werden soll (sie hat ein Zwei-Spalten-Layout) |

**Screenshot-Vorschläge**, aufgenommen mit dem Prüfer-Nutzer und **nur gemeinfreien Büchern**:
1. Bibliothek im Raster mit Covern und „Weiterlesen“-Leiste
2. Filterblatt mit Genres als Baum und ein-/ausgeschlossenen Filtern
3. Reader mit einer EPUB-Seite in Sepia
4. Comic im Reader, gezoomt
5. Regale und Serien
6. Buchdetails mit Lesestatus, Bewertung und „Offline verfügbar“
7. Markierungen und Notizen im Reader

Screenshots ohne private Bücher, ohne echte Cover urheberrechtlich geschützter Werke und ohne deine Server-Adresse in der Statusleiste. Den Demo-Modus über die Entwickleroptionen einschalten, das sorgt für eine saubere Statusleiste.

---

## C. Danach

1. Interner Test: Tag `v0.2.0-rc.1` auf `dev` setzen → der Release-Workflow lädt das AAB in den internen Test hoch.
2. Bei einem **persönlichen Entwicklerkonto**: **geschlossener Test mit mindestens 12 Testern über 14 Tage am Stück**, erst dann ist Produktion möglich.
3. Produktionszugriff beantragen, Fragen zum Test beantworten, veröffentlichen.

**Noch offen, bevor du einreichst:**
- [ ] URL der Datenschutzerklärung festlegen und in der App eintragen (siehe 1.)
- [ ] Platzhalter für Name und Anschrift in allen vier Fassungen der Datenschutzerklärung füllen
- [ ] Spanische und japanische Texte gegenlesen lassen
- [ ] Prüfer-Nutzer `<PRÜFER-NUTZER>` mit gemeinfreien Büchern anlegen (siehe 2.)
- [ ] App-Namen für den Store festlegen
- [ ] Icon 512 px, Feature-Grafik und Screenshots erstellen
- [ ] Video für den Vordergrunddienst aufnehmen
