# location v3

v3 是独立的 Go 后端目录，当前目标是承接线上全部 API，并让原生 App 成为后台管理的唯一入口。遗留网页后台不再作为管理入口对外开放。

## 当前状态

- v3 以无后缀的纯 Go 路径作为主接口形状。
- `/api/` 应直接反向代理到 Go 服务。
- 后台管理走 `android-admin-client` 原生界面；遗留网页后台路径应在 Nginx 上显式返回 `410 Gone`。
- `POST /api/share` 创建带分享码和有效期的位置分享；公开 `/share?token=...` 页面只有在分享码验证通过后才返回所选地图与历史位置数据。
- 明文历史按成员以首次坐标为锚点合并 25 米内的连续上报，并返回首次/末次上报时间、停留时长和上报次数；总数、分页与地图条数均以合并结果计算。
- App 可请求保留窗口内完整的原始历史快照，在客户端解密 P2P 记录后重算停留；大组通过 `/api/history-members` 分页选择成员，超出快照护栏时返回明确错误而不是静默截断。
- 新家庭组号和默认邀请码均为 8 位小写字母数字；旧 32 位十六进制组号继续作为唯一别名兼容读取，自定义邀请码支持 4 至 64 位字母数字。

## 公开隐私边界

登录后的私有历史会保留 IP/WebRTC 探测来源中可用的精确地址、坐标和候选证据，以便 App 展示诊断标记。公开分享则由服务端重新投影到严格白名单，只保留成员显示名、时间、公开地图坐标、城市、地址和必要的坐标系信息；`sources`、`variants`、`candidates`、IP 地址、WebRTC/STUN 与其他网络诊断字段不会进入公开分享响应，纯 IP 文本也不会作为地址回退公开。

## 目录定位

本仓库仅包含可公开的 Go 后端源码、测试与通用部署样例，不包含 Android 私有源码、APK 或线上基础设施配置。

- `cmd/server/`：Go 服务入口。
- `internal/`：业务、数据访问、会话、HTTP 与数据库迁移。
- `deploy/`：不包含私有服务器信息的 systemd 与 Nginx 样例。

## 配置

敏感配置全部从环境变量读取，不写入源码：

- `LOC_DB_HOST`
- `LOC_DB_PORT`
- `LOC_DB_NAME`
- `LOC_DB_USER`
- `LOC_DB_PASS`
- `LOC_GROUP_CODE_BACKFILL_ENABLED`
- `LOC_APP_SIGNING_SECRET`
- `LOC_PUBLIC_BASE_DIR`
- `LOC_ANDROID_VERSION_CODE`
- `LOC_ANDROID_ADMIN_VERSION_CODE`
- `LOC_ADMIN_PASSWORD` / `LOC_ADMIN_PASSWORD_HASH`

IP 地理位置供应商凭据按需配置：

- `LOC_IPINFO_LITE_TOKEN`
- `LOC_IP2LOCATION_IO_KEY`
- `LOC_IPDATA_API_KEY`
- `LOC_IPREGISTRY_API_KEY`

每个已启用供应商都有保守的默认上游预算。现有部署只配置凭据即可继续启动；实际套餐不同时，可用供应商前缀加以下后缀覆盖：

- `_QUOTA_MAX_REQUESTS`：套餐窗口内的请求上限
- `_QUOTA_RESERVE_REQUESTS`：为其他用途保留的请求数
- `_QUOTA_USER_MAX_MISSES`：单用户在同一窗口内可触发的缓存未命中上限
- `_QUOTA_WINDOW_SECONDS`：套餐计数窗口秒数

例如，IPinfo Lite 使用 `LOC_IPINFO_LITE_QUOTA_MAX_REQUESTS`、`LOC_IPINFO_LITE_QUOTA_RESERVE_REQUESTS`、`LOC_IPINFO_LITE_QUOTA_USER_MAX_MISSES` 和 `LOC_IPINFO_LITE_QUOTA_WINDOW_SECONDS`。缓存命中与合并请求不消耗供应商预算；显式配置不合法时服务会拒绝启动。

`LOC_APP_SIGNING_SECRET` 用于 App Challenge 与后台 APK 下载令牌签名，必须至少 32 个字符，并且不能再回退复用数据库密码。服务启动时会校验数据库、管理员凭据、签名密钥和 APK 相对路径，配置不完整会直接拒绝启动。

## 本地运行

```powershell
cd C:\path\to\location-v3
$env:LOC_DB_PASS = '<database password>'
$env:LOC_APP_SIGNING_SECRET = '<token signing secret>'
$env:LOC_ADMIN_PASSWORD = '<admin password>'
$env:LOC_PUBLIC_BASE_DIR = 'C:\path\to\public-assets'
go run .\cmd\server
```

`LOC_PUBLIC_BASE_DIR` 指向实际托管 APK 和公开资源的目录，不要求放在源码目录内。

当前 v3 已覆盖当前线上所需 API。
当前会话也已由 Go 自己管理，不再依赖旧文件式 session 目录。
数据库首次启动使用 `schema_core.sql` 建立基础表，后续结构变化按 `internal/database/migrations/` 中的版本文件顺序执行并记录到 `schema_migrations`。
`LOC_GROUP_CODE_BACKFILL_ENABLED` 默认启用：启动时会在数据库命名锁内把旧组号迁移为 8 位新组号，并保留旧值作为唯一别名；分阶段回滚准备期间可暂时设为 `false`，此时新写入仍使用旧 32 位格式。

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
