# Ebook Reader for Nextcloud (Android-App)

[![Jetzt bei Google Play](https://img.shields.io/badge/Google%20Play-Ebook%20Reader%20for%20Nextcloud-01875f?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=com.somecatcode.ebookreader)
[![Nextcloud App Store](https://img.shields.io/badge/Nextcloud%20App%20Store-E--Book%20Reader-0082c9?logo=nextcloud&logoColor=white)](https://apps.nextcloud.com/apps/ebookreader)

E-Book- und Comic-Reader für Android (EPUB, MOBI, AZW3, FB2, FBZ, CBZ, CBR, CB7, CBT). Die App nutzt denselben Reader-Kern wie die Nextcloud-App **E-Book Reader** ([`SomeCatCode/nextcloud_ebook_reader`](https://github.com/SomeCatCode/nextcloud_ebook_reader)).

| | Name | Bezugsquelle |
|---|---|---|
| Android-App | Ebook Reader for Nextcloud | [Google Play](https://play.google.com/store/apps/details?id=com.somecatcode.ebookreader), APK in den [Releases](https://github.com/SomeCatCode/nextcloud_ebook_reader_app/releases) |
| Nextcloud-App (Server) | E-Book Reader | [Nextcloud App Store](https://apps.nextcloud.com/apps/ebookreader) |

Auf dem Startbildschirm heißt die Android-App „E-Book Reader“.

Die App funktioniert **eigenständig** und optional **mit Nextcloud**:

- **Lokale Ordner:** Jeder Ordner auf dem Gerät ist eine eigene Bibliothek. Die App nutzt die Android-Ordnerauswahl und braucht keine Speicherberechtigung. Die App liest Titel, Autoren, Serien, Beschreibung und Cover aus den Dateien. Lesestand und Markierungen bleiben auf dem Gerät.
- **Nextcloud:** Anmeldung per Login Flow v2 (App-Passwort, kein Passwort in der App) bei einer oder mehreren Nextclouds. Die App synchronisiert Bibliothek, Regale, Lesestand und Markierungen. Bücher lassen sich online oder offline lesen.
- **Kopieren und Verschieben** von Büchern zwischen allen Konten (lokal ↔ Nextcloud, Nextcloud ↔ Nextcloud), samt Lesestand und Markierungen.

Weitere Funktionen:

- Vorlesen mit den Stimmen des Geräts
- Comics und Manga: Zoom, Doppelseiten, Leserichtung von rechts nach links, Webtoons als langer Streifen
- E-Ink-Modus mit hohem Kontrast und ohne Animationen
- Konten per Schalter deaktivieren, ohne sie zu entfernen
- 12 Sprachen: Deutsch, Englisch, Spanisch, Französisch, Italienisch, Portugiesisch (Brasilien), Japanisch, Koreanisch, Chinesisch (vereinfacht), Arabisch, Persisch, Türkisch

## Voraussetzungen

- Android 8.0 (API 26) oder neuer
- Für lokale Ordner: kein Server nötig
- Für ein Nextcloud-Konto:
  - Auf dem Server muss die Nextcloud-App **E-Book Reader** installiert sein. Ein Administrator installiert sie aus dem [Nextcloud App Store](https://apps.nextcloud.com/apps/ebookreader).
  - Version: mindestens 0.5.0, empfohlen 1.0.0 oder neuer. Bei älteren Versionen zeigt die Android-App einen Hinweis.
  - Der Server muss über HTTPS erreichbar sein.
  - Wenn die Nextcloud-App fehlt, meldet die Android-App das bei der Anmeldung und zeigt den Link zum Nextcloud App Store.

## Bauen

Benötigt werden JDK 17 oder neuer (CI: Temurin 21), das Android SDK (Plattform 36) und Node.js 24 mit npm (für das Reader-Bundle).

```sh
git clone --recurse-submodules https://github.com/SomeCatCode/nextcloud_ebook_reader_app.git
cd nextcloud_ebook_reader_app
# Falls ohne --recurse-submodules geklont:
git submodule update --init --recursive

echo "sdk.dir=/pfad/zum/Android/Sdk" > local.properties   # nur lokal, nicht eingecheckt

./gradlew assembleDebug test lint
```

Der Gradle-Task `buildReaderWeb` baut vor jedem Build per `npm ci && npm run build` das Reader-Bundle (`reader-web/`, nutzt `packages/reader-core` aus dem Submodul `third_party/nextcloud_ebook_reader`) nach `app/src/main/assets/reader/` (nicht eingecheckt). Mit `-PskipReaderWeb` wird dieser Schritt übersprungen, z. B. für schnelle Läufe ohne Node.

Die Version kommt aus den Gradle-Properties `appVersionCode` und `appVersionName`. Die Standardwerte stehen in `gradle.properties`. Beispiel: `./gradlew assembleRelease -PappVersionCode=1020399 -PappVersionName=1.2.3`. Die Formel für `appVersionCode` steht in [docs/RELEASING.md](docs/RELEASING.md). Release-Builds werden signiert, wenn `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` und `ANDROID_KEY_PASSWORD` gesetzt sind.

## Projektstruktur

Siehe [PLAN.md](PLAN.md) (Abschnitt 6) und die technischen Verträge in [docs/CONTRACTS.md](docs/CONTRACTS.md).

## Datenschutz

Keine Analyse, kein Tracking, keine Drittanbieter-SDKs mit Datenerhebung. Lokale Bibliotheken verlassen das Gerät nie (außer beim Kopieren in ein Nextcloud-Konto). App-Passwörter liegen verschlüsselt (Android Keystore, AES-GCM) nur auf dem Gerät.

## Lizenz

Proprietär, alle Rechte vorbehalten (siehe [LICENSE](LICENSE)). Die App ist nicht quelloffen; Komponenten Dritter behalten ihre eigenen Lizenzen (Hinweise in `app/src/main/assets/third_party_licenses.txt`).
