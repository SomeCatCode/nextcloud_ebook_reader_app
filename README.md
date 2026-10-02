# E-Book Reader für Android

Android-App als Begleiter der Nextcloud-App **E-Book Reader** ([`SomeCatCode/nextcloud_ebook_reader`](https://github.com/SomeCatCode/nextcloud_ebook_reader)). Sie meldet sich per Login Flow v2 (App-Passwort, kein Passwort in der App) bei einer oder mehreren Nextclouds an, synchronisiert die Bibliothek und den Lesestand und liest alle Formate des Servers (EPUB, MOBI, AZW3, FB2, FBZ, CBZ, CBR, CB7, CBT) mit demselben Reader-Kern wie im Web – online oder offline.

> Status: **Phase 0** (Grundgerüst). Die App startet mit Platzhalter-Bildschirmen; Konten, Sync, Downloads, Reader und Bearbeiten folgen laut [PLAN.md](PLAN.md).

## Voraussetzungen

- Android 8.0 (API 26) oder neuer
- Eine Nextcloud mit der App **E-Book Reader ≥ 0.5.0** (ältere Versionen werden in der App gemeldet)
- HTTPS (HTTP wird in v1 nicht unterstützt)

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

Keine Analyse, kein Tracking, keine Drittanbieter-SDKs mit Datenerhebung. App-Passwörter liegen verschlüsselt (Android Keystore, AES-GCM) nur auf dem Gerät.

## Lizenz

[AGPL-3.0-or-later](LICENSE), wie die Nextcloud-App.
