# Diecast Tracker — Android

Native Android rewrite of the **Diecast Tracker** web app, so it runs on the phone only when opened —
no server, no hosting. Single user, personal collection.

The web app at `C:\laragon\www\diecast-tracker` (Laravel 13 + Inertia/React, MySQL) is the **reference for
behaviour and logic**. It keeps working on the laptop until the Android app catches up. Port logic from it
rather than re-inventing; its feature tests (`tests/Feature/*`) describe the expected behaviour and contain
real-world fixtures.

## Status (2026-10-18)

**Phase 1 done** (not yet committed — the user runs `git init` and all git commands themselves; give them the
commands and the commit messages). Next step: **phase 2** (AI scan).

Phase 1 delivered: collection grid/list (search incl. tags, filters status/brand/rarity/tag, 7 sorts, layout and
sort remembered in DataStore), add/edit/delete with up to 8 photos (camera via FileProvider + photo picker, first =
cover), tags, the four statuses, read-only sightings list, web backup import (merge/replace, one transaction).

## Build, test, run

- Toolchain: Gradle 9.8.1 (wrapper), AGP 9.4.1 with built-in Kotlin, Kotlin 2.4.21, KSP 2.3.12, Compose BOM
  2026.09.00, Room 2.8.5, Coil 3, Navigation Compose 2.10 (type-safe routes). compileSdk 37, targetSdk 36, minSdk 29.
  Versions live in `gradle/libs.versions.toml`.
- `./gradlew :app:testDebugUnitTest` — JVM + Robolectric tests (Room in memory, native graphics). The test JVM needs
  `--add-exports java.base/jdk.internal.access` on JBR 25; already set in `app/build.gradle.kts`.
- `./gradlew :app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease` (R8, ~4 MB). Release is signed with
  `signing.properties` (git-ignored) if present, otherwise with this laptop's debug key
  (`~/.android/debug.keystore`) — changing the key later means uninstalling the app on the phone (data loss).
- Room schemas are exported to `app/schemas`; every schema change needs a real migration (no destructive fallback).
- Test fixture `app/src/test/resources/web-backup-v1.zip` is made by the web app's own `CollectionBackup`:
  `php -d extension=zip tools/make-web-backup-fixture.php` (in-memory SQLite, does not touch the web's data).
- Emulator: the user likes to watch while testing — start it **with a window**
  (`emulator -avd Medium_Phone_API_37.0 -no-audio -no-boot-anim`). Launch the app with the launcher intent
  (`adb shell monkey -p io.github.caropeboka.diecasttracker -c android.intent.category.LAUNCHER 1`), not
  `am start -n`. In Git Bash prefix adb with `MSYS_NO_PATHCONV=1` (else `/sdcard` becomes a Windows path).

## Notes from phase 1

- Money is whole Rupiah (`Long`); the backup's `"35000.00"` strings are rounded half-up.
- The web's backup zips contain `.webp` photos too (its own restore drops them); the importer converts them to JPEG.
- Photos: new ones wait in `files/photos-tmp` until the diecast is saved, then move to `files/photos` after the
  transaction commits. `DiecastRepository.housekeeping()` at start-up finishes interrupted moves and prunes leftovers.
- The add/edit form state is kept in `SavedStateHandle` (JSON) so it survives process death while the camera is open.
- `MainActivity` finishes a launcher-started duplicate on top of an existing task (sideloaded apps opened via the
  installer's "Open" button otherwise stack a second copy).

## Decisions (made with the user)

| Topic | Decision |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose, Material 3 with the "Garage" dark theme (below). UI text in **Bahasa Indonesia**, money in **Rupiah** (`id-ID`, no decimals). |
| Data | Room (SQLite) on the device. Photos in app-private storage. |
| Network | OkHttp + kotlinx.serialization (Gemini, Hot Wheels/Matchbox wikis, Nominatim). Coil for images. |
| Location / map | Fused Location Provider; **osmdroid** with OpenStreetMap tiles (no API key). |
| Background work | WorkManager (Dex sync, thumbnail downloads, bulk scan queue). |
| Settings | DataStore (Gemini/Claude API key, model). |
| Lock | None — opens straight in (personal phone). |
| Existing data | Import the web app's **backup zip** (see below). |
| Devices | Phone: Android 16 (API 36), USB debugging not set up yet. Emulator AVD `Medium_Phone_API_37.0` (API 37, WHPX acceleration works). |

## Phases (each ends with a usable APK)

1. **Foundation + collection** — project, theme, Room schema, collection list/grid with search, filters
   (status, brand, rarity, tag) and sort; add/edit/delete with multiple photos (camera + gallery, first = cover),
   tags, statuses; **import web backup zip**.
2. **AI scan** — one photo fills the form; duplicate warning; AI settings screen.
3. **Sightings & location** — "Ditemui" sighting log, GPS location picker, hunting map.
4. **Dex** — wiki sync, matching, seen/caught progress per year/series, own dex lists.
5. **The rest** — market value from marketplace screenshots, dashboard, bulk scan, in-store check, export/backup.

## Domain model (from `app/Models/*` in the web app)

**Diecast**: name*, brand, make, series, collection_number (as printed, e.g. `61/250 · 2/10`), year, scale
(`1:64` default), color, rarity, packaging, condition, status, quantity (1), purchase_price, market_value
(+ source `manual|price_check`, updated_at), target_price (wishlist), purchase_date, purchase_place,
purchase_latitude/longitude, storage_location, notes, ai_scanned; ordered photos; tags; sightings;
later catalog_item_id + catalog_match (`auto|confirmed|ignored`).

- rarity: `regular, treasure_hunt, super_treasure_hunt, chase, limited, exclusive`
  (labels: Regular, Treasure Hunt, Super Treasure Hunt, Chase, Limited, Exclusive)
- packaging: `carded` (Carded (blister)), `boxed` (Box), `loose` (Loose)
- condition: `mint, near_mint, good, fair, damaged` (Mint, Near mint, Good, Fair, Rusak)
- status: `owned` (Dimiliki), `wishlist` (Wishlist), `spotted` (Ditemui), `sold` (Terjual)
- scales offered: 1:64, 1:43, 1:32, 1:24, 1:18, 1:87, 1:100

**Sighting** ("Ditemui"): place, latitude, longitude, seen_on, price, notes, photo.

## Web backup format (phase 1 import)

Zip produced by `app/Services/CollectionBackup.php`:

- `diecasts.json` → `{ "format": "diecast-tracker-backup", "version": 1, "exported_at", "diecasts": [ … ] }`;
  each record has the Diecast fields above (dates `Y-m-d`), `tags: [name]`, `photos: ["photos/<file>"]`,
  `sightings: [{ place, latitude, longitude, seen_on, price, notes, photo }]`.
- `photos/…` — every photo; `koleksi.xlsx` — human copy, ignore.
- Dex links are **not** in v1. Plan for phase 4: add a dex reference (source, year, number, variant) to the
  web backup so confirmed matches carry over.

## Where the logic lives in the web app

| Feature | Web reference |
| --- | --- |
| AI vision (Gemini REST `generateContent`, JSON schema output, model fallback from the live model list — lite models first, time budget; Claude alternative) | `app/Services/AiVision.php` |
| Card scan prompt + schema + field cleanup | `app/Services/DiecastScanner.php` |
| Duplicate detection | `app/Services/CollectionMatcher.php` |
| Market value from screenshots / statistics | `app/Services/PriceChecker.php`, `PriceAnalyzer.php` |
| GPS → place name (own spots within 150 m first, then Nominatim reverse with a User-Agent, cached) and place search | `app/Services/PlaceLookup.php` |
| Location picker UX (fixed centre pin, drag map, name follows pin unless typed/chosen, skip lookup if the pin didn't move) | `resources/js/components/location-picker.tsx` |
| Hunting map grouping and stats | `app/Http/Controllers/ExploreController.php` |
| Dex: wiki table parsing, year lists, sync, matching, progress, own lists | `app/Services/Dex/*`, fixtures in `tests/Feature/DexTest.php` |
| Dashboard figures | `app/Http/Controllers/DashboardController.php` |
| Bulk scan queue / in-store check | `BulkScanController.php`, `CheckController.php` |

Hard-won details to keep when porting:

- **Wiki parsing:** split lines on `\r\n|\r|\n` (not a regex `\R` without UTF-8 mode — byte 0x85 inside
  Cyrillic/CJK file names breaks them); strip tags with a regex, not a strip_tags-style parser (broken
  `<font color=#0C0">` markup swallows text); drop `<!-- -->` comments and `<ref>` tags.
- **Wiki etiquette:** identify with a User-Agent, pace API calls (~400 ms), retry dropped connections.
  The image CDN returns 404 for foreign referers, so download thumbnails once and keep them.
- **Card year ≠ release year:** the year printed on a card is the casting's copyright year. The Dex matcher
  matches across years and suggests the release year.
- **Dex progress:** one number = one entry (variants/TH/STH are forms); seen = owned|spotted|sold,
  caught = owned|sold, wishlist counts as "wanted" only.

## Garage theme (dark only)

| Token | Value | | Token | Value |
| --- | --- | --- | --- | --- |
| background | `#0b0b0d` | | primary / brand | `#ff6a1a` (on it: `#160a03`) |
| card | `#131316` | | brand-2 | `#ffb020` |
| popover | `#16161a` | | border | `#26262b` |
| muted / secondary | `#1c1c21` | | input | `#2c2c32` |
| foreground | `#f4f1ec` | | muted-foreground | `#9a968f` |
| accent | `#221f1c` | | radius | 6 dp |

Charts/meters (validated for the card surface): bar `#e8590c`, track `#3d2012`, middle step = OKLab 50/50 of
the two (Dex "seen, not owned"; always labelled). Rarity: TH `#199e70`, STH `#d95926`, Chase `#9085e9`,
Exclusive `#d55181`. Gain `#0ca30c`, loss `#e66767`.

Headings: **Saira Condensed** 600/700, uppercase, slight tracking. Body: **Instrument Sans**.
Status is never colour-only: pair it with an icon/label (✓ owned, 👁 seen, ♥ wanted).

## Environment

- Windows 11, Android Studio Rabbit (2026.2.1). SDK: `C:\Users\user\AppData\Local\Android\Sdk`
  (platform android-37.0, build-tools 36.0.0, emulator + `google_apis_playstore` x86_64 image).
- `JAVA_HOME` = Android Studio's JBR (OpenJDK 25) — if the Android Gradle Plugin rejects JDK 25, use a Gradle
  Java toolchain (e.g. 21) instead of changing the user's setup.
- `ANDROID_HOME` and `Path` (platform-tools, cmdline-tools\latest\bin, JBR\bin) are set at user level.
  cmdline-tools may not be installed yet (SDK Manager → SDK Tools).
- Verify UI on the emulator: `adb install`, `adb shell am start`, `adb exec-out screencap -p > shot.png`.

## Working with the user

- Reply in **Bahasa Indonesia** (they sometimes write English). Their keyboard often drops the letter "b"
  ("anyak" = banyak).
- They like to discuss bigger features before building, and to pick between clear options.
- **Git:** they run every git command themselves — provide the commands and professional English
  Conventional Commit messages, split per feature. Never commit, stage or `git rm` for them.
- System setup: default to giving steps; when they ask you to do it (e.g. environment variables), back up
  what you change first.
