<?php
declare(strict_types=1);

const APP_NAME = '位置';
const ANDROID_VERSION_CODE = 88;
const ANDROID_VERSION_NAME = '2.1.2';
const ANDROID_APK_FILENAME = 'location-release.apk';
const ANDROID_ADMIN_VERSION_CODE = 59;
const ANDROID_ADMIN_VERSION_NAME = '2.1.0';
const ANDROID_ADMIN_APK_FILENAME = 'location-admin-release.apk';
const ANDROID_ADMIN_FORCE_UPDATE = true;
const ANDROID_FORCE_UPDATE = true;
const IPINFO_LITE_TOKEN = ''; // https://ipinfo.io/
const IP2LOCATION_IO_KEY = ''; // https://www.ip2location.io/
const IPDATA_API_KEY = ''; // https://ipdata.co/
const IPREGISTRY_API_KEY = ''; // https://ipregistry.co/
const AMAP_JS_API_KEY = ''; // https://lbs.amap.com/
const AMAP_SECURITY_JS_CODE = '';
const AMAP_REVERSE_GEOCODE_KEY = AMAP_JS_API_KEY;
const AMAP_SERVICE_PROXY_PATH = '/_AMapService';
const APP_DEVICE_COOKIE_NAME = 'loc_device';

const DB_HOST = '127.0.0.1';
const DB_NAME = 'loc';
const DB_USER = 'loc';
const DB_PASS = '';
const DB_CHARSET = 'utf8mb4';

const REDIS_HOST = '127.0.0.1';
const REDIS_PORT = 6379;
const REDIS_DB = 0;
const REDIS_USERNAME = '';
const REDIS_PASSWORD = '';
const REDIS_CACHE_TTL_SECONDS = 15;
const REDIS_USER_HISTORY_TTL_SECONDS = 86400;

const CF_TURNSTILE_SITE_KEY = '';
const CF_TURNSTILE_SECRET_KEY = '';

const ADMIN_USERNAME = 'admin';
const ADMIN_PASSWORD = '';
const ADMIN_PASSWORD_HASH = '';
const ADMIN_PATH = 'admin';
const ADMIN_SOURCE_DIR = 'admin';

const LOCATION_HISTORY_LIMIT = 5000;
const LOCATION_STALE_SECONDS = 600;
const DEFAULT_REPORT_INTERVAL_SECONDS = 300;
const MIN_REPORT_INTERVAL_SECONDS = 60;
const MAX_REPORT_INTERVAL_SECONDS = 86400;
const MAX_LOGIN_FAILURES = 3;
const LOGIN_LOCK_SECONDS = 1800;
const APP_USER_AGENT_TOKEN = '';
const SESSION_LIFETIME_SECONDS = 2592000;
const MIN_LOCATION_REPORT_SECONDS = 10;
const MAX_LOCATION_ACCURACY_METERS = 5000;
const MAX_LOCATION_SPEED_MPS = 120;
const MAX_REASONABLE_TRAVEL_MPS = 120;
const LOCATION_DIAGNOSTICS_UPDATE_SECONDS = 600;
const MAX_ADDRESS_DIAGNOSTICS_BYTES = 12000;
const MAX_P2P_ENCRYPTED_PAYLOAD_BYTES = 128 * 1024;
const MAX_HISTORY_RESPONSE_ENCRYPTED_BYTES = 8 * 1024 * 1024;
const MAX_CLIENT_MERGE_SNAPSHOT_ENCRYPTED_BYTES = 8 * 1024 * 1024;
const MAX_HISTORY_RESPONSE_BYTES = 16 * 1024 * 1024;
const SCHEMA_MIGRATION_LOCK_TIMEOUT_SECONDS = 120;
const DATABASE_SCHEMA_VERSION = '20260717-group-alias-history-v1';
const GROUP_CODE_BACKFILL_SETTING_KEY = 'migration_group_codes_8_alias_v1';

$locGroupCodeBackfillRaw = getenv('LOC_GROUP_CODE_BACKFILL_ENABLED');
$locGroupCodeBackfillEnabled = true;
if ($locGroupCodeBackfillRaw !== false && trim($locGroupCodeBackfillRaw) !== '') {
    $locGroupCodeBackfillParsed = filter_var(
        $locGroupCodeBackfillRaw,
        FILTER_VALIDATE_BOOLEAN,
        FILTER_NULL_ON_FAILURE
    );
    if ($locGroupCodeBackfillParsed === null) {
        throw new RuntimeException('LOC_GROUP_CODE_BACKFILL_ENABLED must be a boolean value.');
    }
    $locGroupCodeBackfillEnabled = $locGroupCodeBackfillParsed;
}
define('LOC_GROUP_CODE_BACKFILL_ENABLED', $locGroupCodeBackfillEnabled);
unset($locGroupCodeBackfillRaw, $locGroupCodeBackfillEnabled, $locGroupCodeBackfillParsed);

