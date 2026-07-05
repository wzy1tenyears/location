# location v3

v3 是独立的 Go 后端目录，用来承接从 `v2` PHP 后端逐步迁出的接口。`v2` 继续保留现有 PHP 后端；`v3` 只负责 Go 服务本身，不反向污染 `v2/`。

## 当前迁移范围

- `GET /api/app_update.php`
- `GET /api/admin_app_update.php`
- `GET /api/announcement.php`
- `GET|POST /api/invite_check.php`
- `GET /api/me.php`
- `GET|POST /api/settings.php`
- `GET /api/locations.php`
- `POST /api/history.php`

这些接口保持原有 JSON 形状，便于在 Nginx 上按路径灰度转发。

## 目录定位

- `v2/`：现有线上 App + PHP 后端
- `v3/`：独立 Go 后端
- 当前默认状态仍应是 PHP 在线上接流；`v3` 只在灰度时按路径接管

## 配置

敏感配置全部从环境变量读取，不写入源码：

- `LOC_DB_HOST`
- `LOC_DB_PORT`
- `LOC_DB_NAME`
- `LOC_DB_USER`
- `LOC_DB_PASS`
- `LOC_PUBLIC_BASE_DIR`
- `LOC_PHP_SESSION_DIR`
- `LOC_LEGACY_BASE_URL`
- `LOC_ANDROID_VERSION_CODE`
- `LOC_ANDROID_ADMIN_VERSION_CODE`
- `LOC_ADMIN_PASSWORD` / `LOC_ADMIN_PASSWORD_HASH`

非敏感默认值只用于本地开发。线上部署时应由 systemd 环境文件或进程管理器注入。

## 本地运行

```powershell
cd F:\program\location\v3
$env:LOC_DB_PASS = '<database password>'
$env:LOC_PUBLIC_BASE_DIR = 'F:\program\location\v2'
$env:LOC_LEGACY_BASE_URL = 'http://127.0.0.1:8081'
go run .\cmd\server
```

`LOC_PUBLIC_BASE_DIR` 指向实际托管 APK 和公开资源的目录；当前如果仍复用 `v2` 的 APK/资源，就应指向 `F:\program\location\v2`，而不是机械地写成 `v3`。

`LOC_LEGACY_BASE_URL` 用于未迁接口回退到旧 PHP 后端。切主流量到 `v3` 时，这个变量应指向仅本机可访问的旧后端入口，例如 `http://127.0.0.1:8081`。

本机当前未检测到 Go 工具链；安装 Go 后可直接运行上面的命令。

## 验证

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\verify-v3.ps1
```

有 Go 工具链时脚本会执行 `go test ./...`；没有 Go 工具链时仍会检查目录结构、路由覆盖、后台更新接口权限保护和敏感字面量。

## 构建

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\build-v3.ps1
```

默认会输出 Linux `amd64` 二进制到 `bin/family-location-go-linux-amd64`，用于后续服务器灰度部署。

## 灰度部署

- `deploy/family-location-go.service.sample`：systemd 示例，敏感环境变量放到 `/etc/family-location-v3.env`。
- `deploy/nginx-go-backend.sample.conf`：Nginx 主入口切到 `v3`、同时保留旧 PHP 回退入口的样例。
