# Changelog

All notable changes to Diecast Tracker for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).
Releases with an APK are on the [Releases page](https://github.com/caropeboka/diecast-tracker-android/releases).

## [0.12.0] - 2026-11-08

### Added
- One Settings page (the gear on the collection) with General (language, currency, date format), AI, Data & backup,
  the tour and About, each showing what it is set to now.
- Ask AI can suggest the purchase price, market value or wishlist target price, applied with one tap like the rest.
- A quick question in Ask AI for a car bought long ago: what it cost at Indomaret or Alfamart in its release year,
  suggested as the purchase price (an estimate when the exact price can't be found).
- Leaving the AI settings with a key typed but not saved asks first.
- 3D garage: a low white desk (70 × 40 × 25 cm) under the boards. Boxes and loose cars stand anywhere on it; what is
  put on something else moves to the nearest free spot. "Place all automatically" fills it from the back when the
  rest is full.
- 3D garage: two car lifts on the desk's back corners, each holding a loose car on its deck and one under it, and a
  nanoblock dog that can be moved around the desk. A "Desk" view looks down on them.
- 3D garage: shelf settings (the gear at the top) set how many cards a lane of the boards takes, 4 to 40 instead of
  a fixed 12. More cards hang closer together; cards that no longer fit wait in the tray.
- 3D garage: "Flip" on a picked-up loose car turns it to face the other way. Its photo in the collection stays as
  it is.

### Changed
- Bulk scan moved into the ＋ menu. The collection's ⋮ menu is gone: the store check is in ＋ and everything else in
  Settings.
- Deleting a diecast moved into a ⋮ menu on its page, away from Ask AI.
- 3D garage: the blister boards hang flat on the wall above the desk instead of leaning against it.
- Groq gets 2 photos per request instead of 3. Each photo counts as 2048 tokens whatever its size, so 3 photos and
  the prompt went over the free plan's tokens a minute.

### Fixed
- A request over a provider's token limit is sent again with fewer photos, instead of smaller ones that count the
  same, and only then fails over to the next AI.
- Ask AI no longer shows a raw ```suggest block when the AI names a field the form doesn't have, or is cut off.
- Ask AI's conversation no longer blinks at the end of the list, and flinging to the top doesn't close it.
- After "Save & add another", Ask AI starts a fresh conversation for the next diecast.
- Dex sync with the phone in a language that writes other digits (Arabic, for example) asked the wiki for the wrong
  page.

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

[0.12.0]: https://github.com/caropeboka/diecast-tracker-android/releases/tag/v0.12.0
[0.11.0]: https://github.com/caropeboka/diecast-tracker-android/releases/tag/v0.11.0
