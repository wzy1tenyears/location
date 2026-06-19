<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_admin_path();

unset($_SESSION['admin_logged_in']);
redirect('/' . admin_url_path() . 'login.php?admin_logout=1');
