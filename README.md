# 位置

一个基于 PHP + MySQL + 原生 Android 客户端的家庭定位共享项目。v2 公开版只保留原生 App、`api/` 和 Web 后台目录；根目录不再提供用户端 Web/PWA 页面。

## 授权说明

本项目采用双授权策略：

- 非商业用途：可按 GPL-3.0 使用、学习、修改和分发。
- 商业用途：必须先联系作者并取得单独书面授权。

注意：这不是无条件商业可用的 GPL-3.0-only 授权。任何商业产品、商业服务、公司内部业务、收费部署、代部署、SaaS 服务、软硬件打包销售或其他商业场景，都不在本仓库公开授权范围内。

## APK Release

因防止误用，本项目不提供可直接安装的 APK release。需要客户端时，请自行审查源码、配置服务器地址、导入图标后本地打包。

## 使用前必须修改

部署前先编辑：

```text
private/config.php
```

至少需要确认数据库、Redis、后台账号、后台路径、地图 Key、IP 探测 Token 和 Cloudflare Turnstile 配置。公开源码中的 `DB_PASS`、`ADMIN_PASSWORD` 和 `APP_USER_AGENT_TOKEN` 默认留空；部署时必须只在本地填写强凭据，优先使用 `ADMIN_PASSWORD_HASH`，不得把填写后的配置提交。App 令牌留空时接口会以 503 拒绝服务。`private/config.php` 含敏感信息，线上 Nginx 必须禁止外部访问 `/private/`。

## Android 客户端

公开版保留用户端和后台端 Android 源码，不提供 APK、签名文件或私有服务器地址。仓库中的两个地址文件默认并且必须保持空白；可以在未提交的本地工作树中临时写入自己的服务端 URL，也可以在 App 首次启动时手动填写：

```text
android-client/assets/server-url.txt
android-admin-client/assets/server-url.txt
```

示例：

```text
https://example.com/
```

不要把本地填写的地址提交到公开仓库；`.\verify-public.ps1` 会同时检查两个文件为空。

## 家庭组号与邀请码

- 新建家庭组使用 8 位小写英文字母或数字组号。
- 升级前已有的 32 位小写十六进制组号会作为兼容别名保留，新旧组号都可以继续加入同一个家庭组。
- 后台留空创建邀请码时默认生成 8 位小写英数字，也可以自定义 4 至 64 位英数字邀请码。
- 已经发出的历史英数字邀请码仍可继续用于注册，不会因新建规则收紧而失效。

`LOC_GROUP_CODE_BACKFILL_ENABLED` 默认是 `true`：新部署和常规升级会事务回填已有 32 位组号，并立即为新家庭组生成 8 位组号。需要分两代发布时，可在第一代显式设置为 `false`，暂时继续生成 32 位组号，同时上线能读取 8 位当前号和 32 位旧号的兼容代码；确认更早的 PHP worker 已停止并排空请求后，再删除该环境变量或改为 `true`。回填完成后，迁移标记会继续强制生成 8 位组号；不能回滚到只识别 32 位组号的更早版本。

## 历史停留与地图

历史接口保留全部原始定位记录，并在读取时把同一家庭组、同一成员、同一坐标系下以首个点为锚且距离不超过 25 米的连续记录合并为一次停留。历史列表和地图会显示首次上报时间、末次上报时间、停留时长和上报次数。

普通明文记录由服务端合并。P2P 记录只有在客户端请求 `client_merge_snapshot` 并收到服务端声明完整的历史快照后，才会在本机解密并重新合并，然后重算分页和每成员地图上限；快照不完整时不会用局部数据推断停留。

地图会直接显示 GPS、IP 和 WebRTC 坐标标记。IP 与 WebRTC 标记优先显示探测结果中最精确的可用地址，并保留区县、街道、详情、POI、邮编和坐标系等结构化字段。

## 构建说明

本项目可使用 Android SDK 命令行构建，不需要 Android Studio。公开仓库不附带私有签名文件，请自行配置签名和发布流程。

```powershell
.\android-client\build.ps1
.\android-admin-client\build.ps1
```

## 目录结构

- `api/`：用户登录、注册、定位、历史、工单、App 质询与版本检查接口。
- `admin/`：Web 后台源码目录，访问路径由 `private/config.php` 的 `ADMIN_PATH` 控制，默认 `/admin`。
- `private/`：配置、公共库和安装 SQL，必须禁止公网直接访问。
- `android-client/`：原生 Android 用户端源码。
- `android-admin-client/`：原生 Android 后台端源码。
- `nginx-location.conf`：Nginx 站点规则片段，根路径返回 404，后台走 `/admin`。

## 安全说明

- 根路径不提供用户网页，用户端通过原生 App 使用。
- API 默认限制 `loc-app` User-Agent，`api/app_challenge.php` 作为 App 前台 WebView Turnstile 质询页例外开放。
- 登录失败多次会临时锁定账号。
- 未同意用户协议、隐私条约和跨境加密传输协议的账号请求会被拒绝。
- 位置上报会做基础字段校验、地址一致性记录和异常日志。
- 原生 App 登录/注册可通过 `api/app_challenge.php` 桥接 Cloudflare Turnstile；App API 请求只处理 JSON，地图/逆地理和 Turnstile 质询才在前台按需加载 WebView。
- 公开版用户端可在本地做环境风险检测，但默认不上传已安装应用包名列表；发布前可运行 `.\verify-public.ps1` 检查公开版边界。

## 免责声明

本项目用于合法、知情、必要的家庭成员位置共享场景。不得用于跟踪、骚扰、侵犯隐私、冒用身份、上传虚假定位或其他违法违规用途。
