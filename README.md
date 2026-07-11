# location v3

v3 是独立的 Go 后端目录，当前目标是承接线上全部 API，并让原生 App 成为后台管理的唯一入口。遗留网页后台不再作为管理入口对外开放。

## 当前状态

- v3 以无后缀的纯 Go 路径作为主接口形状。
- `/api/` 应直接反向代理到 Go 服务。
- 后台管理走 `android-admin-client` 原生界面；遗留网页后台路径应在 Nginx 上显式返回 `410 Gone`。

本仓库仅包含可公开的 Go 后端源码、测试与通用部署样例，不包含 Android 私有源码、APK 或线上基础设施配置。

## 配置

敏感配置全部从环境变量读取，不写入源码：

- `LOC_DB_HOST`
- `LOC_DB_PORT`
- `LOC_DB_NAME`
- `LOC_DB_USER`
- `LOC_DB_PASS`
- `LOC_APP_SIGNING_SECRET`
- `LOC_PUBLIC_BASE_DIR`
- `LOC_ANDROID_VERSION_CODE`
- `LOC_ANDROID_ADMIN_VERSION_CODE`
- `LOC_ADMIN_PASSWORD` / `LOC_ADMIN_PASSWORD_HASH`

`LOC_APP_SIGNING_SECRET` 用于 App Challenge 与后台 APK 下载令牌签名，必须至少 32 个字符，并且不能再回退复用数据库密码。服务启动时会校验数据库、管理员凭据、签名密钥和 APK 相对路径，配置不完整会直接拒绝启动。

## 本地运行

```powershell
cd location
$env:LOC_DB_PASS = '<database password>'
$env:LOC_APP_SIGNING_SECRET = '<token signing secret>'
$env:LOC_ADMIN_PASSWORD = '<admin password>'
$env:LOC_PUBLIC_BASE_DIR = '.\public'
go run .\cmd\server
```

`LOC_PUBLIC_BASE_DIR` 指向实际托管 APK 的目录。

当前 v3 已覆盖当前线上所需 API。
当前会话也已由 Go 自己管理，不再依赖旧文件式 session 目录。
数据库首次启动使用 `schema_core.sql` 建立基础表，后续结构变化按 `internal/database/migrations/` 中的版本文件顺序执行并记录到 `schema_migrations`。

## 验证

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\verify-v3.ps1
```

发布验证必须找到 Go 工具链并执行 `go test ./...`。仅需要检查目录、路由和敏感字面量时，可显式传入 `-StaticOnly`；该模式不能作为发布通过依据。

## 构建

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\build-v3.ps1
```

默认会输出 Linux `amd64` 二进制到 `bin/family-location-go-linux-amd64`，用于后续服务器灰度部署。

## 灰度部署

- `deploy/family-location-go.service.sample`：systemd 示例，敏感环境变量放到 `/etc/family-location-v3.env`。
- `deploy/nginx-go-backend.sample.conf`：Nginx `/api/` 直接切到纯 Go v3、并禁用旧网页后台的样例。
- 旧 APK 只保留 `app_update.php` 与 `admin_app_update.php` 两个自动更新兼容入口，其余接口只使用无后缀路径。
