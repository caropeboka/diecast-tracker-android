<?php

/*
 * Builds app/src/test/resources/web-backup-v1.zip with the web app's own CollectionBackup, so the importer
 * is tested against the real format. Runs on a throwaway in-memory SQLite database and a temporary public
 * disk: the web app's database and photos are not touched.
 *
 *   php -d extension=zip tools/make-web-backup-fixture.php [path/to/web-app]
 *
 * The web app path defaults to C:/laragon/www/diecast-tracker.
 */

$web = $argv[1] ?? 'C:/laragon/www/diecast-tracker';
$out = __DIR__.'/../app/src/test/resources/web-backup-v1.zip';

foreach ([
    'DB_CONNECTION' => 'sqlite', 'DB_DATABASE' => ':memory:', 'CACHE_STORE' => 'array',
    'SESSION_DRIVER' => 'array', 'QUEUE_CONNECTION' => 'sync', 'GEMINI_API_KEY' => '', 'ANTHROPIC_API_KEY' => '',
] as $key => $value) {
    putenv("$key=$value");
    $_ENV[$key] = $value;
    $_SERVER[$key] = $value;
}

require "$web/vendor/autoload.php";
$app = require "$web/bootstrap/app.php";
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

$disk = sys_get_temp_dir().'/diecast-fixture-'.uniqid();
config(['filesystems.disks.public.root' => $disk]);

use App\Models\User;
use App\Services\CollectionBackup;
use App\Services\DiecastWriter;
use App\Services\PhotoStorage;
use App\Services\SightingLog;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Storage;

/** A small solid-colour image, so the fixture stays tiny. */
function image(string $format, int $red, int $green, int $blue): string
{
    $image = imagecreatetruecolor(160, 120);
    imagefill($image, 0, 0, imagecolorallocate($image, $red, $green, $blue));
    ob_start();
    $format === 'webp' ? imagewebp($image) : imagejpeg($image, null, 85);

    return (string) ob_get_clean();
}

Artisan::call('migrate', ['--force' => true]);

$user = User::forceCreate(['name' => 'Fixture', 'email' => 'fixture@example.test', 'password' => bcrypt('x')]);
$photos = app(PhotoStorage::class);
$writer = app(DiecastWriter::class);
$sightings = app(SightingLog::class);
$jpg = fn (int $red, int $green, int $blue) => $photos->storeBytes(image('jpeg', $red, $green, $blue));
$base = ['rarity' => 'regular', 'packaging' => 'carded', 'status' => 'owned', 'quantity' => 1, 'scale' => '1:64'];

$writer->save($user, null, [...$base,
    'name' => "'71 Datsun 510", 'brand' => 'Hot Wheels', 'make' => 'Nissan', 'series' => 'HW J-Imports',
    'collection_number' => '61/250 · 2/10', 'year' => 2024, 'color' => 'Metallic Blue', 'rarity' => 'treasure_hunt',
    'condition' => 'mint', 'purchase_price' => 35000, 'market_value' => 150000, 'purchase_date' => '2026-09-01',
    'purchase_place' => 'Toys Kingdom Grand Indonesia', 'purchase_latitude' => -6.1951, 'purchase_longitude' => 106.8209,
    'storage_location' => 'Rak A', 'notes' => 'Kartu sedikit tertekuk di pojok.', 'ai_scanned' => true,
], [$jpg(200, 40, 40), $jpg(40, 40, 200)], ['JDM', 'Favorit']);

$writer->save($user, null, [...$base,
    'name' => 'Toyota AE86 Sprinter Trueno', 'brand' => 'Tomica', 'make' => 'Toyota', 'packaging' => 'boxed',
    'quantity' => 2, 'purchase_price' => 89000, 'market_value' => 95000, 'year' => 2023, 'storage_location' => 'Box 3',
], [$jpg(40, 200, 40)], ['JDM', 'Drift']);

$writer->save($user, null, [...$base,
    'name' => 'Nissan Skyline GT-R (R34)', 'brand' => 'Mini GT', 'status' => 'wishlist', 'target_price' => 250000,
    'packaging' => 'boxed',
], [], ['JDM']);

$porsche = $writer->save($user, null, [...$base,
    'name' => 'Porsche 911 GT3 RS', 'brand' => 'Hot Wheels', 'series' => 'HW Exotics', 'status' => 'spotted', 'year' => 2025,
], [$jpg(200, 200, 40)], []);
$sightings->record($porsche, ['place' => 'Toys Kingdom', 'latitude' => -6.2, 'longitude' => 106.8, 'seen_on' => '2026-10-01', 'price' => 45000], $jpg(90, 90, 90));
$sightings->record($porsche, ['place' => 'Gramedia Matraman', 'seen_on' => '2026-10-10', 'price' => 55000, 'notes' => 'Tinggal satu']);

$writer->save($user, null, [...$base,
    'name' => 'Honda Civic Type R', 'brand' => 'Matchbox', 'status' => 'sold', 'purchase_price' => 30000, 'market_value' => 60000,
], [], []);

// The web also keeps .webp photos (its own restore drops them).
$countach = $writer->save($user, null, [...$base,
    'name' => 'Lamborghini Countach LPI 800-4', 'brand' => 'Hot Wheels', 'rarity' => 'super_treasure_hunt',
    'market_value' => 750000, 'purchase_price' => 32000, 'year' => 2026,
], [], ['Favorit']);
Storage::disk('public')->put('diecasts/countach.webp', image('webp', 30, 30, 120));
$countach->photos()->create(['path' => 'diecasts/countach.webp', 'position' => 0]);

app(CollectionBackup::class)->create($user, $out);

// Records the restore must skip or clean up, as a hand-edited file might contain them.
$zip = new ZipArchive;
$zip->open($out);
$manifest = json_decode($zip->getFromName('diecasts.json'), true);
$manifest['diecasts'][] = [...$base, 'name' => null, 'brand' => 'Hot Wheels'];
$manifest['diecasts'][] = [...$base, 'name' => 'Ford Model T', 'year' => 1900];
$manifest['diecasts'][] = [...$base, 'name' => 'Path trick', 'photos' => ['../../evil.jpg', 'photos/missing.jpg']];
$zip->addFromString('diecasts.json', json_encode($manifest, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
$zip->deleteName('koleksi.xlsx');
$zip->close();

Storage::disk('public')->deleteDirectory('');
@rmdir($disk);

echo count($manifest['diecasts'])." records written to $out\n";
