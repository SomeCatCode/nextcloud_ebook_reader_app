# E-Book Reader für Android

E-Book- und Comic-Reader für Android (EPUB, MOBI, AZW3, FB2, FBZ, CBZ, CBR, CB7, CBT) mit demselben Reader-Kern wie die Nextcloud-App **E-Book Reader** ([`SomeCatCode/nextcloud_ebook_reader`](https://github.com/SomeCatCode/nextcloud_ebook_reader)). Die App funktioniert **eigenständig** und optional **mit Nextcloud**:

- **Lokale Ordner:** Jeder Ordner auf dem Gerät (über die Android-Ordnerauswahl, ohne Speicherberechtigung) ist eine eigene Bibliothek. Die App liest Titel, Autoren, Serien, Beschreibung und Cover aus den Dateien; Lesestand und Markierungen bleiben auf dem Gerät.
- **Nextcloud:** Anmeldung per Login Flow v2 (App-Passwort, kein Passwort in der App) bei einer oder mehreren Nextclouds, Sync von Bibliothek, Regalen, Lesestand und Markierungen, online lesen oder offline.
- **Kopieren und Verschieben** von Büchern zwischen allen Konten (lokal ↔ Nextcloud, Nextcloud ↔ Nextcloud), samt Lesestand und Markierungen.

## Voraussetzungen

- Android 8.0 (API 26) oder neuer
- Optional: eine Nextcloud mit der App **E-Book Reader ≥ 0.5.0** (ältere Versionen werden in der App gemeldet), nur über HTTPS

## Bauen

Benötigt werden JDK 17 oder neuer (CI: Temurin 21), das Android SDK (Plattform 36) und Node.js 22 mit npm (für das Reader-Bundle).

```sh
git clone --recurse-submodules https://github.com/SomeCatCode/nextcloud_ebook_reader_app.git
cd nextcloud_ebook_reader_app
# Falls ohne --recurse-submodules geklont:
git submodule update --init --recursive

echo "sdk.dir=/pfad/zum/Android/Sdk" > local.properties   # nur lokal, nicht eingecheckt

./gradlew assembleDebug test lint
```

Der Gradle-Task `buildReaderWeb` baut vor jedem Build per `npm ci && npm run build` das Reader-Bundle (`reader-web/`, nutzt `packages/reader-core` aus dem Submodul `third_party/nextcloud_ebook_reader`) nach `app/src/main/assets/reader/` (nicht eingecheckt). Mit `-PskipReaderWeb` wird dieser Schritt übersprungen, z. B. für schnelle Läufe ohne Node.

Die Version kommt aus den Gradle-Properties `appVersionCode` und `appVersionName` (Standard `1` / `0.1.0`), z. B. `./gradlew assembleRelease -PappVersionCode=10203 -PappVersionName=1.2.3`. Release-Builds werden signiert, wenn `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` und `ANDROID_KEY_PASSWORD` gesetzt sind.

## Projektstruktur

Siehe [PLAN.md](PLAN.md) (Abschnitt 6) und die technischen Verträge in [docs/CONTRACTS.md](docs/CONTRACTS.md).

## Datenschutz

Keine Analyse, kein Tracking, keine Drittanbieter-SDKs mit Datenerhebung. Lokale Bibliotheken verlassen das Gerät nie (außer beim Kopieren in ein Nextcloud-Konto). App-Passwörter liegen verschlüsselt (Android Keystore, AES-GCM) nur auf dem Gerät.

## Lizenz

Proprietär, alle Rechte vorbehalten (siehe [LICENSE](LICENSE)). Die App ist nicht quelloffen; Komponenten Dritter behalten ihre eigenen Lizenzen (Hinweise in `app/src/main/assets/third_party_licenses.txt`).
