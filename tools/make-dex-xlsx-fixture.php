<?php

/*
 * Builds app/src/test/resources/dex-list-inline.xlsx and dex-list-shared.xlsx with OpenSpout (what the web app
 * reads and writes spreadsheets with), so the Android spreadsheet reader is tested against files a real writer
 * made: once with inline strings (OpenSpout's default) and once with a shared string table (like Excel).
 * Only the web app's vendor folder is used; its database and files are not touched.
 *
 *   php tools/make-dex-xlsx-fixture.php [path/to/web-app]
 *
 * The web app path defaults to C:/laragon/www/diecast-tracker.
 */

use OpenSpout\Common\Entity\Row;
use OpenSpout\Writer\XLSX\Options;
use OpenSpout\Writer\XLSX\Writer;

$web = $argv[1] ?? 'C:/laragon/www/diecast-tracker';
require "$web/vendor/autoload.php";

$rows = [
    ['Nomor', 'Nama', 'Seri', 'Nomor seri', 'Varian'],
    ['MGT00512', 'Nissan Skyline GT-R (R34)', 'Mini GT 2026', '', 'Bayside Blue'],
    ['MGT00512', 'Nissan Skyline GT-R (R34)', 'Mini GT 2026', '', 'Millennium Jade'],
    [84, 'Toyota GR86', '', '3/5', ''],
    ['', 'Honda NSX-R — Type R', '', '', 'Championship White'],
    ['', '', '', '', ''],
];

foreach (['inline' => true, 'shared' => false] as $kind => $inline) {
    $out = __DIR__."/../app/src/test/resources/dex-list-$kind.xlsx";
    $writer = new Writer(new Options(SHOULD_USE_INLINE_STRINGS: $inline));
    $writer->openToFile($out);
    $writer->getCurrentSheet()->setName('Daftar');
    foreach ($rows as $values) {
        $writer->addRow(Row::fromValues($values));
    }
    // Only the first sheet is read.
    $writer->addNewSheetAndMakeItCurrent()->setName('Catatan');
    $writer->addRow(Row::fromValues(['Nama', 'Bukan daftar']));
    $writer->addRow(Row::fromValues(['Jangan dibaca', '']));
    $writer->close();

    echo "Wrote $out\n";
}
