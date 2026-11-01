# Diecast Tracker for Android

A personal Android app for keeping track of a die-cast car collection: photos, AI card scanning, a Hot Wheels and
Matchbox checklist ("Dex"), a map of where cars were found, market prices from marketplace screenshots, and a 3D
model of the shelves at home with every car on it.

There is no server and no account. Everything lives on the phone, and the app only goes online for the things that
need it (see [Privacy](#privacy)).

> The interface is in **English**, **Bahasa Indonesia** and **Simplified Chinese**. Prices are stored in Indonesian
> Rupiah and can be shown in Rupiah, US dollars or Chinese yuan. The app was built for one collector and one phone.
> The code is public as a reference, not as a product with support.

It is a native rewrite of a Laravel + React web app with the same name. The web app's logic and feature tests were
ported over, so both apps behave the same and can restore each other's backups.

## Features

### Collection
- Grid or list with search (tags included), filters for status, brand, rarity and tag, and 7 sort orders.
- Up to 8 photos per car from the camera or the gallery; the first one is the cover.
- Four statuses: owned, wishlist, spotted and sold, plus tags, packaging, condition, scale, prices and
  storage location.
- **Studio photo** turns a photo into a clean cover on white, entirely on the phone. Loose cars are cut out with ML
  Kit; for cards and boxes you place the four corners and the app squares the card up, rounds its corners and removes
  the hang hole.

### AI
- **Scan:** photograph every side of the card, then "Scan with AI" fills in name, brand, series, number, year and
  rarity. Fields the AI filled are marked ✦, and a car you already own triggers a duplicate warning.
- **Bring your own free key.** Supported: Google Gemini, Groq, Mistral, OpenRouter, and any OpenAI-compatible server
  (for example NVIDIA NIM or Cloudflare Workers AI).
- **Automatic switching:** the providers are tried in the order you choose. When one runs out of quota, is busy, takes
  too long or rejects its key, the same photos go straight to the next. A provider out of quota is skipped until its
  quota is back.
- Every AI request shows its steps as a live log (uploading, waiting, switching to another provider) instead of a
  bare spinner.

### Out hunting
- **Store check:** take a photo of a card on the store shelf and see right away whether you already
  own it, want it, or saw it somewhere before. Then +1, "Got it" (mark it bought here), log the sighting with the shelf
  price, or add it as a new car. Also works without the AI by typing a name, offline. Opens from a launcher shortcut.
- **Bulk scan:** queue many photos and the AI reads them one by one in the background (WorkManager), even with the
  app closed. It waits out quota limits and lost connections. You review the results, then save them one by one or
  save every confident one in one tap.
- **Sightings and map:** every sighting gets a place, date, price and photo. A map picker suggests your own places
  within 150 m before OpenStreetMap's. The hunting map shows where you bought or only saw cars, with stats and a
  timeline.

### Dex
- Syncs the **Hot Wheels** and **Matchbox** mainline year lists (2015 and later) from the Fandom wikis, and links
  the collection to them automatically.
- Shows seen / caught / wanted progress per brand, year and series.
- "Match the Dex" lets you confirm automatic links or pick the right entry. It handles cards whose printed year (the
  casting's copyright year) differs from the release year.
- Your own lists for brands without a wiki: added by hand, imported from Excel/CSV, read from checklist screenshots
  by the AI, or built from the collection.

### Prices and stats
- **Market price check:** take screenshots of Tokopedia or Shopee search results and the AI reads every listing. The
  app then works out the median, a fair range and a suggested price, with an accuracy rating. Outliers are left out.
- **Garage dashboard:** collection value and gain, the most valuable cars, Dex progress, breakdowns by brand, rarity,
  make, year and scale, price gainers, the wishlist and data completeness.

### 3D garage
- The real furniture at home, modelled in code after photos: a black carved corner shelf, two laser-cut blister
  boards and a white case with 6 × 10 compartments. Rendered with SceneView (Filament).
- Owned cars appear as their photos: cards hang on the boards, loose cars sit in the compartments. Place a car
  anywhere it fits; unplaced cars wait in a tray. Placing a car also fills in its storage location.
- A map-like camera (orbit, pinch to zoom towards your fingers, pan), and cars that glide between places instead of
  jumping.
- Decorations: photograph a figure and the app cuts it out (and can clean off dust) so it can stand on the shelf.

### Languages, currency and first run
- English, Bahasa Indonesia and Simplified Chinese. The app follows the phone's language, or you pick one in
  **⋮ → Settings** (on Android 13 and newer also in the phone's per-app language settings).
- Prices in Rupiah, US dollars or Chinese yuan, converted at the day's exchange rate. Everything stays stored in
  Rupiah, and a price typed in another currency is converted back.
- Dates as the language writes them, or as YYYY-MM-DD, DD-MM-YYYY, DD/MM/YYYY or MM/DD/YYYY.
- A guided tour on first launch: it starts with the language, then spotlights each part of the app on the real
  screens. Replay it from Settings.

### Backup
- One `.zip` in the web app's backup format, plus the Dex, price checks, shelf places and decorations. Save it through
  the system file picker or share it.
- Restores both phone and web backups (merge or replace). Exports the collection to Excel.
- A reminder card appears when the collection hasn't been backed up in 30 days.

## Privacy

- No backend, no analytics, no account, no ads. The collection and its photos are stored in the app's private
  storage.
- Photos go to an AI provider **only when you ask the AI to read them**, and only to the providers you set up.
  Screenshots for price checks are read and then discarded.
- Other network use: the Fandom wikis for the Dex, OpenStreetMap's Nominatim for place names, OpenFreeMap for map
  tiles, Google Play services for location and the ML Kit model download, and (only when prices are shown in
  another currency) [Frankfurter](https://frankfurter.dev) or [open.er-api.com](https://open.er-api.com) for exchange
  rates.
- API keys are kept in a DataStore file that is excluded from Android's cloud backup.

## Tech stack

| Area | Library |
| --- | --- |
| Language and UI | Kotlin 2.4, Jetpack Compose (Material 3), Navigation Compose with type-safe routes |
| Data | Room (schema v8, every version migrated, schemas in `app/schemas`), DataStore |
| Network | OkHttp 5, kotlinx.serialization |
| Images | Coil 3, ML Kit subject segmentation (on-device) |
| Maps and location | MapLibre Android with OpenFreeMap, Fused Location Provider |
| Background work | WorkManager |
| 3D | SceneView 4 (Filament) |

Requires Android 10 (API 29) or newer with Google Play services. Release builds target arm64 only.

## Project layout

```
app/src/main/java/io/github/caropeboka/diecasttracker/
├── data/        Room entities and DAOs, repositories, and the ported logic:
│   ├── ai/      Gemini and OpenAI-style clients, provider switching, card scanner
│   ├── dex/     wiki parsing, sync, matching, progress, own lists
│   ├── locale/  app language and texts outside the screens
│   ├── money/   exchange rates
│   ├── price/   marketplace price analysis
│   ├── scan/    bulk scan queue, in-store check
│   ├── shelf/   3D garage placement
│   └── backup/  backup export and restore, Excel
└── ui/          Compose screens per feature (collection, edit, dex, garage, check, …)
```

Texts live in `app/src/main/res/values*/strings_<feature>.xml`: `values` (English), `values-in` (Indonesian) and
`values-zh-rCN` (Simplified Chinese).

Most logic is plain Kotlin and covered by JVM and Robolectric tests (200+). Many are ports of the web app's feature
tests and use its real-world fixtures, such as wiki tables and backup zips.

## Building

You need Android Studio with JDK 21 or newer, and the Android SDK with platform 37.

```sh
./gradlew :app:assembleDebug        # debug APK (arm64 + x86_64 for the emulator)
./gradlew :app:testDebugUnitTest    # unit and Robolectric tests
./gradlew :app:lintDebug
./gradlew :app:assembleRelease      # R8-minified release APK (arm64)
```

**Release signing:** put a `signing.properties` file in the project root (it is git-ignored):

```properties
storeFile=/path/to/your.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, the release build is signed with the local debug key. Pick one key and keep it: an update signed with a
different key can't be installed over the old one without uninstalling the app, which deletes its data.

**Hot Wheels logo:** the 3D blister boards can show the Hot Wheels logo from
`app/src/main/assets/brand/hot_wheels_logo.png`. The logo is a Mattel trademark, so it is not in the repository;
without it the boards show a drawn flame instead.

## Setting up the AI

Every AI provider below has a free tier. Create a key, then open **⋮ → AI settings**, paste it, and tap "Test key"
to check it and choose a model.

| Provider | Free tier | Get a key |
| --- | --- | --- |
| Google Gemini | daily free quota | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) |
| Groq | fast; up to 3 photos per request | [console.groq.com/keys](https://console.groq.com/keys) |
| Mistral | "Experiment" plan (phone verification; requests may be used for training) | [console.mistral.ai/api-keys](https://console.mistral.ai/api-keys) |
| OpenRouter | free models, 50 requests a day (1,000 after a one-time $10 top-up) | [openrouter.ai/keys](https://openrouter.ai/keys) |
| OpenAI-compatible | depends on the service; enter its base URL, key and a vision model | — |

Free tiers change often; the limits above were checked in November 2026. Use ↑/↓ on the settings screen to set the
order the providers are tried in.

## Status

Version 0.9.0, in daily use on one phone. Planned next: more realism in the 3D garage (materials, shadows, finding a
car on the shelves) and shelf settings.

## Credits

- Fonts: [Saira Condensed](https://fonts.google.com/specimen/Saira+Condensed) and
  [Instrument Sans](https://fonts.google.com/specimen/Instrument+Sans), SIL Open Font License (bundled in
  `assets/licenses`).
- Icons: [Material Symbols](https://fonts.google.com/icons), Apache License 2.0.
- Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, tiles by
  [OpenFreeMap](https://openfreemap.org); place names by [Nominatim](https://nominatim.org).
- Dex data comes from the [Hot Wheels Wiki](https://hotwheels.fandom.com) and
  [Matchbox Wiki](https://matchbox.fandom.com) (CC BY-SA). It is downloaded on the phone and not bundled with the
  app.

Hot Wheels and Matchbox are trademarks of Mattel, Inc. This project is not affiliated with or endorsed by Mattel,
Fandom or any marketplace it reads screenshots from.
