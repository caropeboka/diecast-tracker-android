# Changelog

All notable changes to Diecast Tracker for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).
Releases with an APK are on the [Releases page](https://github.com/caropeboka/diecast-tracker-android/releases).

## [0.11.0] - 2026-11-07

### Added
- Every number on the Garage dashboard opens the collection filtered to what it counted: the average value, total
  spent, rare cars, a car manufacturer, a release year column and a scale. The most valuable car opens itself, and
  "Brands" scrolls to the brand panel.
- The collection shows those filters as chips that clear on a tap, and has a "Rare (not regular)" rarity choice.
- The hunting map's stat tiles scroll to the places list or the history; a day in the history opens to what was seen
  or bought, where and for how much. The history keeps every day (30 shown, then "Show all").
- The Dex tab shows one card per brand; "See all" opens the brand's own page with every list.
- Tomica Premium in the Dex, a list per release year from the Tomica Wiki (main line and Unlimited).

### Changed
- "Car makes" is now "Car manufacturers", showing the top 5 and "Show all".
- A number typed on its own ("15", as printed on a Tomica box) counts as the line number when matching the Dex.

## [0.10.0] - 2026-11-07

### Added
- Dex: Hot Wheels from 1989, Matchbox from 2000, Mini GT (with Kaido House) 2018–2022 and the Tomica regular lineup.
- "Look the list up on the web (AI)" for lines without a wiki list; the rows are reviewed before they are added.
- AI providers: Cloudflare Workers AI, NVIDIA NIM, Z.ai and OVHcloud AI Endpoints (no key needed, off by default).
- "Ask AI" in the diecast form: a chat that sees the photos and fields and suggests changes you apply with one tap.
  Gemini searches the web and shows its sources.
- Price check: other keywords, Bukalapak, Lazada, Facebook and eBay sold listings, prices abroad at the day's rate,
  a rough AI estimate from the web, and the prices already in your collection.
- Store check: shoot many photos in a row; they are read one at a time.
- Hunting map: province, city, district and village for every point, with a chart that narrows the page to an area.

## [0.9.1] - 2026-11-07

### Fixed
- A wrong Dex link can always be changed: search the Dex from the form or "Match the Dex", or match again from
  scratch. Tapping a linked entry opens that entry instead of its whole year.
- Map markers are easier to tap, place details open in a sheet, and "My location" shows where you are.
- 3D garage: a picked-up car shows its details, cards fit on the corner shelf, each tier has a back row for big
  scales, and the white case takes 1:60 and 1:62.

## [0.9.0] - 2026-11-01

Includes 0.6.0–0.8.0, which were built for the author's phone only.

### Added
- English, Bahasa Indonesia and Simplified Chinese; prices in Rupiah, US dollars or yuan at daily rates; a choice of
  date formats; a Settings screen and a guided first-run tour.
- More free AI providers (Groq, Mistral, OpenRouter, any OpenAI-compatible server) with automatic switching when one
  is out of quota, busy or slow.
- The 3D garage: the shelves at home modelled in code, free placement of every owned car, a tray, and decorations.
- Studio photos: a clean cover on white, made on the phone.
- A live log of every AI request's steps.
- A centre "Add" button with a menu, a new bottom bar, a wheel launcher icon and container transitions.

### Changed
- Photos first, then the AI on request: the camera reopens after each shot and a guide lists the sides to shoot.
- The in-store check page was redesigned around the camera and one main action per result.

## [0.5.4] - 2026-10-22

The first complete version: the collection with photos and backup import (phase 1), the AI card scan (2), sightings
and the hunting map (3), the Dex with wiki sync and own lists (4), and backup and Excel export, the Garage dashboard,
market prices from screenshots, the bulk scan and the in-store check (5).

[0.11.0]: https://github.com/caropeboka/diecast-tracker-android/releases/tag/v0.11.0
