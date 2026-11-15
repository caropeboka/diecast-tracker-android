# Changelog

All notable changes to Diecast Tracker for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).
Releases with an APK are on the [Releases page](https://github.com/caropeboka/diecast-tracker-android/releases).

## [0.15.0] - 2026-11-15

### Added
- Automatic price check: with a free SerpApi key, "Check automatically on Google Shopping" in the price check searches
  Google Shopping in Indonesia for the keywords, no screenshots needed. The AI picks the listings that are the same
  car from their titles, and the median, fair range and accuracy are worked out as for screenshots. Afterwards it
  says how many SerpApi searches are left.
- Settings → Automatic price check: save, test and remove the SerpApi key. The test shows the plan and the searches
  left. Only the keywords and the key go to SerpApi, never photos, and the key stays out of backups.
- Listings found by the automatic check open in the browser from the check's result.
- Logos: the price check's search buttons, its listings and past checks show the shop's logo; the AI settings, the
  steps of every AI request and "filled in by" labels show the AI's; the SerpApi page and its Settings row show
  SerpApi's; and the Dex, the Garage's brand panel, the brand filter and the collection's cards show the diecast
  brand's (initials for a brand without one). The Garage's car manufacturers and the collection filtered by one show
  the car maker's emblem (over a hundred makers, cars and motorcycles). A car's page shows the brand's logo and the
  maker's emblem in their fields; the Garage's Dex, most valuable, price gainers and wishlist rows and the map's
  history show the brand's logo; Treasure Hunts and Super Treasure Hunts carry their circle-flame in the Garage's
  rarity panel and the rarity filter.
- The collection can be filtered by car manufacturer (with the maker's emblem), and a car's page suggests the makers
  already in the collection.
- Gain or loss against the purchase price, as a trading app shows it: "▲ 25%" in green or "▼ 8%" in red beside the
  market value on the collection's cards, and the amount with the percentage under the market value on a car's page.
- Receipts with the shop's address: the AI reads the branch's address and the app looks it up on OpenStreetMap, so
  "Bought at" gets the shop's point on the map as well as its name (only when the street or the shop itself is
  found; the collector's own pin of the same shop nearby wins). The receipt card shows the address and whether it
  was found. Head-office addresses and online orders are left out. A street must carry the name that was searched,
  and when OpenStreetMap lacks the street, the shop itself is searched in its kelurahan and kecamatan (only one may
  come back).
- "Find the store on the map" on a receipt whose store has no point yet (one read before addresses were): the AI
  reads it again and "Bought at" gets the point, keeping the name typed there.

### Changed
- A car's page: "Another photo", "Gallery" and "Scan with AI" sit in one row, each icon over its label, with how many
  photos the AI reads under its own.
- The Dex panel's "Confirmed · Change · Unlink" row is centred.
- A scale written with a slash or spaces ("1/60", "1 : 60") is saved as "1:60", so it doesn't count as another scale.

## [0.14.0] - 2026-11-14

### Added
- Receipts: the receipt button beside the purchase price photographs a receipt (or picks it from the gallery) and the
  AI reads its lines. The car's line fills in the purchase price, and the receipt's date and shop fill in the purchase
  date and place when they are still empty. Prices in another currency are converted at the day's rate.
- One receipt for several cars: the next car of the same purchase picks it from "Read recently" and takes another
  line; lines already taken say by which car. Lines that aren't diecasts (a bag, a drink) are listed apart.
- Bulk scan: "Prices from a receipt" for the whole queue, with the AI pairing each photo with its line before you
  apply it.
- The receipt is kept with the car as proof of purchase (not as one of its photos), opens full screen with zoom,
  and travels in phone backups. Receipts no car was saved with go after a day.
- Turn a photo a quarter to the right or mirror it from the photo tools on a car's page.
- "Move to…" on a sighting (its ⋮ menu, next to delete): a sighting logged on the wrong car becomes a new spotted
  car with its photo as the cover, or moves to a look-alike of the same name.
- Store check: "Not this one: log it as new" on a result that matched a car in the collection.
- 3D garage: eight nails in the wall over the desk, two rows of four, each holding one card by its hang hole. A card
  put on a taken nail swaps with the one there, "Place all automatically" fills the nails before the boards, and a
  "Nails" view faces them.

### Changed
- 3D garage: the blister boards hang higher, above the nails, and the white case hangs upright on the wall beside
  them, level with the boards, instead of leaning on the floor.

### Fixed
- Store check: a card with the same name but another number (the two "Batmobile" cards in the 2026 Batman series)
  no longer counts as the car you already have or saw. It shows as a look-alike with its number, and the verdict
  says you don't have it yet. Before, its sighting was logged on the other car, whose old photo then stood for both.

## [0.13.0] - 2026-11-10

### Added
- Price check: Google Shopping, OLX (its hobby category), Carousell, and sold listings on Yahoo! Auctions Japan and
  Mercari Japan, whose prices in yen are converted like the others. The search buttons are grouped into new from
  shops, second-hand and sold abroad.
- Price check: "Search the photo with Google Lens" sends the cover photo to Google Lens, for a car its name doesn't
  find.
- The AI's other keywords include one in Japanese for Tomica and other Japanese lines.
- A quick question in Ask AI for the current market price, suggested as the market value.

### Removed
- Bukalapak from the price check search buttons: it stopped selling physical goods in February 2025, so its product
  search no longer opens.

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

[0.15.0]: https://github.com/caropeboka/diecast-tracker-android/releases/tag/v0.15.0
[0.14.0]: https://github.com/caropeboka/diecast-tracker-android/releases/tag/v0.14.0
[0.13.0]: https://github.com/caropeboka/diecast-tracker-android/releases/tag/v0.13.0
[0.12.0]: https://github.com/caropeboka/diecast-tracker-android/releases/tag/v0.12.0
[0.11.0]: https://github.com/caropeboka/diecast-tracker-android/releases/tag/v0.11.0
