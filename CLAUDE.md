# Diecast Tracker — Android

Native Android rewrite of the **Diecast Tracker** web app, so it runs on the phone only when opened —
no server, no hosting. Single user, personal collection.

The web app at `C:\laragon\www\diecast-tracker` (Laravel 13 + Inertia/React, MySQL) is the **reference for
behaviour and logic**. It keeps working on the laptop until the Android app catches up. Port logic from it
rather than re-inventing; its feature tests (`tests/Feature/*`) describe the expected behaviour and contain
real-world fixtures.

## Status (2026-11-07)

Every planned phase (1–4, 5a–5e) is committed and pushed to `github.com/caropeboka/diecast-tracker-android`
(`main`) as version 0.5.4. Phase 5 order, agreed with the user: 5a backup/export → 5b dashboard → 5c market value from
screenshots → 5d bulk scan → 5e in-store check. The user runs all git commands themselves; give them the commands and
the commit messages.

**On the phone since 2026-10-31:** release build signed with the user's own key, installed with `adb install` over
USB, the web's backup restored (5 diecasts with photos), a real Gemini key set; the first real scan (a blister card)
read every field right. The user's feedback from the phone became 0.6.0–0.6.7 (see "After the phases"): AI progress
as a log of steps, photos first and the AI on request, the centre button and a new bottom bar, "Foto studio", a new
launcher icon, screen transitions. Then the **3D garage** (see "3D garage"): G0 (0.6.7–0.6.8) and G1 free placement
(**0.7.0**, Room v7), then 0.7.1 (a map-like camera) and 0.7.2 (diecasts move instead of jumping, the garage and
the dashboard's diecasts grow into their pages, the ＋ menu as a FAB menu) and 0.7.3 (every screen opened by a tap
grows out of what was tapped, a new "Cek di toko" page, decorations on the black shelf: Room v8; on the phone with the
two figures placed). **0.8.0**: more free AI providers besides Gemini with automatic switching (see "AI providers");
covered by tests, not yet tried with real Groq/Mistral/OpenRouter keys. **0.9.0**: English by default with
Indonesian and Simplified Chinese, a Settings screen (language, currency with daily rates, date format) and a
first-run tour (see "Languages, money, dates and the tour"); on the phone since 2026-11-01 (all 8 tour steps and a
switch to Chinese and back checked; currency and date formats not yet tried there). **0.9.1**: fixes from the
user's list after 0.9.0 (see "Fixes from the phone"). **0.10.0**: the features from that list (see "From the user's
list (0.10.0)"): the Dex back to 1989/2000, Mini GT (with Kaido House) and Tomica lists, lists looked up on the web by
the AI, more AI providers, "Cek di toko" shooting many photos in a row, "Tanya AI" in the form, price checks beyond
Tokopedia/Shopee, and regions on the map. 0.9.1 and 0.10.0 are built and covered by tests (250 pass, no lint errors),
on the phone since 2026-11-07 (it starts, the 64 diecasts intact) but **its new pieces are not tried there yet**.
**0.11.0** (the user's first look at 0.10.0, see "From the phone after 0.10.0"): every number on Garasi opens Koleksi
filtered, the map's stats and history days open, the Dex tab per brand with a page each, Tomica Premium per year;
covered by tests (252 pass), on the phone since 2026-11-07 (it starts, the 64 diecasts intact), not tried there yet. The collection's brands (read from the phone 2026-11-07): Hot Wheels,
Matchbox, Tomica (Premium and the regular lineup), Bburago, Maisto, Moto Cross, Roll Speed. The
phone got 0.7.0 and 0.7.1 on 2026-11-01 (migration fine, data intact, "Normal" renders right on its Mali-G57); the
work since 0.9.0 is not committed yet. Next: try the new pieces on the phone (Tanya AI, the web estimate and the web list with
the real Gemini key; the regions lookup; the store check's batch; the new wikis' sync); the user adds the other
providers' keys and tries a scan with Gemini's quota used up (or Gemini switched off); the user tries the garage on the
phone (placing,
smoothness, "Hemat" not crashing, pulling a diecast out, tap accuracy); then G2 (realism: materials, shadows, room,
"where is diecast X?") and G3 (shelf settings), and maybe the Garasi tab opening the 3D garage with the dashboard
behind a "Statistik" button (planned, not done: the garage opens from "Rak 3D" on the dashboard). Covered by tests
only before the phone: the scan's Dex suggestion, the checklist screenshots read by the AI (including
its review sheet), a price check's successful reading, a bulk scan photo the AI read successfully (its review card,
"Simpan" and "Simpan semua"), and a store check photo the AI read successfully (its verdict and matches; logging it
with the check's photo). The verdict cards, +1, "Dapat", "Catat temuan", the typed search, the camera, a failed check
and "Tambah ke koleksi" from it, and the launcher shortcut were tried on the emulator.

Phase 1 delivered: collection grid/list (search incl. tags, filters status/brand/rarity/tag, 7 sorts, layout and
sort remembered in DataStore), add/edit/delete with up to 8 photos (camera via FileProvider + photo picker, first =
cover), tags, the four statuses, read-only sightings list, web backup import (merge/replace, one transaction).

Phase 2 delivered: AI scan with **Gemini only** (the user's choice; Claude can be added later). The Scan button
opens the camera straight away (`DiecastEditRoute(scan = true)`; without a key it leads to the AI settings); since
0.6.0 the AI reads only on "Pindai dengan AI" (up to 4 photos), see "After the phases". AI-filled fields get ✦ and a brand border until edited; confidence note; duplicate warning like the web
(tap opens the existing diecast). AI settings screen: key shown as `••••1234`, test key → live model list, remove key.
The success path was verified with tests only — the emulator run used a fake key (error path).

Phase 3 delivered: bottom navigation (Koleksi · Peta); "Catatan temuan" on every saved diecast (add with place, date,
price, notes, photo in a bottom sheet; delete with confirm; "Termurah" badge); location fields with a map picker for
"Beli di", "Ditemui di" and sightings (fixed centre pin, own places within 150 m before OpenStreetMap, search, recent
places, "Lokasi saya" via GPS); the last picked place is remembered 3 hours and pre-fills new diecasts and sightings;
"Peta jelajah" (type/range filters, 4 stats, markers filled = bought / ring = only seen, sized by count, place details,
places list, timeline). Room schema v2 (`place_cache`, with migration).

Phase 4a delivered: a Dex tab (Koleksi · Peta · Dex) — sync Hot Wheels Mainline (2015+; 1989+ since 0.10.0) / Matchbox years from the
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

Phase 5a delivered: backup from the phone — one .zip in the web's format plus the Dex (links, own dexes with photos,
wiki lists as text), saved through the system file picker ("Simpan": Download, Drive…) or the share sheet
("Bagikan"); "Data & backup" shows the last backup; a reminder card on Koleksi when the collection was never backed up
or not in 30 days ("Nanti" = 7 days). "Pulihkan dari backup" takes phone and web backups (phone backups restore the
Dex too). "Export Excel" (.xlsx, the web's columns; save or share). No schema change.

Phase 5b delivered: the "Garasi" tab (bottom bar Koleksi · Garasi · Peta · Dex; the app still opens on Koleksi) with
everything the web's dashboard has: collection value with gain against purchase price and a photo collage, 4 stat
tiles, Dex lists furthest along, most valuable, data completeness ("Lengkapi →" opens Koleksi filtered), per brand
(count/value), rarity, makes, release years, scales, price gainers, wishlist, recently added. Taps open the diecast,
the dex list or the collection filtered to match. Koleksi got a "Belum ada …" filter (photo, purchase price, market
value, storage location, tags). No schema change.

Phase 5c delivered: "Cek harga pasaran" on every saved diecast's form — search keywords (prefilled like the web),
Tokopedia/Shopee buttons, up to 6 screenshots from the gallery, the AI reads every listing, the app works out median,
fair range, cheapest/dearest, suggested price (median to Rp 500), accuracy with reasons and tips, the wishlist target
against the median; "Pakai Rp X sebagai harga pasaran" saves it at once (source "price_check", hint under the field);
older checks (5 in view) and delete. Screenshots are not kept (the user's choice), only what was read. Price checks
travel in phone backups. Room schema v4 (`price_checks`, with migration).

Phase 5d delivered: "Scan massal" (⋮ menu in Koleksi; a banner in Koleksi and Garasi while the queue holds photos) —
many photos from the gallery or a back-to-back camera (it reopens after each shot until Back), one diecast each; the
AI reads them one by one **in the background** (WorkManager, also with the app closed; waits out quota/busy/no
connection; Jeda/Lanjutkan); review cards with confidence, "Mirip koleksimu" and its matches, quick fields (name,
brand, number, series, rarity, price), Simpan / Lengkap (the full form, prefilled) / Buang, failed ones Coba lagi /
Isi manual; shared fields (save as Dimiliki/Wishlist/Ditemui, date, place with the map, storage, tags); "Simpan semua
yang yakin". Room schema v5 (`scan_items`, with migration).

Phase 5e delivered: "Cek di toko" — from the launcher shortcut (long-press the app icon), the bottom bar's centre
button (a "Cek" button in Koleksi before 0.6.0), or the ⋮ menu; the camera opens straight away when the AI is on. The AI reads the photo at once
and the collection is searched: Sudah punya / Ada di wishlist / Pernah kamu temui / Belum punya / Tidak terbaca (with
"Coba lagi"), each with its icon and colour, the store field (map picker), and the last 10 checks (older ones folded
since 0.7.3, see "After the phases").
Matches get +1 (owned) or "Dapat" (wishlist/spotted → owned today, "Beli di" = this store) and 👁 "Catat temuan" with
"Harga di rak"; a new one can be logged as a "Ditemui" diecast (then "Jadi beli — Dapat"), added through the form
("Tambah ke koleksi" / "Tambah sebagai item terpisah", "Wishlist") with the photo and the reading. "Atau ketik namanya
(tanpa AI)" searches the collection, Ditemui included, without the AI or a connection. Room schema v6
(`scan_items.source`, with migration).

## Build, test, run

- Toolchain: Gradle 9.8.1 (wrapper), AGP 9.4.1 with built-in Kotlin, Kotlin 2.4.21, KSP 2.3.12, Compose BOM
  2026.09.00, Room 2.8.5, Coil 3, Navigation Compose 2.10 (type-safe routes), OkHttp 5.5, MapLibre Android 13.6.1,
  Play Services Location 21.3, WorkManager 2.11, ML Kit subject segmentation 16.0.0-beta1 (Play services),
  SceneView 4.57.0 (Filament). compileSdk 37, targetSdk 36, minSdk 29. Versions live in `gradle/libs.versions.toml`.
- `./gradlew :app:testDebugUnitTest` — JVM + Robolectric tests (Room in memory, native graphics). The test JVM needs
  `--add-exports java.base/jdk.internal.access` on JBR 25; already set in `app/build.gradle.kts`.
- `./gradlew :app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease` (R8, ~30 MB; Filament added ~10 MB). MapLibre's native code is
  ~13 MB per ABI: release ships **arm64 only** (the phone), debug adds x86_64 for the emulator — so test a release
  build on the emulator only by temporarily adding x86_64 to `abiFilters`. Release is signed with
  `signing.properties` (git-ignored) if present, otherwise with this laptop's debug key
  (`~/.android/debug.keystore`) — changing the key later means uninstalling the app on the phone (data loss).
  The phone's app is signed with the user's own key: `C:/Users/user/keystores/diecast-tracker.jks` (PKCS12, alias
  `diecast-tracker`, RSA 4096, until 2126; password only in `signing.properties`; the user keeps a backup of both).
  Never build a release for the phone without `signing.properties`, and never replace the keystore.
- Updating the phone: bump `versionCode`/`versionName`, `:app:assembleRelease`, then
  `adb install -r app/build/outputs/apk/release/app-release.apk` (keeps the data). Use `adb`, not a copied APK: since
  2026-09-30 certified devices in Indonesia only install apps from verified developers, and `adb` is exempt (a copied
  APK would need Android's "advanced flow": developer options, a restart, a 24-hour wait).
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

## Notes from phase 5

- Backup zip (`BackupExporter`): the web's layout (`diecasts.json` v1, `photos/`, `koleksi.xlsx`) plus `dex.json`
  (`DexBackup`: every catalog with its items, ids only meaningful inside the backup) and `dex/` (own dexes' photos;
  wiki thumbnails are left out and download again). Records carry phone-only keys the web's restore ignores (it keeps
  only validated fields): `market_value_source`, `market_value_updated_at`, `created_at`, `updated_at`, `dex_item`,
  `dex_match`, sightings' `created_at`. **Never write `catalog_item_id`**: the web validates it with `exists` against
  its own Dex. Checked with the web's own `CollectionBackup::restore` (and its Excel import of `koleksi.xlsx`) on an
  in-memory SQLite, like `tools/make-web-backup-fixture.php`; the web sets the market value source to manual.
- Restore (`BackupImporter` + `DexRestorer`): the Dex goes back first, then the diecasts with their links mapped.
  Wiki years are matched by source + year (items by number + variant) and kept as they are; own dexes by name (items
  by number + name + variant label; missing ones are added, as the next variant if the number exists). "Replace" also
  deletes the own dexes (their folders after the commit) but keeps the phone's wiki years. A link whose entry didn't
  come back is cleared and auto-linked again; "ignored" stays. Catalog ids are AUTOINCREMENT, so a new dex folder
  never reuses a deleted one's.
- Export runs in the app scope like the import. "Simpan" writes into a `CreateDocument` URI (a failed write deletes the
  document); "Bagikan" writes into `cache/exports` (FileProvider path `exports`; older than an hour removed on the next
  share, older than a day at start-up). Photos are STORED in the zip (already JPEG). Both count as a backup for the
  reminder (`UserPrefs.backupTimes`, `BackupReminder`).
- `XlsxWriter` writes a minimal .xlsx itself (inline strings, numbers, frozen bold header; closes its own zip but not
  the stream, so it nests inside the backup zip); `CollectionSheet` has the web's columns and labels ("Carded").
- Emulator: if everything stays black (Home too) while `uiautomator dump` shows the UI, the host GPU renderer failed —
  restart it with `-gpu swiftshader_indirect`. `uiautomator` dumps escape `&` as `&amp;`.
- Dashboard: `Dashboard.build` (pure, like `Exploration.build`) ports `DashboardController` plus the sums in
  `dashboard.tsx` from one live query (`DiecastDao.observeDashboard`, `DashboardRow` = the card row + make, scale,
  storage, created_at, has_tags) and the Dex summaries. Owned diecasts only, except wishlist/spotted counts, showcase
  and recent (owned + sold). Brands and makes group regardless of case (the web's MySQL collation), first spelling
  wins. Differences from the web: the gain % is measured against the purchase price of the diecasts that have both
  prices (the web divides by total value minus gain); the collage stays low on a phone (6 → 3×2, 3–5 → one row of 3,
  2 → side by side, 1 → wide). `DashboardTest` ports the web's Dex test and covers the sums.
- Opening Koleksi filtered from another tab: the collection stays at the bottom of the back stack, and its filters live
  in `CollectionViewModel`'s own `SavedStateHandle` — **not** the back stack entry's `savedStateHandle` (a different
  instance; writing there does nothing). `Navigation.openCollection` gets that view model with
  `ViewModelProvider.create(entry, CollectionViewModel.Factory, entry.defaultViewModelCreationExtras)` and calls
  `show(CollectionLink)`; a link's sort is saved as the remembered sort.
- Price checks: `data/price` ports `PriceChecker.php` (prompt, schema, `searchQuery`) and `PriceAnalyzer.php` exactly
  (dedupe on title/shop/price, Tukey fences from 5 listings, PHP-style percentiles, half-up rounding — prices are
  positive so `roundToLong` matches PHP's `round`, scoring texts word for word). `PriceCheckEntity` keeps listings,
  stats, reasons and tips as JSON through `Converters`, plus how many screenshots were read. "Pakai" writes the market
  value straight to the database (`PriceCheckDao.setMarketValue`) and `DiecastEditViewModel.marketValueApplied` puts
  it in the form and its baseline, so it doesn't count as an unsaved change; saving later keeps the source because
  the value didn't change. The panel has its own view model (`viewModel(key = "price-check-<id>")`); picked screenshot
  URIs live in its saved state and are decoded (2400 px) only when the check runs. Backups carry them as
  `price_checks` per record. `PriceAnalyzerTest` / `PriceCheckTest` port the web's tests.
- Bulk scan: `data/scan/ScanQueue` + `ScanWork` (unique work `bulk-scan`, `KEEP`, network required, linear back-off
  from 60 s). The worker reads the oldest pending item in a loop; `AiRetryLaterException` (quota/busy) and the new
  `AiOfflineException` (no connection, thrown by `GeminiClient` instead of a plain `AiException`) leave it pending and
  return `retry`; other errors mark it failed with the reason. Paused (`UserPrefs.bulkScanPaused`) or no key: the
  worker ends at once. The app starts it at launch and whenever the key becomes set (`scanner.enabled` collected in
  `DiecastTrackerApp`), and the screen after adding/retrying/resuming. `ScanWork.observe` shows reading / waiting
  (with `nextScheduleTimeMillis` as a countdown) / queued.
- A queued photo stays a **pending** photo (`photos-tmp`) until its diecast is saved: `PhotoStore.housekeeping` takes
  a `waiting` set (the queue's files) that it neither promotes nor prunes. `ScanFields` is `@Serializable` and stored
  as JSON in `scan_items.fields`. Saving builds a `DiecastForm` with `ui/scan/queuedForm` (AI reading → quick edits →
  shared defaults; Ditemui gets its first sighting from place/date/price, Wishlist's price is the target price) so the
  rules and validation are the form's; `isSafe` = done, named, not low confidence, no matches.
- "Lengkap"/"Isi manual" open `DiecastEditRoute(scanItem = id)`: the form starts from the photo and the reading (as its
  baseline, so leaving asks nothing), never deletes the queue's photo (`queue_photo` in its saved state), hides
  "Simpan & tambah lagi", and removes the queue entry after saving. `ScanQueueTest` ports the web's bulk tests.
- The emulator was killed for low memory once during this phase (Claude Code reaps background shells under memory
  pressure): ask before starting it again, and keep builds lean while it runs.
- Store checks (`data/scan/StoreCheck`, web: `CheckController`) share `scan_items` with the bulk queue, told apart by
  `source` (`bulk`/`check`, like the web); every bulk query filters on `source = 'bulk'`. A check is read at once (not
  by the worker); its photo stays pending until it is logged (a sighting's photo on a match, or the new Ditemui
  diecast's photo) or added through the form (`DiecastEditRoute(scanItem)`, as from the queue), which removes the row.
  Checks older than a day go with their photos at start-up (`StoreCheck.prune`, before the housekeeping).
- `DiecastRepository.addOne` (+1, capped at `MAX_QUANTITY`) and `acquire` (owned, bought today; unlike the web it also
  takes the store as "Beli di" unless one is set, with its point only while fresh). Sightings from a check keep the
  point only while fresh, like the web's `freshCoordinates`.
- The check screen keeps its results in its saved state (`CheckResult`, JSON) so they survive the process being killed
  while the camera is open; the diecasts they point at are read live by id (`DiecastRepository.observeItems`), so +1,
  Dapat and sightings show at once. Back from the form, `refresh()` marks photo checks whose row is gone as "Sudah
  disimpan lewat form". The verdict leaves out the Ditemui diecast the check itself created. A typed search opens the
  form with `DiecastEditRoute(name)`.
- Launcher shortcut: `res/xml/shortcuts.xml` (action `io.github.caropeboka.diecasttracker.CHECK`, labels must be string
  resources, adaptive icon `mipmap/ic_shortcut_check`). Static shortcuts start a fresh task, so `MainActivity` sends
  the request (only without saved state) through a channel that `DiecastTrackerRoot` turns into `CheckRoute`.
- Android unit test names (backticks) can't contain `:` — use a dash. `StoreCheckTest` ports the web's check tests.

## After the phases (0.6.0–0.7.3, from the user's feedback on the phone)

- **AI progress as a log of steps** (the user: a spinner alone doesn't say whether it's stuck; like Claude Code's
  activity line). `GeminiClient.extract(…, onEvent)` reports `AiEvent`s: `ListingModels` (fallback list not cached),
  `Sending` (model, bytes, fallback index), `Uploading` (the body is a `ProgressBody` written and flushed in 32 KB
  pieces), `Waiting` (all sent), `Missed` (model + short Indonesian reason; `retrying` for the chosen model's quick
  retry), `Answered`. Events also come from OkHttp's threads. `ui/components/AiProgressLog` turns them into
  `AiProgress` steps in a `MutableStateFlow` (✓ done, ⚠ missed, the running one with its seconds and, while
  uploading, a real fraction) and `AiProgressView` shows the last 5 with a bar and a hint ("Biasanya 10–30 detik.").
  Callers add their own steps before and after ("Menyiapkan 2 foto", "Mencocokkan dengan koleksi"). Used by the form's
  scan (on the photo), the price check, the store check (and its retry) and the AI checklist; the bulk scan runs in
  the background and keeps its card text.
- **Photos first, the AI on request** (the user: shoot every side, then scan, for more to go on). A new diecast's
  camera reopens after each shot until Back or 8 photos (`onCameraResult`, like the bulk scan); nothing is read
  automatically any more. The form shows `ShotGuide` (Blister / Box / Loose chips that set the packaging field, and the
  sides ticked off by photo count: depan + belakang, or depan, samping, atas, bawah) and a big "Pindai dengan AI (n
  foto)" (only the first 4 are sent: `MAX_SCAN_PHOTOS`); after a scan it is "Pindai ulang dengan AI".
- **One centre button in the bottom bar** (Koleksi · Garasi · ＋ · Peta · Dex, the user's idea): Cek di toko, Isi
  manual, Scan dengan AI (last, nearest the thumb). The Koleksi FABs are gone. `CreateActions` comes from
  `DiecastTrackerRoot` through `LocalCreateActions`, so every tab's `MainNavigationBar` has it. Since 0.7.2 a FAB menu
  instead of a bottom sheet (the user found the sheet clumsy and chose Material's FAB menu): `CreateMenuOverlay`
  is drawn by the root over every screen and its bar; the button reports its centre (`CreateMenu.anchor`, root
  coordinates), the screen dims, a brand × (the ＋ turned 45°) sits on the button, three pills of one width rise
  above it, staggered, Scan dengan AI nearest. Back, the scrim or × close it; a choice counts once (`menu.open` is
  checked), which replaced the bar's `dropUnlessResumed`.
- Release builds: lint and R8 once failed together while memory was short; running the build again passed.
- **Foto studio** (0.6.1–0.6.4; the user showed a clean studio shot of a card as the goal). On the form's big photo:
  the photo shown becomes a new cover on white at 4:5 (the original stays; with 8 photos it asks to delete one
  first), all on the phone (`data/studio`). Loose: ML Kit subject segmentation (`MlKitCutter`, model fetched once by
  Play services, also ahead through the manifest's `com.google.mlkit.vision.DEPENDENCIES`) and `StudioComposer`
  (edges firmed, specks dropped, never enlarged, no rotation so wheels don't float). Carded/Box open `CardCropDialog`
  (`ui/edit/CardCrop.kt`) instead, as the segmenter takes a card's printed picture for the subject: step 1 drag four
  corners (a guess from `guessCorners`, a loupe under the finger), step 2 `CardCutout` squares it up, rounds a card's
  corners (boxes stay sharp), finds the hang hole itself, and taps erase or restore background (Urungkan; it refuses a
  tap that would spread over the card's print). "Mobil loose? Potong otomatis" switches to the segmenter. Progress
  goes through `AiProgressLog` ("Diproses di HP"). Studio copies are listed in the form's saved state
  (`studioPhotos`): they don't tick off a side in `ShotGuide`, and the AI gets the real sides first. The AI choice
  (generative "Nano Banana" editing) was left out: it may redraw printed details. **R8 gotcha:** ML Kit makes its
  `ComponentRegistrar`s by reflection; full mode dropped their constructors (release only: "Attempt to read from
  field …"), hence the keep rule in `proguard-rules.pro`. `StudioPhotoTest` covers the composer and cutout.
- **Bottom bar like myBCA's** (0.6.5, the user's screenshot): dark bar with rounded top corners and a thin border, a
  big orange ＋ circle half above it in a bar-coloured ring ("Tambah" under it; the raised part is tappable too), the
  active tab an orange icon and a bold white label, no pill. It covers a little of the content behind it, like myBCA.
- **Launcher icon** (0.6.6): a five-spoke wheel on an orange gradient (`ic_launcher_background/foreground`), plus a
  monochrome layer for themed icons. The "Cek di toko" shortcut icon is unchanged.
- **Screen transitions** (0.6.6–0.6.7; the user: the old 0.7 s fades felt like a PowerPoint slideshow), in
  `ui/ScreenTransitions.kt`: tabs cross-fade in place (220 ms, the bar stays put); deeper screens move like Android's
  own (a tenth of the width with a quick fade, emphasized easing), back mirrors it; a diecast opened from Koleksi
  grows out of its card and shrinks back (shared bounds: `sharedDiecastCard` / `sharedDiecastPage`, grid and list);
  the back gesture scrubs a shrink and cancels if the finger slides back. The form's loading spinner shows only after
  400 ms, so opening a diecast doesn't flash it. Since 0.7.2 (the user: from Garasi it was still "PPT basic") the
  same from the dashboard (`CARDS` in `ScreenTransitions`): a diecast can show in several of its panels at once but
  only one element per key may be on a screen, so `Opener` (`DashboardParts.kt`) remembers which one was tapped
  (place + id, saveable) and only that one carries `sharedDiecastCard`. The 3D garage grows out of the dashboard's
  "Rak 3D" pill (`sharedGarage`) and the diecast in hand into its page (see "3D garage"). Since 0.7.3 (the user:
  Back from the ＋ menu's screens was still "PPT basic") **every screen opened by a tap grows out of what was tapped**
  and shrinks back into it, a container transform (`ui/ScreenReveal.kt`): `noteTaps` on the root notes every finger
  going down (Initial pass, nothing consumed); `revealSource()` marks an element with its colour and where to shrink
  back to (the ＋ menu's pills shrink into the ＋ button, Koleksi's ⋮ into itself); `trackReveals`, a destination
  listener (it runs inside `navigate()`, before the transition is chosen), links a pushed entry to the screen under it
  when a tap came within 10 s — the marked element, else a 56 dp blob at the finger — in `Reveals.links`, dropped when
  the entry is destroyed. `RevealedScreen` (around every `screen<…>` in `Navigation`) wraps the new screen in
  `sharedBounds("reveal-<entry id>")`, the screen under it holds an invisible `StandIn` with the same key at the spot,
  plus a 0.32 scrim; 380 ms (`CONTAINER`). Tabs and the card/garage transforms keep theirs; a screen opened without a
  tap (the launcher shortcut) still slides. A pop lands on an entry that has been on top before (lint rejects
  `currentBackStack`: a restricted API).
- **Cek di toko, redesigned** (0.7.3; the user asked whether the page looked bad: it read like a form, the camera
  wasn't under the thumb, the empty state was blank, rainbow cards stacked up, too many buttons of equal weight). The
  store is a one-line bar ("Di toko mana? Ketuk untuk isi" opens the map field); before any check a big `CameraHero`;
  after one, "Foto diecast lain" in a bottom bar (`CameraBar`). The newest check is a full `ResultCard` (a band and a
  border in the verdict's colour from the theme: owned TH green, wishlist Exclusive pink, spotted Chase violet, new
  brand orange, unreadable loss red; the photo; one main action as a full-width button, the others outlined); older
  ones fold into a line (`FoldedResult`, tap to unfold; which are open is saveable).

## AI providers (0.8.0)

The user wanted more free AIs than Gemini, with an automatic switch when Gemini's free quota runs out. Researched
(2026-11): Groq (free, fast, vision model `qwen/qwen3.8-27b`, 3 images, ~1K requests/day), Mistral (free "Experiment"
plan, phone verification, data may be used for training; vision in Small/Medium/Large 3, Ministral 3), OpenRouter
(free models: 50/day, 1000/day after a one-time $10; the `openrouter/free` router), NVIDIA NIM (trial) and Cloudflare
Workers AI (10k neurons/day) through the custom slot (their own providers since 0.10.0); Cerebras was left out (no free tier since 2026-07, text only).
The user chose Groq, Mistral, OpenRouter and a free "OpenAI-compatible" slot, switching on **quota, busy or timeout**
(also on a refused key), never when the AI answered but couldn't read the photo.

- `data/ai/AiVision` is what every caller uses (scanner, price check, checklist reader; the store check and the bulk
  scan through the scanner): the providers ready to use (`AiSetup.chain`: switched on, model set, a key — the custom
  one needs a URL instead) in the user's order; on any `AiException` the same request goes to the next one, with
  `AiEvent.Switching`/`Provider` for the log. All failing: one provider rethrows its own error; several give
  "Semua AI yang diatur gagal:" with every reason, as `AiOfflineException` only when all were offline, as
  `AiRetryLaterException` when any only needs time (so the bulk scan waits), else a plain `AiException`.
- **Rests:** an `AiRetryLaterException` carrying `restUntil` (quota) sets that provider aside (`ProviderRests`, stored
  as `<id>_rest_until` in the `ai` DataStore): resting ones go last, not out (`AiEvent.Resting` in the log when
  others are ready), and a success clears the rest. Gemini: every 429 body says "PerDay" → next midnight
  America/Los_Angeles, else the longest `retryDelay`, else 60 s. OpenAI-style: `retry-after` (s), OpenRouter's
  `x-ratelimit-reset` (epoch ms), Groq's "try again in 1m26.4s", "per day/daily/RPD" → next UTC midnight, else 60 s.
- `OpenAiClient`: chat completions with the photos as `image_url` data URLs before the text, the JSON Schema in the
  system prompt and `response_format: json_object` (asked again without it on a 400 that names `response_format`);
  the answer's JSON is found past `<think>` blocks and code fences (`jsonObjectIn`). Per-provider limits in
  `AiProvider` (Groq: `maxImages` 3, `maxRequestBytes` 4 MB with base64's third and 64 KB of prompt counted): extra
  photos are left off the end (`AiEvent.Trimmed`). OpenRouter's in-200 `error` becomes its code; `X-Title` is sent.
  Model lists for "Tes key": OpenRouter `openrouter/free` + ids ending `:free` with "image" in
  `architecture.input_modalities`; Mistral `capabilities.vision`; the others everything but audio/guard/embedding.
  `AiHttp.kt` holds what both clients share (`fetch`, `ProgressBody`, `withTimeout`).
- Settings (`AiSettings`, the same `ai` DataStore, still out of cloud backup): `<id>_api_key`, `<id>_model`,
  `<id>_on`, `provider_order`, `custom_base_url`, `custom_name`; Gemini keeps its old key names, so the saved key
  stayed. The screen: one card per provider in order (number, status "Aktif · ••••1234" / "Belum diatur" /
  "Dimatikan" / "Kuota habis sampai …", ↑/↓, tap to open: a note about its free tier, "Pakai …" switch, key, Tes key,
  "Buat key di … ↗", model; the custom one also a name and a base URL; Cloudflare an account id). A
  provider that isn't used may stay half filled; one in use needs a model (and the custom one a valid URL).
- Who read it: `ScanFields.readBy` (the label, only when it wasn't Gemini) shows in the form ("Field bertanda ✦ diisi
  oleh Groq.") and as a pill on the bulk scan's cards. `AiProgressLog` names the provider in every step ("Mengirim
  ke Groq …", "Pindah ke Groq", "Gemini dilewati dulu: kuotanya habis sampai besok 15.00").
- Tests: `FakeGemini` also answers the OpenAI-style hosts (target = host + path, replies in turn, response headers,
  `bearers`); `FakeGemini.vision()` builds the app's `AiVision` for the callers' tests. `AiVisionTest` covers the
  switch, the rests, the request shape, the JSON-mode retry, the model lists and the custom server.

## Languages, money, dates and the tour (0.9.0)

The user wanted the app in English by default, Indonesian and Chinese as well, chosen in Settings, an English README,
and a guided first run "like a game's". Their choices: follow the phone's language; Simplified Chinese (zh-CN);
prices in Rupiah, US dollars or yuan, **converted at daily rates** (data stays in Rupiah); a choice of date formats;
the tour as a spotlight on the real screens, starting with the language.

- Strings: `res/values` is English, `values-in` Indonesian, `values-zh-rCN` Chinese, one `strings_<feature>.xml` per
  area in each. **Every new string goes into all three files**, and counts are `<plurals>` (lint's `PluralsCandidate`
  warnings that remain are wordings where a plural changes nothing). Keys are prefixed by area (`dex_`, `garage_`,
  `tour_`…); shared ones (`save`, `cancel`, `delete`…) are in `strings_common.xml`. The Excel export's column headers
  stay Indonesian on purpose (`CollectionSheet`: the web's import reads them).
- Language: `data/locale/AppLanguage.kt`. `AppLocales.set` uses `LocaleManager` on Android 13+ (it recreates the
  activity itself; `res/xml/locales_config.xml` also lists the app in the phone's per-app language settings); before
  that a `locale` SharedPreference read by `AppLocales.wrap` in `attachBaseContext` of the app and the activity, then
  `recreate()`. "Ikuti HP" = no choice; a phone language the app doesn't have shows English. "in" and "id" both mean
  Indonesian. The phone's own language comes from `AppLocales.phone` (`LocaleManager.systemLocales` on 13+): there
  even `Resources.getSystem()` carries the app's choice (found on the phone: "Phone language (now Chinese)").
- Outside composition (view models, repositories, AI errors, price check reasons, import problems) texts come from
  `Texts` (`container.texts`, `texts(R.string.x, …)`, `texts.plural(…)`); in a composable outside `stringResource`'s
  reach (tap handlers, semantics) `rememberTexts()`. Enums carry `@StringRes label`. `Texts.everyLanguage()`
  recognises something written in another language, e.g. a storage location ("Rak putih · baris 3 kolom 2") written
  before the language changed, so taking a diecast off the shelf still clears it.
- Money and dates: `util/Format.kt`. `Formats` (Compose state: `locale`, `currency`, `rates`, `dateStyle`) is set
  from the configuration and `UserPrefs` by `DiecastTrackerApp`/`MainActivity`. `formatMoney`/`formatMoneyCompact`
  convert from Rupiah; money fields take amounts in the shown currency and store `convertToIdr`. Until there's a rate
  prices stay in Rupiah (`Formats.shownCurrency`). `data/money/RateSource`: Frankfurter (ECB) first, open.er-api.com
  as a fallback, rates per USD; refreshed when another currency is in use and the last ones are over 12 h old, kept
  in `UserPrefs` for offline use; "Perbarui kurs" in Settings forces it. `DateStyle`: like the language, yyyy-MM-dd,
  dd-MM-yyyy, dd/MM/yyyy, MM/dd/yyyy. Indonesian groups thousands with dots, English and Chinese with commas.
- Settings (`ui/settings/SettingsScreen.kt`, ⋮ → Pengaturan): language, currency (with the current rates and their
  source), date format, links to the AI settings and "Data & backup", and "Ulangi tur".
- Tour (`ui/Tour.kt`): `TourStep` Welcome (with the language chips) → Koleksi tab → ＋ → Garasi → Peta → Dex → ⋮ →
  Ready (with "Atur AI" when no AI is set up). Pieces register their bounds with `Modifier.tourTarget(LocalTour.current,
  TourTarget.X)` (tabs and ＋ in `MainNavigation`, ⋮ in `CollectionScreen`); `TourOverlay`, drawn by the root over
  everything, dims the screen with a hole (`BlendMode.Clear` in an offscreen layer) that glides between targets, a
  pulsing ring, and a card above or below the target. It swallows taps (a tap on the spot = Next); Back goes a step
  back. It starts on Koleksi while `UserPrefs.tourDone` is false (finished or skipped sets it); the step is saveable,
  so a language change on the first step (which recreates the activity) keeps it.
- Tests: Robolectric tests with Indonesian expectations run with `@Config(…, qualifiers = "in")` and, for number
  formats, `Formats.locale = Locale.forLanguageTag("id")` (reset in `@After`); `testTexts()` / `TestEnvironment.texts`
  in `data/TestSupport.kt`. `TextsTest` covers the three languages and `everyLanguage()`.

## Fixes from the phone (0.9.1)

The user's list after 0.9.0, split with them into fixes (0.9.1) and features (later, see Status).

- **Dex links can always be redone** (the user: a wrong match couldn't be matched again). `DexRepository.search`
  (every word must match name, series, number, toy code or the dex's name; the diecast's brand first, newest year
  first, 60 at most; `LIKE … ESCAPE '\'`) behind `ui/dex/DexPicker.kt` (`DexPickerSheet`). The form's Dex panel has
  "Ganti" on a link, "Bukan ini? Cari sendiri" under suggestions, "Cari di Dex" when nothing matched, and
  "Cocokkan ulang" (`LinkAction.Reset`: link and "not in the Dex" cleared, the suggestions come back) after "Lepas";
  "Cocokkan Dex" has the same search. Tapping a Dex entry card there opens **that entry**
  (`DexCatalogRoute(id, number)`: the dex with the entry's sheet open once; closing it goes back), not the year.
- **Map** (`ExploreScreen`): markers take taps within 18 dp (`queryRenderedFeatures` on a box, nearest wins); a
  place's details are a bottom sheet, from a marker or from the places list (also for places without a point, then
  with a note); "Lokasi saya" (small FAB on the map) asks for permission, shows a blue dot with a halo (own GeoJSON
  source above the markers) and centres on it.
- **Garage**: the details card, cards on the black shelf, the two rows per tier and 1:60/1:62 in the case (see
  "3D garage"). `SCALES` offers 1:62 and 1:60.

## From the user's list (0.10.0)

The features from the same list, each with the option the user picked.

- **Dex before 2015** (Hot Wheels from 1989, Matchbox from 2000): `WikiSource.firstYear`. Hot Wheels 1990–1994 name
  the column "Casting Name"/"Car name" and give a row per colour (the colour becomes the variant label); 1997–1999
  write each row as a `{{List0000White|…}}` template followed by its `|[[Image:…]]` cell (`templateRows` turns them
  into a table). Matchbox 2000 heads its columns "Model"/"Card" with the region after the number ("MB02 (USA)" →
  variant "USA"). `WikiOldYearsTest`.
- **Other brands' Dex** (researched 2026-11; the user picked the wikis plus the AI's web search). Mini GT Wiki:
  "<year> Models" for 2018–2022 only (Code | Name | LHD/RHD | Brand | Photo; KHMG = Kaido House, MGTS = sets) →
  `WikiSource.MiniGt` (`lastYear`; `includes` Kaido House, so `DexMatcher` looks in Mini GT's catalogs for a Kaido
  House diecast; the make stands in for the series; exclusives come from the photo caption, `fileCaption`; KHMG/MGTS
  sort after MGT). Tomica Wiki: "List of Main Lineup Products", No. 1–150 on sale now, ~60 % filled, no photos →
  `WikiSource.Tomica`, a `single` list with `year` null (catalog "Tomica Regular"; `DexDao.catalogFor` uses
  `year IS :year`; `DexWork` syncs it once whatever years it gets; the sync card hides the year pickers). Left out
  after a look: Maisto's wiki (only 2026, mostly without toy numbers, one number over a 32-row assortment, rowspans)
  and Tarmac Works' wiki (galleries of SKU file names; names only on each model's page). For those and any other
  line: **"Cari daftarnya di web (AI)"** in "Isi banyak sekaligus" (`ChecklistReader.search`: `AiVision.chat` with
  Gemini's Google Search, a paragraph plus a ```json block of rows) → the checklist's review sheet, with the AI's
  note, a warning (searched the web or answered from memory) and the sources (`ChecklistReview.web`).
  `WikiSource.scale` is the suggestion's scale (null for Tomica). `WikiBrandsTest`, `CustomDexTest`.
- **AI providers split** (the user picked Cloudflare Workers AI, NVIDIA NIM, Z.ai GLM-4.6V-Flash and OVHcloud without
  a key): own `AiProvider`s instead of examples for the custom slot. Cloudflare's URL takes the account id
  (`ProviderSettings.account`); Z.ai wants plain base64 (`rawBase64`; the client flips between plain and data URL on an
  image error); OVHcloud is anonymous (`needsKey = false`) and off by default (photos only go to a new service once
  the collector switches it on). `OpenAiClient.complete` holds the shared loop (shrinking on 413, the base64 flip, a
  retry hook for the JSON mode).
- **Cek di toko, many photos** ("one by one"): the camera reopens after each shot; each photo becomes a pending
  check row (`StoreCheck.queue`) and `CheckViewModel.readQueue` reads the oldest one at a time (`StoreCheck.retry`),
  each result going on top. `QueuePanel` shows the waiting photos (the one being read framed, with its log);
  failed ones have "Coba lagi", several "Coba lagi semua (n)". Pending results are kept beyond the last 30.
- **Tanya AI** ("free chat + suggestions"): a top-bar button in the form when an AI is set up opens `AskAiSheet`. The
  AI sees up to 4 photos (real sides before studio copies), the form's fields and the Dex entry (`DiecastChat`);
  quick questions; answers in simple markdown (`chatText`); changes it suggests come as a ```suggest block
  (`FieldSuggestion`) with "Pakai" (`DiecastForm.withSuggestion` checks years, scale and choices; the field gets ✦).
  Gemini answers with Google Search grounding and its sources (`GeminiClient.chat`; a 400/403 naming
  search/grounding/tool asks again without it: `SearchRefused`); other providers answer from memory
  (`OpenAiClient.chat`, the photos only in the first user turn). The conversation is JSON in the sheet's view model's
  saved state; a question cut off by the process dying shows as interrupted. `AiVision.chat` shares the provider
  switching with `extract` (`firstAnswer`). `DiecastChatTest`, `DiecastFormTest`.
- **Price check beyond Tokopedia/Shopee** (all four): other keywords (`PriceChecker.keywordVariants` at once,
  `keywords` from the AI without photos); more places to search (`Marketplace`: Bukalapak, Lazada, Facebook, eBay
  sold); listings in other currencies converted at today's rate (`ExchangeRates.others`: EUR, GBP, JPY, AUD, CAD, SGD,
  MYR, HKD from Frankfurter; without a rate a listing doesn't count; a tip that shipping isn't included); a rough AI
  estimate from the web (`PriceChecker.estimate`, a ```estimate block; "Pakai" stores it as `ValueSource.AiEstimate`,
  `ai_estimate`); the collector's own records (`OwnPriceFinder`: purchase and shelf prices of this diecast and its
  look-alikes from `CollectionMatcher`, with the median). `PriceAlternativesTest`.
- **Regions on the map** (provinsi → kabupaten/kota → kecamatan → desa): Nominatim's GeocodeJSON `admin.level4..7`
  (jsonv2's address keys mix the levels up in Indonesia: Jakarta's kota is `city_district`, Surabaya's kecamatan
  `municipality`, Cibinong's `town`). `Nominatim.region` caches the answer in `place_cache` as
  `osm-region:<~100 m cell>` for a year (`pruneCache(before, regionsBefore)`); an error answer (at sea) is kept as
  `Region.NONE`, no connection isn't. `ExploreViewModel.lookUpRegions` runs while Peta is shown: cached ones at once,
  the rest at Nominatim's pace, again after "Coba lagi". `Exploration.build(…, regions, area)` narrows the events to
  the chosen area (stats, markers, places and history follow) and builds a `RegionChart` one level below it (bought
  and spotted stacked on the meter ramp, "Tidak diketahui" last, points still being looked up and events without a
  point counted). The chosen area is a chip under the filters (× clears it); the chart's breadcrumb goes back up.
  OpenStreetMap misses levels in places (Soreang: no kecamatan or desa). `ExplorationTest`, `PlaceLookupTest`.

## From the phone after 0.10.0 (0.11.0)

The user's first look at 0.10.0 on the phone: numbers that couldn't be tapped, a Dex tab that lists every year, and
the other brands in their collection. Their choices in brackets.

- **Garasi → Koleksi filtered** ("Koleksi tersaring"): "Rata-rata" opens owned by value, the dearest opens that
  diecast, "Merek" scrolls to the brand panel, "Total belanja" owned by purchase price, "Langka" owned and not regular,
  a car manufacturer, a release-year column (a 5-year column = its span) and a scale their diecasts. Koleksi got
  filters only links set (`CollectionFilter.make/scale/years/rare`, `CollectionLink` the same; `scale` `UNKNOWN_SCALE`
  "?" = none given, as the dashboard labels it): each shows as a chip before the menus that clears it on a tap, and
  "Langka (bukan regular)" is a choice in the rarity menu too. The make filter is exact (`COLLATE NOCASE`), not a
  search. "Car makes" is "Car manufacturers" now (the car's maker, not the diecast's brand; Indonesian already said
  "Pabrikan mobil"): `Dashboard.makes` keeps every make, the panel shows 5 and "Lihat semua (n)". Tappable bar rows
  carry a chevron. `DashboardTest` covers the links.
- **Peta** ("Gulir ke bagiannya"): "Tempat dijelajahi" scrolls to the places list, "Diecast" and "Hari berburu" to
  the history, "Berburu sejak" shows the whole history and opens the first day (`BringIntoViewRequester` after
  `animateScrollToItem`). A day in the history opens in place to what was seen or bought, where and for how much
  (`TimelineDay.items`; a tap opens the diecast). The history keeps every day; the page shows 30 and "Lihat semua".
  Item indices are constants in `ExploreScreen` (`MAP_ITEM`, `PLACES_ITEM`, `HISTORY_ITEM`): keep them in step.
- **Dex per brand** ("Halaman merek"): the Dex tab shows a card per brand (meter, legend, how many lists, the newest);
  "Lihat semua" or the card opens `DexBrandRoute(brand)` (`DexBrandScreen`: the brand's progress, then every list with
  its ⋮ menu). The own dex sheet and the delete dialog are `CatalogDialogs`, shared by both screens.
- **Tomica Premium** ("per tahun rilis"): `WikiSource.TomicaPremium`, one page ("Tomica Premium (toyline)") for every
  year, a year's list is what came out that year (dates "4/2/2015", "11/2024", "1/21/23") because the numbers are used
  again for other models. Main line "No.15" (sort = number) and Unlimited "Unlimited No.1" (sort + 1000); sets, RS,
  Racing ("Racing No.99" is a race number), reprints and exclusives have no numbers and are left out (the note says to
  use an own dex). "(Release Commemoration Version)" next to the plain name under one number becomes its variant
  label. Both Tomica sources have brand "Tomica", so the sync chips show `shortName` (the line). The matcher reads a
  number on its own ("15", as typed from a Tomica box) as the line number (`DexMatcher.numbers`). The other brands in
  the collection (Bburago, Maisto, Moto Cross, Roll Speed) have no usable wiki list (no Bburago wiki; Maisto's "Fresh
  Metal" page lists no releases): "Cari daftarnya di web (AI)". `WikiBrandsTest`.

## 3D garage (G0 0.6.7–0.6.8, G1 0.7.0)

The user's real furniture from their photos, in 3D, with the owned diecasts on it: left the black carved corner shelf
(they call it "laci hitam"; three quarter-round tiers), in the middle **two** laser-cut MDF blister boards (4 lanes
each, ~12 cards per lane), right the white case (6 × 10 compartments). Opens from "Rak 3D" on the Garasi dashboard
(`GarageRoute`). Stages agreed: G0 technical trial, G1 shelves + placement, G2 realism, G3 shelf settings.

- Rendering: SceneView (Compose) on Filament; furniture built in code (`ui/garage/Meshes.kt`: lathe, extruded
  outlines with holes, planes), no model files. `BlisterBoard.kt` (outline traced from the photo, scorched edges,
  keyholes, T-rails with a groove, the logo), `CornerShelf.kt` (tiers, turned posts, carved galleries, crest),
  `LooseCase.kt`. Diecasts are pictures: a card is its studio photo with the white cut away, a loose car its photo with
  the backdrop flooded away (`GarageViewModel.cutOut`), a box a block in its border colour with the photo in front.
  Sizes follow the scale (`scaleRatio`). Camera: `Focus` (Semua · Laci · Papan · Rak putih) glides, tapping furniture
  in the overview focuses it, tapping the chosen chip again brings the camera back. Gestures are our own
  `ShelfCamera` (0.7.1; the user couldn't look at a board's sides or bottom: SceneView's manipulator always zooms to
  the screen's centre and its two-finger pan only starts when the fingers keep their distance): one finger orbits
  (yaw/pitch limited), two fingers zoom towards their midpoint and pan along at the same time (like a map), a double
  tap zooms in on the spot (a two-finger tap out); the camera stays in front of the walls and its target on the
  furniture. SceneView's detector hands over y from the bottom of the view. A tapped diecast comes out of its place
  (up the board's groove, off the tier, out of the compartment) and floats up in front of where the camera was when
  it was tapped; tap again to put it back.
- Motion (0.7.2; the user: placing made it just appear, "Ke nampan" had no animation). `Stage` in `GarageScreen`
  compares each new `Layout` with the last one **while composing** (so nothing is drawn a frame in the wrong place)
  and remembers where every diecast was last drawn: one that was in the tray comes out of it (from just under the
  tray button, small, via its place's clear pose into its place; several at once 70 ms apart, as with "Taruh semua
  otomatis"); one carried by hand (`byHand`: the moved one and a swap partner) travels via the clear pose; neighbours
  pushed along glide straight; one gone from the shelves but still owned flies into the tray (a `Ghost`, drawn
  without a tap name) and the tray button bumps when it lands. `landing`/`releasing` keep a diecast lifted or in hand
  until the database change shows up (`LaunchedEffect(scene)` clears them). `Sight.ahead`/`onScreen` convert between
  pixels and the scene.
- Leaving and coming back: the garage composition is thrown away while another screen is on top, and the 3D view
  starts blank. So the view is a **TextureView** (`SurfaceType.TextureSurface`; a SurfaceView ignores the alpha and
  scale of the transitions), on "Detail" its picture is kept in `GarageViewModel.snapshot` and covers the view on the
  way back until it has drawn 3 frames with the scene loaded (1.5 s at most; the wall's colour when there's no
  picture, e.g. coming from the dashboard), then fades. The one in hand, the camera when it was pulled out and when
  the page opened, and its card's rectangle on the screen (`heldCard`, its four corners projected) are saveable, so
  the page shrinks back into a transparent box with `sharedDiecastCard` exactly over the diecast still in hand.
  Picking a focus (`look`) drops the kept camera.
- The Hot Wheels logo (`assets/brand/hot_wheels_logo.png`, from Wikipedia) is **git-ignored** (Mattel's trademark,
  public repo); without it the board draws a hand-made flame, so a clone still builds.
- Placement (G1, the user's choices): free placement on all three — any compartment of the case, a lane of the boards
  (a card goes on top of the ones there and they stack from the bottom), any tier and spot along it on the black
  shelf. Tap an empty spot → a sheet of the diecasts that fit ("Taruh di sini"); tap a diecast → Pindahkan (then tap
  the target; an occupied compartment swaps) / Ke nampan / Detail. Unplaced owned diecasts wait in the tray
  ("Nampan · n", "Belum ditaruh", "Taruh semua otomatis"). What fits: cards on the boards; loose cars and boxes of
  1:60 or smaller in the case (`ShelfLayout.CASE_LARGEST`; 1:64 before 0.9.1); anything on the black shelf (cards too
  since 0.9.1). Each tier has two rows (`tierLayout`, 0.9.1; the user: a 1:18 took the whole tier): decorations and
  the rest along the front edge (`tierSpots`), cards and 1:32 or bigger (`atBack`) side by side in a straight back row
  across the corner facing down its diagonal (`backRowSpots`, fronts short of the front row's nearest corner); what
  has no room in its own row tries the other. The row isn't stored: the slot keeps only the angle. A picked-up
  diecast shows a card with its details (brand, series, year, number, rarity, values; `ShelfRow` carries them; a tap
  on it = Detail). Placing writes "Lokasi simpan" ("Papan 2 · jalur 1",
  "Laci hitam · susun tengah", "Rak putih · baris 3 kolom 2"); taking it off clears it only if it still says that.
  Not owned any more (saved as another status) → off the shelf; deleting cascades.
- Data: Room **v7** `shelf_slots` (diecast id PK + FK cascade, shelf key, spot, position; index shelf + spot).
  spot = lane 0–7 across both boards from the left / tier 0–2 lowest first / compartment row × 6 + column, rows from
  the top; position = order in a lane, or the angle along a tier in tenths of a degree (0 = back wall, 900 = left
  wall). `data/shelf`: `ShelfLayout` (sizes, fit rules, labels), `ShelfPlanner` (pure: place, swap, lane full at 12),
  `ShelfRepository` (one transaction; `ShelfDao.shelve/unshelve` keep the storage location in step). The UI's pure
  `layOut` (`ui/garage/Placement.kt`) turns slots into poses: lanes by position then id, a tier's spots pushed apart
  along the arc between the posts (`tierSpots`), and anything that no longer fits (too full, packaging changed) shown
  in the tray again; `arrange` is "Taruh semua otomatis" (cards to the emptiest lane, loose cars into the case from
  the top, small boxes from its bottom-left as in the photo, the rest round the tiers middle-first) and stores the
  packed angles. Taps: `motionEventToRay` (nullable) intersected with each piece's plane (`laneAt`, `cellAt`,
  `tierAt`). Phone backups carry `"shelf_slot"` per record (the web ignores it); a restore keeps it only if it still fits
  and its compartment is free (a full lane skips it). `ShelfTest`, `GarageGeometryTest`, `MigrationTest`, `BackupExporterTest` cover it.
- **Decorations ("Hiasan", 0.7.3; the user's two figures from their video, the rabbit dusty).** Black shelf only: an
  empty spot there offers "Hiasan baru di sini" (camera or gallery) above the diecasts that fit. `DecorMaker`
  (`data/decor`) cuts the subject out with the studio's ML Kit segmenter (`StudioComposer.isolate`: traced like a
  studio photo, cropped to the subject, null when there is none) and `Dusting.clean` makes a cleaned copy (specks
  replaced by a 5×5 median/MAD test, levels by luminance, a little saturation; only opaque pixels count).
  `DecorDraftSheet` shows both on a checkerboard ("Seperti difoto" / "Dibersihkan", the default), a name and the real
  height in cm (1–60, comma or point), "Taruh di laci". The photo is deleted once cut and never sent anywhere. Drawn
  as a billboard (`DecorParts.Decoration`) that turns to the camera every frame (`eye`, from `onFrame`), sized by its
  height, pushed apart with the diecasts along the tier (`layOut(…, decor)` → `tierSpots`; one without room isn't
  drawn). Tap → Pindahkan (then an empty spot on the black shelf) / Ubah (name, height) / Hapus. Data: Room **v8**
  `decorations` (name, photo, height_mm, tier, position in tenths of a degree like the slots, created_at); cut-outs
  as PNG in `files/decor` (`DecorStore`, unused ones pruned at start-up). Phone backups carry `decor.json` and
  `decor/` (`DecorBackup`, only when there are any; "replace" swaps them). `DecorTest` and `MigrationTest` cover it.
- Render quality switch at the top right ("Normal" / "Polos" without post-processing / "Hemat" without shadows, AO,
  bloom), kept from G0 until the phone shows which works ("Normal" does, on the phone's Mali-G57 with OpenGL ES 3.2;
  Filament runs at feature level 1 there). **The emulator (SwiftShader) can't show "Normal"** (all
  white; "fxaa failed to link") **and aborts compiling "Hemat"**: test on it in "Polos"; colours look darker than on
  the phone. With shadow casters in the scene it paints plain-coloured receivers red, so nothing casts shadows yet.
- Filament/SceneView gotchas: SceneView's materials read UV0, so every vertex needs one (`Meshes.plane` puts the
  texture the right way up); transparent cut-outs need reflectance 0 (otherwise the see-through part shows as a pale
  pane); the default main light points straight down and left the upright boards dark, so it comes from the front,
  above. kotlin-math's vector operators are inlined from JVM 21 bytecode, which this module's JVM 17 target can't
  take: ray and pose maths is plain arithmetic.
- A screen's `Scaffold` needs `snackbarHost = { SnackbarHost(LocalSnackbar.current) }` or `container.messages` never
  show on it (the garage had none at first).
- Some files are CRLF (`WebBackupFormat.kt`, `BackupImporter.kt`): multi-line `perl`/`sed` substitutions silently do
  nothing on them; use the Edit tool.
- Emulator: right after boot the app's window (or the whole app, dashboard too) can stay black: force-stop and
  launch it again. The emulator with the garage used ~6 GB; it was restarted twice for low memory. Inspect its
  database with `adb exec-out run-as io.github.caropeboka.diecasttracker cat databases/diecast-tracker.db` (plus
  `-wal`, `-shm`) and Node's built-in `node:sqlite`.

## Decisions (made with the user)

| Topic | Decision |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose, Material 3 with the "Garage" dark theme (below). UI text in **English** by default, **Bahasa Indonesia** and **Simplified Chinese** too, following the phone unless chosen in Settings (0.9.0; Indonesian only before). Money stored in whole **Rupiah**; shown in Rupiah, USD or CNY at daily rates as chosen. Date format chosen in Settings. |
| First run (0.9.0) | A spotlight tour on the real screens, language first, Skip/Next, replayable from Settings. |
| Data | Room (SQLite) on the device. Photos in app-private storage. |
| Network | OkHttp + kotlinx.serialization (Gemini, OpenAI-style AI providers, Hot Wheels/Matchbox wikis, Nominatim). Coil for images. |
| Location / map | Fused Location Provider; **MapLibre** with **OpenFreeMap**'s dark style (OpenStreetMap data, no API key). osmdroid was dropped: archived Nov 2024. Nominatim for place names. |
| Navigation | Bottom bar: Koleksi · Garasi (dashboard) · ＋ · Peta · Dex; the app opens on Koleksi. The centre ＋ opens Cek di toko / Isi manual / Scan dengan AI (since 0.6.0, replacing the Koleksi FABs) as a FAB menu rising above it (since 0.7.2, replacing a bottom sheet). Other screens open from the ⋮ menu. A screen opened by a tap grows out of what was tapped (0.7.3). Every number on Garasi opens Koleksi filtered to what it counted, the filter shown as a removable chip (0.11.0). |
| AI scan flow (0.6.0) | Photos first (camera back-to-back for a new diecast, a guide of the sides to shoot), the AI reads only on "Pindai dengan AI". While any AI request runs, its steps are shown as a log (not just a spinner). |
| Foto studio (0.6.1) | On the phone only (ML Kit segmentation; corners by hand for cards and boxes), free and offline after the first download; the original photo stays. No generative AI editing (it can change printed details). |
| 3D garage (0.6.7+) | SceneView/Filament, furniture modelled in code after the user's photos (black corner shelf, two MDF blister boards, white 6 × 10 case). Owned diecasts only, one place each whatever the quantity. Free placement with a tray of unplaced ones; the place writes "Lokasi simpan". The trademarked logo stays out of git. Decorations (the user's figures) on the black shelf, cut out on the phone and optionally cleaned (0.7.3). |
| Dex | Two stages: 4a wiki sync, matching, progress, "Cocokkan Dex", form panel, add from Dex, scan suggestion; 4b own lists (manual, Excel/CSV, AI from screenshots, from the collection). Since 0.10.0: Hot Wheels from 1989, Matchbox from 2000, Mini GT (with Kaido House) 2018–2022 and Tomica's regular lineup from their wikis; other lines through the AI's web search into own dexes, reviewed first. Since 0.11.0: Tomica Premium per release year; the Dex tab shows a card per brand, which opens that brand's page with its lists. The Dex starts from scratch on the phone (no web backup change). Thumbnails download in the background, Wi-Fi only by default, user can allow mobile data. Own dexes: the AI's checklist rows are reviewed before they're added; entries can be edited (both unlike the web). |
| Location filling | GPS only on request (map picker); the picked place is remembered 3 hours and pre-fills new diecasts and sightings. |
| Background work | WorkManager (Dex sync, thumbnail downloads, bulk scan queue). |
| Settings | DataStore (every AI provider's key, model, on/off and order; the custom server's URL and name). |
| AI provider | Gemini, Groq, Mistral, OpenRouter and one OpenAI-compatible server (0.8.0; Gemini only before), plus Cloudflare Workers AI, NVIDIA NIM, Z.ai and OVHcloud (no key, off by default) since 0.10.0, tried in the user's order: on quota, busy, timeout or a refused key the same request goes to the next; one out of quota rests until it is back. Cerebras left out (no free tier). Claude as in the web may follow. Duplicate warning as on the web (no "+1" shortcut). |
| Lock | None — opens straight in (personal phone). |
| Existing data | Import the web app's **backup zip** (see below). |
| In-store check (phase 5e) | Opened from a launcher shortcut, the bottom bar's centre button and the ⋮ menu; the camera opens straight away. A typed search without the AI works offline. "Dapat" also fills "Beli di" with the store (the web only sets the date). Since 0.10.0 many photos in a row, read one at a time. |
| Bulk scan (phase 5d) | Reads in the background with WorkManager (not only while the screen is open, unlike the web). Camera is back-to-back. Entry: ⋮ menu in Koleksi, banners in Koleksi and Garasi. Saved items leave the queue (no "saved" history). |
| Price check (phase 5c) | From the diecast form only (no share target). Screenshots are read and thrown away; the readings, stats and history are kept (5 in view) and go into phone backups. Since 0.10.0 also other keywords, Bukalapak/Lazada/Facebook/eBay sold, prices abroad at today's rate, a rough AI estimate from the web (source `ai_estimate`) and the collector's own records. |
| Tanya AI (0.10.0) | A chat in the diecast form that sees its photos and fields; suggested changes applied with "Pakai"; Gemini searches the web and shows its sources. |
| Map regions (0.10.0) | Provinsi → kabupaten/kota → kecamatan → desa from OpenStreetMap's admin levels per point, looked up while Peta is open and cached a year; a drill-down chart that also filters the page. |
| Backup (phase 5a) | One zip in the web's backup format + the Dex, so phone and web can both restore it. Manual: "Simpan" (file picker) or "Bagikan" (share sheet); reminder card after 30 days without a backup. No automatic backup. Excel: export only (no collection import from Excel). |
| Devices | Phone: Infinix NOTE 50 Pro (X6855), Android 16 (API 36), arm64; USB debugging on, the laptop is allowed. Emulator AVD `Medium_Phone_API_37.0` (API 37, WHPX acceleration works). |

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
- The web's backups have no Dex: the user chose to build the Dex from scratch on the phone (sync + auto-linking +
  "Cocokkan Dex"). Backups made on the phone add it in keys and files the web ignores (see "Notes from phase 5").

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
  Conventional Commit messages, split per feature. Never commit, stage or `git rm` for them. One code block per
  commit, every line under ~90 characters (one path per `git add`; the terminal's wrapping turns long lines into
  broken ones when copied), the body as short lines inside the quotes, no `Co-Authored-By` trailer.
- System setup: default to giving steps; when they ask you to do it (e.g. environment variables), back up
  what you change first.
