<?php

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    throw new RuntimeException('This test is CLI-only.');
}

$expectedRaw = strtolower(trim((string) ($argv[1] ?? '')));
if (!in_array($expectedRaw, ['true', 'false'], true)) {
    throw new InvalidArgumentException('Expected true or false.');
}

require dirname(__DIR__, 2) . '/private/config.php';

$expected = $expectedRaw === 'true';
if (LOC_GROUP_CODE_BACKFILL_ENABLED !== $expected) {
    throw new RuntimeException(sprintf(
        'LOC_GROUP_CODE_BACKFILL_ENABLED was %s, expected %s.',
        LOC_GROUP_CODE_BACKFILL_ENABLED ? 'true' : 'false',
        $expectedRaw
    ));
}

echo "group_backfill_config_test: OK ($expectedRaw)\n";
