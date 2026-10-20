# Diecast Tracker — Android

Native Android rewrite of the **Diecast Tracker** web app, so it runs on the phone only when opened —
no server, no hosting. Single user, personal collection.

The web app at `C:\laragon\www\diecast-tracker` (Laravel 13 + Inertia/React, MySQL) is the **reference for
behaviour and logic**. It keeps working on the laptop until the Android app catches up. Port logic from it
rather than re-inventing; its feature tests (`tests/Feature/*`) describe the expected behaviour and contain
real-world fixtures.

## Status (2026-10-20)

Phases 1–3 and 4a are committed and pushed to `github.com/caropeboka/diecast-tracker-android` (`main`). **Phase 4b
(own dex lists) is done**, version 0.4.1, tried on the emulator; not yet committed (the user runs all git commands
themselves; give them the commands and the commit messages). Next: **phase 5** (discuss the order with the user first).
Covered by tests only, as there is no real Gemini key on the emulator: the scan's Dex suggestion and the checklist
screenshots read by the AI (including its review sheet).

Phase 1 delivered: collection grid/list (search incl. tags, filters status/brand/rarity/tag, 7 sorts, layout and
sort remembered in DataStore), add/edit/delete with up to 8 photos (camera via FileProvider + photo picker, first =
cover), tags, the four statuses, read-only sightings list, web backup import (merge/replace, one transaction).

Phase 2 delivered: AI scan with **Gemini only** (the user's choice; Claude can be added later). The collection's
Scan button opens the camera straight away (`DiecastEditRoute(scan = true)`; without a key it leads to the AI settings);
the first photo(s) of a new diecast are scanned automatically; "Isi otomatis / Baca ulang dengan AI" reads up to 4
photos. AI-filled fields get ✦ and a brand border until edited; confidence note; duplicate warning like the web
(tap opens the existing diecast). AI settings screen: key shown as `••••1234`, test key → live model list, remove key.
The success path was verified with tests only — the emulator run used a fake key (error path).

Phase 3 delivered: bottom navigation (Koleksi · Peta); "Catatan temuan" on every saved diecast (add with place, date,
price, notes, photo in a bottom sheet; delete with confirm; "Termurah" badge); location fields with a map picker for
"Beli di", "Ditemui di" and sightings (fixed centre pin, own places within 150 m before OpenStreetMap, search, recent
places, "Lokasi saya" via GPS); the last picked place is remembered 3 hours and pre-fills new diecasts and sightings;
"Peta jelajah" (type/range filters, 4 stats, markers filled = bought / ring = only seen, sized by count, place details,
places list, timeline). Room schema v2 (`place_cache`, with migration).

Phase 4a delivered: a Dex tab (Koleksi · Peta · Dex) — sync Hot Wheels Mainline (2015+) / Matchbox years from the
Fandom wikis via WorkManager (progress per year, re-sync/delete per dex), auto-linking of the collection after every
sync and on save, progress meters (seen/caught/wanted) per brand, dex and series, a dex screen (grid or per series,
filters, search, entry sheet with variants and "Tambah ke koleksi / Ditemui / Wishlist"), "Cocokkan Dex" (confirm
automatic links with the release-year fix, pick a suggestion, or mark "not in the Dex"), a Dex panel in the diecast
form (edit: confirm / drop / pick, applied at once; new: linked entry from the Dex or the scan's suggestion), and
thumbnails downloaded in the background — Wi-Fi only unless "Pakai data seluler juga" is switched on. Room schema v3
(`catalogs`, `catalog_items`, `diecasts.catalog_item_id/catalog_match`, with migration).

Phase 4b delivered: own dexes ("Dex sendiri") for brands without a wiki list — create/rename/delete (brand, unique
name, optional year; the brand is prefilled with a collection brand that has no wiki list); entries by hand with a
photo (camera/gallery; the sheet stays open for the next one), corrected later ("Ubah"), another colour ("Tambah
varian"), deleted with a confirm that names linked diecasts; "Isi banyak sekaligus" from Excel (.xlsx) / CSV, from
checklist screenshots or a photo of a printed list read by the AI (max 6, reviewed and ticked before they're added),
or from the collection. The collection is auto-matched after every addition. No schema change.

## Build, test, run

- Toolchain: Gradle 9.8.1 (wrapper), AGP 9.4.1 with built-in Kotlin, Kotlin 2.4.21, KSP 2.3.12, Compose BOM
  2026.09.00, Room 2.8.5, Coil 3, Navigation Compose 2.10 (type-safe routes), OkHttp 5.5, MapLibre Android 13.6.1,
  Play Services Location 21.3, WorkManager 2.11. compileSdk 37, targetSdk 36, minSdk 29. Versions live in
  `gradle/libs.versions.toml`.
- `./gradlew :app:testDebugUnitTest` — JVM + Robolectric tests (Room in memory, native graphics). The test JVM needs
  `--add-exports java.base/jdk.internal.access` on JBR 25; already set in `app/build.gradle.kts`.
- `./gradlew :app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease` (R8, ~17 MB). MapLibre's native code is
  ~13 MB per ABI: release ships **arm64 only** (the phone), debug adds x86_64 for the emulator — so test a release
  build on the emulator only by temporarily adding x86_64 to `abiFilters`. Release is signed with
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

## Notes from phase 2

- `GeminiClient` ports `AiVision.php`: chosen model + one retry after 1.5 s on 500/503, then lite models first, up to
  4 fallbacks within 80 s; the fallback list comes from the live model list, cached 12 h per key (hashed) in the `ai`
  DataStore. Unlike the web, a failed list is not cached, and no connection (`UnknownHostException`) stops at once
  with its own message.
- The `ai` DataStore (`datastore/ai.preferences_pb`, API key + model) is excluded from cloud backup; device-to-device
  transfer still copies it.
- `CollectionMatcher` ports PHP's `similar_text` exactly (reference values from PHP in its test); it skips the diecast
  being edited.
- Tests fake Gemini with an OkHttp interceptor (`FakeGemini`) instead of MockWebServer; the web's Gemini tests are ported
  in `GeminiClientTest`.

## Notes from phase 3

- Maps: `ui/map/GarageMap` wraps MapLibre's `MapView` (texture mode, so it can sit in a scrolling, rounded card; it
  grabs its own drags from scrolling parents) with OpenFreeMap's `dark` style. MapLibre pulls OkHttp 4.12; it runs fine
  on our 5.5. Tiles show black for a moment while loading.
- `PlaceLookup` / `Nominatim` port `PlaceLookup.php`: own spots (sightings + purchases) within 150 m first, then
  Nominatim with an identifying User-Agent, ≥1.1 s between requests, answers cached in Room (`place_cache`, reverse
  30 days per ~10 m cell, search 7 days; failures never cached, pruned at start-up). Coordinates are sent with
  `Locale.ROOT` / plain decimals — the Indonesian locale would write commas.
- `Exploration.build` ports `ExploreController` (grouping by lower-cased name or ~100 m cell, spelling typed first wins a
  tie, timeline of 30 days); `ExplorationTest` and `PlaceLookupTest` port the web's `ExploreTest`.
- GPS: `DeviceLocation` (fused provider, high accuracy, 15 s, 30 s max age). Permission is asked when the picker opens
  without a point or on "Lokasi saya"; approximate is accepted. Emulator: `adb emu geo fix <lng> <lat>`.
- Sightings are saved at once, apart from the form (like the web); a point older than 3 hours is dropped from a new
  sighting (web: `freshCoordinates`). The "lokasi berburu" (last picked place) lives in `UserPrefs` and pre-fills a new
  form without making it dirty.
- `MigrationTest` builds a database of every older version from `app/schemas/...` and opens it with the migrations;
  bump its range for every new version.

## Notes from phase 4

- `data/dex` ports `app/Services/Dex/*`: `WikiTableParser`, `WikiCatalog` (MediaWiki API, 400 ms pacing, 3 attempts
  2 s apart on dropped connections), `DexMatcher` (scores and thresholds as the web: auto at ≥90 with a 20-point
  margin, across years with a year fix), `DexProgress`, `DexSync`, `DexRepository`. `DexTest` ports the web's
  `DexTest` with its wiki fixtures (`FakeWiki`).
- Thumbnails: `DexWork.downloadImages` (unique work, `UNMETERED` or `CONNECTED` per the `dex_cellular` pref,
  exponential back-off from 30 s) fetches 12 at a time into `files/dex/<catalogId>/`; started after a sync, at
  start-up when some are missing, and when the setting changes. 5xx/429/IO errors retry, other failures drop the photo
  until the next sync. `ImageWork.Retrying` (enqueued with `runAttemptCount > 0`) is the back-off, not "waiting for
  Wi-Fi". Photo URLs are looked up during the sync (50 per API call), so the missing count grows while it runs.
- A year's sync catches any exception and reports it per year; otherwise a bug fails the worker and the screen just
  goes back to idle.
- A saved diecast's dex link is changed only through `DexRepository.link` (panel or "Cocokkan Dex"); saving the form
  keeps it, and re-runs auto-linking only when the identity fields (name, brand, series, number, year, rarity) changed.
  A new diecast takes `DiecastForm.catalogItemId` as a confirmed link.
- Kotlin gotcha: `/*` inside a KDoc comment (e.g. a path like `Services/Dex/*`) opens a nested comment and silently
  comments out the rest of the file — KSP then reports `[MissingType]` on `AppDatabase`.
- Own dexes: `DexCustom` ports `CatalogImport.php` + `CustomDexController.php` (numbering: same number = variant, no
  number = next in order, exact duplicates skipped; wiki lists refuse manual edits). Differences from the web, chosen
  with the user or found while testing: entries can be edited (`edit` keeps links; another number makes it a variant
  of that entry), the AI's rows are reviewed first (`ChecklistReader` only reads; `DexCustom.known` flags rows already
  there), and "Dari koleksiku" links a number-less diecast to an entry of the same model already on the list (or adds
  it as another colour) instead of doubling it. Entry photos live in `files/dex/<catalog id>/` (moved out of
  `photos-tmp`), so deleting a dex removes them. `CustomDexTest` ports the web's `CustomDexTest`.
- `SheetReader` reads .xlsx itself (zip + XmlPullParser: first tab via workbook rels, shared/rich/inline strings,
  skipped cells, `x:` prefixes) and CSV (`,` `;` or tab from the first line, quotes, BOM) — Apache POI would weigh more
  than the app. Fixtures `dex-list-{inline,shared}.xlsx` come from OpenSpout: `php tools/make-dex-xlsx-fixture.php`.
- Screenshots for the AI are decoded at 2400 px (`PhotoStore.screenshot`, web: `SCREENSHOT_MAX_EDGE`) and not kept.
- Sheets cover the snackbar: messages for a sheet that stays open are shown inside it (`lastAdded`, `importNote`).
- Regex gotcha: Android's regex engine is ICU, the unit tests run the JVM's. `(?U)` (UNICODE_CHARACTER_CLASS) passes
  every Robolectric test but throws `PatternSyntaxException` on a device — use explicit classes (`[\s\p{Z}]`). Check
  new patterns on the emulator.

## Decisions (made with the user)

| Topic | Decision |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose, Material 3 with the "Garage" dark theme (below). UI text in **Bahasa Indonesia**, money in **Rupiah** (`id-ID`, no decimals). |
| Data | Room (SQLite) on the device. Photos in app-private storage. |
| Network | OkHttp + kotlinx.serialization (Gemini, Hot Wheels/Matchbox wikis, Nominatim). Coil for images. |
| Location / map | Fused Location Provider; **MapLibre** with **OpenFreeMap**'s dark style (OpenStreetMap data, no API key). osmdroid was dropped: archived Nov 2024. Nominatim for place names. |
| Navigation | Bottom bar: Koleksi · Peta · Dex (Dashboard joins in phase 5); other screens open from the ⋮ menu. |
| Dex | Two stages: 4a wiki sync, matching, progress, "Cocokkan Dex", form panel, add from Dex, scan suggestion; 4b own lists (manual, Excel/CSV, AI from screenshots, from the collection). The Dex starts from scratch on the phone (no web backup change). Thumbnails download in the background, Wi-Fi only by default, user can allow mobile data. Own dexes: the AI's checklist rows are reviewed before they're added; entries can be edited (both unlike the web). |
| Location filling | GPS only on request (map picker); the picked place is remembered 3 hours and pre-fills new diecasts and sightings. |
| Background work | WorkManager (Dex sync, thumbnail downloads, bulk scan queue). |
| Settings | DataStore (Gemini/Claude API key, model). |
| AI provider | Gemini only for now (phase 2); Claude as in the web may follow. Duplicate warning as on the web (no "+1" shortcut). |
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
- Dex links are **not** in v1, and won't be: the user chose to build the Dex from scratch on the phone (sync +
  auto-linking + "Cocokkan Dex").

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
- `emulator` is not on the Git Bash `PATH`: use `C:/Users/user/AppData/Local/Android/Sdk/emulator/emulator.exe`.
- The laptop has 15 GB RAM and runs low with Gradle + Kotlin daemons + the emulator; the emulator has been killed
  for low memory. Check free memory first, stop the daemons (`./gradlew --stop`) before starting it, and **ask the
  user before (re)starting the emulator**. While it runs, build lean: `./gradlew --no-daemon
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m" -Pkotlin.compiler.execution.strategy=in-process …`.
- Test files for the emulator: `adb push` into `/sdcard/Download` shows in the file picker; for the photo picker push
  into `/sdcard/Pictures` and run `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/<file>`.

## Working with the user

- Reply in **Bahasa Indonesia** (they sometimes write English). Their keyboard often drops the letter "b"
  ("anyak" = banyak).
- They like to discuss bigger features before building, and to pick between clear options.
- **Git:** they run every git command themselves — provide the commands and professional English
  Conventional Commit messages, split per feature. Never commit, stage or `git rm` for them.
- System setup: default to giving steps; when they ask you to do it (e.g. environment variables), back up
  what you change first.
