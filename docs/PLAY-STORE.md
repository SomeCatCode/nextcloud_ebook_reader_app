# Play Store: was du angeben musst

> **Zum Ausfüllen der Console:** [PLAY-CONSOLE-LEITFADEN.md](PLAY-CONSOLE-LEITFADEN.md) folgt Punkt für Punkt der Checkliste der Play Console und enthält fertige Texte. Diese Datei hier bleibt der Hintergrund. Seit 0.2.0 ist die App proprietär und das Repo privat. Für die Datenschutzerklärung siehe den Leitfaden, Punkt 1.

Leitfaden für die Google Play Console, zugeschnitten auf diese App: ein **Client für den eigenen bzw. einen frei gewählten Nextcloud-Server**, ohne eigene Server des Entwicklers, ohne Werbung und ohne Tracking.

> Stand: Oktober 2026. Google ändert Formulare und Richtlinien regelmäßig. Wo die Console etwas anderes fragt, gilt die Console. Rechtsberatung ersetzt diese Datei nicht.

---

## 0. Bevor es losgeht

| Punkt | Was zu tun ist |
|---|---|
| **Entwicklerkonto** | Play Console, einmalig 25 $. Wähle zwischen **persönlich** und **Organisation**. |
| ⚠️ **Testpflicht bei persönlichen Konten** | Neue persönliche Konten müssen vor der ersten Produktiv-Veröffentlichung einen **geschlossenen Test mit mindestens 12 Testern über 14 Tage am Stück** durchführen. Organisationskonten (mit D-U-N-S-Nummer) sind davon ausgenommen. **Plane die zwei Wochen ein.** |
| **Identitätsprüfung** | Google verlangt eine Verifizierung (Ausweis, ggf. Adresse, Telefon). Die angegebene Kontakt-E-Mail ist im Store öffentlich sichtbar. |
| **Paket-ID** | `com.somecatcode.ebookreader`. Sie lässt sich nach dem ersten Upload nie mehr ändern. |
| **Play App Signing** | Aktivieren (Standard). Du lädst mit einem **Upload-Schlüssel** hoch, Google verwaltet den App-Signaturschlüssel. Den Upload-Schlüssel gut sichern, Anleitung siehe README. |

---

## 1. Name, Marke und „fremde“ Nextcloud

**Die App ist ein Client.** Sie zeigt nur, was auf dem Server liegt, den die Nutzerin oder der Nutzer selbst angibt. Du betreibst keinen Server und siehst keine Daten. Das ist im Play Store erlaubt und üblich, so funktionieren auch die Nextcloud-, Jellyfin- und Home-Assistant-Apps. Wichtig sind aber zwei Dinge:

1. **Markenrecht und Verwechslungsgefahr**
   - „Nextcloud“ ist eine eingetragene Marke der Nextcloud GmbH. Der Name darf nicht den Eindruck einer offiziellen App erwecken.
   - Verwende **kein Nextcloud-Logo** im Icon, im Feature-Grafik-Bild oder in Screenshots, auch nicht abgewandelt.
   - Gut ist ein eigener Name mit beschreibendem Zusatz, z. B. **„SomeCat Reader – E-Books für Nextcloud“**. Riskant sind Namen wie „Nextcloud E-Book Reader“ oder „Nextcloud Books“.
   - Schreibe in die Beschreibung: *„Inoffizielle App, nicht mit der Nextcloud GmbH verbunden. Nextcloud ist eine Marke der Nextcloud GmbH.“*
   - Prüfe vor der Veröffentlichung die aktuellen Markenrichtlinien auf nextcloud.com (Bereich „Trademarks“).
2. **Funktionsfähigkeit für die Prüfung**
   - Google testet die App. Ohne Server kann der Prüfer nur den Anmeldebildschirm sehen. Deshalb musst du einen **Testzugang** angeben, siehe Punkt 3.

---

## 2. Store-Eintrag (Hauptinformationen)

| Feld | Vorgabe | Vorschlag |
|---|---|---|
| App-Name | max. 30 Zeichen | „SomeCat Reader“ (Zusatz „für Nextcloud“ in der Kurzbeschreibung) |
| Kurzbeschreibung | max. 80 Zeichen | „E-Books & Comics aus deiner Nextcloud lesen – offline, mit Lesestand-Sync.“ |
| Vollständige Beschreibung | max. 4000 Zeichen | Funktionen, Voraussetzung **„Benötigt eine Nextcloud mit der App E-Book Reader ab 0.5.0“**, Hinweis „inoffiziell“, Datenschutz-Kurzfassung |
| App-Symbol | 512 × 512 PNG, 32 Bit | eigenes Logo, ohne Nextcloud-Logo |
| Feature-Grafik | 1024 × 500 | Pflicht |
| Screenshots | mind. 2 fürs Smartphone (16:9 oder 9:16), besser 4–8; für Tablets 7" und 10" separat, wenn die App bei Tablets gut sichtbar sein soll | mit **gemeinfreien Büchern** (Project Gutenberg, Standard Ebooks), keine urheberrechtlich geschützten Cover |
| Kategorie | | **Bücher & Nachschlagewerke** |
| Tags | | E-Book-Reader, Comic-Reader |
| Kontaktdaten | E-Mail Pflicht, Website optional | |
| Datenschutzerklärung | **Pflicht-URL** | `PRIVACY.md` aus dem Repo über GitHub Pages veröffentlichen |

Die Texte legen wir im Repo unter `fastlane/metadata/android/de-DE` und `en-US` ab, damit sie versioniert sind.

---

## 3. App-Inhalte (Abschnitt „Richtlinie > App-Inhalte“)

### 3.1 Datenschutzerklärung
URL zur `PRIVACY.md`, siehe Abschnitt 6. Ohne sie lässt sich nichts veröffentlichen.

### 3.2 App-Zugriff („Zugriffsbeschränkungen“)
- Wähle **„Alle oder einige Funktionen sind eingeschränkt“**.
- Gib eine **Anleitung mit Testzugang** an: Server-URL, Benutzername, Passwort sowie kurze Schritte („Konto hinzufügen → Server-URL eingeben → im Browser anmelden → Bibliothek öffnet sich“).
- ⚠️ **Lege dafür einen eigenen Test-Nutzer an**, z. B. `<PRÜFER-NUTZER>`, mit:
  - **nur gemeinfreien Büchern** (ein paar EPUBs und ein Comic). Auf keinen Fall Zugriff auf deine eigene Bibliothek, schon gar nicht auf Inhalte für Erwachsene. Sonst drohen eine Ablehnung oder eine falsche Altersfreigabe.
  - ohne Admin-Rechte, mit kleinem Speicherkontingent, ohne Freigaben zu anderen Nutzern
  - einem Login, der dauerhaft funktioniert. Google prüft auch spätere Updates. Kein Ablaufdatum, keine Zwei-Faktor-Anmeldung für diesen Nutzer.
- Der Login läuft über den Browser (Login Flow v2). Erwähne das in der Anleitung, damit der Prüfer nicht verwirrt ist.

### 3.3 Werbung
**„Nein, meine App enthält keine Werbung.“**

### 3.4 Einstufung des Inhalts (IARC-Fragebogen)
- **Kategorie:** „Referenz, Nachrichten oder Bildung“, oder falls angeboten „Dienstprogramm/Produktivität“.
- Die App liefert **keine eigenen Inhalte**. Sie zeigt nur Dateien vom Server der Nutzerin oder des Nutzers, vergleichbar mit einem Datei-Viewer.
- **Austausch zwischen Nutzern** (Chat, Teilen, öffentliche Inhalte): **Nein**. Es gibt keine Interaktion mit anderen Nutzern in der App.
- **Uneingeschränkter Internetzugriff / Webbrowser:** Nein. Die App verbindet sich nur mit dem angegebenen Server, externe Links in Büchern öffnen sich erst nach Rückfrage im Systembrowser.
- **Ergebnis:** voraussichtlich USK 0 bzw. PEGI 3 / „Jedes Alter“. Das ist korrekt, weil die Einstufung den Inhalt der App selbst bewertet, nicht die privaten Dateien.

### 3.5 Zielgruppe und Inhalte
- **Zielgruppe:** **„18 Jahre und älter“**, alternativ „16+“. **Keine Altersgruppe unter 13 wählen.** Sonst gilt die Familienrichtlinie mit deutlich strengeren Anforderungen, z. B. Einwilligungen, Werbebeschränkungen und spezielle SDK-Vorgaben.
- **„Könnte die App unbeabsichtigt Kinder ansprechen?“** Nein, das ist ein Werkzeug für die eigene Cloud.

### 3.6 Weitere Erklärungen
| Frage | Antwort |
|---|---|
| Nachrichten-App? | Nein |
| COVID-19-/Gesundheits-App? | Nein |
| Finanzfunktionen? | Nein |
| Behörden-App? | Nein |
| Werbe-ID (Advertising ID) | **„Nein, meine App verwendet keine Werbe-ID“.** Wir binden keine Bibliothek ein, die `AD_ID` nutzt. Das wird im Manifest geprüft, und die Berechtigung wird bei Bedarf entfernt. |
| Kontolöschung | Die App **erstellt keine Konten**, sie meldet sich nur bei einem bestehenden Nextcloud-Konto an. Die Pflicht zur Kontolöschung gilt nur für Apps, in denen man Konten anlegen kann. Angabe: *„Keine Kontoerstellung in der App.“* Zusätzlich bietet die App „Konto entfernen“, das auch das App-Passwort auf dem Server widerruft. |

---

## 4. Datensicherheit („Data safety“)

Hier gibt es zwei vertretbare Lesarten, weil die Daten nur **zwischen dem Gerät und dem Server der Nutzerin oder des Nutzers** fließen. Der Entwickler, also du, erhält nie Daten.

**Empfehlung: die vorsichtige, transparente Angabe.** Google bewertet „Übertragung vom Gerät weg“ als Erhebung, auch wenn der Server der Nutzerin oder dem Nutzer gehört. Viele selbst gehostete Clients geben es deshalb so an:

| Frage | Antwort |
|---|---|
| Erhebt oder teilt die App erforderliche Nutzerdaten? | **Ja, erhebt**, nur zur Funktion und nur gegenüber dem eigenen Server |
| Daten verschlüsselt bei der Übertragung? | **Ja** (nur HTTPS) |
| Können Nutzer die Löschung beantragen? | **Ja**: Konto in der App entfernen, das löscht alle lokalen Daten und widerruft das App-Passwort. Daten auf dem Server verwaltet die Nutzerin oder der Nutzer selbst. |
| **Weitergabe an Dritte** | **Nein**. Der eigene Server ist keine Weitergabe an Dritte, weil die Nutzerin oder der Nutzer ihn selbst wählt. |

Erhobene Datentypen, alle **nicht geteilt**, Zweck **App-Funktionalität**, **erforderlich**:

| Datentyp (Console) | Was genau |
|---|---|
| Personenbezogene Daten → **Nutzer-IDs** | Nextcloud-Benutzername, Server-Adresse |
| **Dateien und Dokumente** | E-Books, Comics, Cover, Metadaten (Titel, Tags …) |
| App-Aktivitäten → **Sonstige Nutzeraktionen in der App** | Lesefortschritt, Lesestatus, Bewertungen |

**Nicht erhoben:** Standort, Kontakte, Fotos, Finanzdaten, Gesundheit, Absturzberichte und Analysedaten (es ist kein SDK dafür eingebaut), Geräte-IDs bzw. Werbe-ID.

> Die Alternative „keine Daten erhoben“ wird teils mit dem Argument genutzt, dass der Entwickler nichts empfängt. Sie ist angreifbarer. Die obige Angabe ist sicher und ehrlich. Wichtig ist, dass die Datenschutzerklärung genau dasselbe sagt.

---

## 5. Berechtigungen der App

| Berechtigung | Wofür | Muss in der Console erklärt werden? |
|---|---|---|
| `INTERNET` | Verbindung zum Nextcloud-Server | nein |
| `ACCESS_NETWORK_STATE` | Downloads nur bei Netz bzw. nur im WLAN (Einstellung) | nein |
| `POST_NOTIFICATIONS` (Android 13+) | Fortschritt großer Downloads, „Download fertig“ | nein. Die Nutzerin oder der Nutzer wird zur Laufzeit gefragt. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | große Downloads (bis 400 MB) zuverlässig im Hintergrund fertigstellen | ⚠️ **Ja**: Angabe in der Console „Vordergrunddienste“, Typ **Datensynchronisierung**, mit kurzer Begründung und meist einem **Video** (z. B. Bildschirmaufnahme: Regal offline verfügbar machen → Download-Benachrichtigung). |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | kommen automatisch über WorkManager (periodischer Sync) | nein |

**Nicht benötigt:** Speicherzugriff (die App speichert in ihrem eigenen Ordner), Kamera, Kontakte, Standort, „Alle Dateien“-Zugriff, Paketliste. Darauf achten wir im Code. Jede zusätzliche sensible Berechtigung macht die Prüfung schwerer.

---

## 6. Datenschutzerklärung (Inhalt der `PRIVACY.md`)

Sie muss öffentlich erreichbar sein, über GitHub Pages oder deine Website, und mindestens enthalten:

1. **Verantwortlicher:** Name bzw. SomeCatCode und Kontakt-E-Mail
2. **Was die App verarbeitet:** Server-Adresse, Benutzername, App-Passwort (verschlüsselt auf dem Gerät), E-Books und Metadaten, Lesefortschritt
3. **Wohin Daten gehen:** **ausschließlich** zu dem Nextcloud-Server, den die Nutzerin oder der Nutzer einträgt. Für diesen Server ist dessen Betreiber verantwortlich, also die Nutzerin oder der Nutzer selbst bzw. der Anbieter ihrer Cloud.
4. **Was nicht passiert:** keine Server des Entwicklers, keine Werbung, kein Tracking, keine Analyse- oder Absturzdienste, keine Weitergabe
5. **Speicherung auf dem Gerät und Löschung:** Konto entfernen bzw. App deinstallieren löscht alles lokal, das App-Passwort wird auf dem Server widerrufen
6. **Rechte nach DSGVO** (Auskunft, Löschung …). Da du keine Daten hast, verweist du für Serverdaten an den jeweiligen Server-Betreiber.
7. **Kinder:** nicht für Kinder unter 16 bestimmt
8. Datum und Änderungen

Einen Entwurf lege ich in Phase 6 als `PRIVACY.md` (Deutsch und Englisch) an.

---

## 7. Technische Store-Anforderungen (erledigt der Code bzw. die CI)

- **Ziel-API:** Google verlangt eine aktuelle `targetSdk` (innerhalb eines Jahres nach dem neuesten Android). Wir nutzen 36.
- **Format:** Android App Bundle (`.aab`), signiert mit dem Upload-Schlüssel, gebaut vom Release-Workflow
- **64-Bit:** automatisch erfüllt, die App ist reiner Kotlin- und WebView-Code ohne eigene Native-Bibliotheken
- **Versionsnummern:** `versionCode` steigt mit jedem Upload, er wird aus dem Git-Tag abgeleitet

---

## 8. Ablauf bis zur Veröffentlichung (Checkliste)

1. [ ] Play-Console-Konto anlegen und verifizieren
2. [ ] App anlegen (Name, Standardsprache Deutsch, „App“, „kostenlos“)
3. [ ] Test-Nutzer `<PRÜFER-NUTZER>` auf der Nextcloud mit gemeinfreien Büchern anlegen
4. [ ] Datenschutzerklärung veröffentlichen (GitHub Pages)
5. [ ] App-Inhalte ausfüllen: Datenschutz, App-Zugriff mit Testzugang, Werbung, Einstufung, Zielgruppe 18+, Datensicherheit, Werbe-ID, Vordergrunddienst inkl. Video
6. [ ] Store-Eintrag: Texte, Icon, Feature-Grafik, Screenshots
7. [ ] Upload-Schlüssel erzeugen, Secrets in GitHub hinterlegen, Release-Tag → AAB
8. [ ] **Geschlossener Test** mit mindestens 12 Testern über 14 Tage (bei persönlichem Konto)
9. [ ] Produktionszugriff beantragen (Google fragt nach den Testerfahrungen), dann Produktion
