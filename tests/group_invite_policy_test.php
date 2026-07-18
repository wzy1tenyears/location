<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

for ($index = 0; $index < 256; $index += 1) {
    $code = generate_lower_alphanumeric_code(8);
    if (preg_match('/^[0-9a-z]{8}$/D', $code) !== 1) {
        throw new RuntimeException('Generated code does not match the 8-character policy.');
    }
}

foreach (['0a1b2c3d', '000102030405060708090a0b0c0d0e0f'] as $compatibleCode) {
    if (!is_valid_family_group_code($compatibleCode)) {
        throw new RuntimeException('A compatible group code was rejected.');
    }
}

foreach (['', 'abc123', 'ABC12345', '000102030405060708090A0B0C0D0E0F'] as $invalidCode) {
    if (is_valid_family_group_code($invalidCode)) {
        throw new RuntimeException('An invalid or non-normalized group code was accepted.');
    }
}

$lengthFailure = false;
try {
    generate_lower_alphanumeric_code(0);
} catch (InvalidArgumentException) {
    $lengthFailure = true;
}
if (!$lengthFailure) {
    throw new RuntimeException('Non-positive random-code length was accepted.');
}

echo "group_invite_policy_test OK\n";
