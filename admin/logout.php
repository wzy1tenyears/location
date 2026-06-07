<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_loc_app_page();
require_admin_path();

unset($_SESSION['admin_logged_in']);
redirect('/?admin_logout=1');
