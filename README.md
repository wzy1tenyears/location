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

至少需要确认数据库、Redis、后台账号、后台路径、地图 Key、IP 探测 Token 和 Cloudflare Turnstile 配置。`private/config.php` 含敏感信息，线上 Nginx 必须禁止外部访问 `/private/`。

## Android 客户端

公开版保留用户端和后台端 Android 源码，不提供 APK、签名文件或私有服务器地址。打包前可写入服务端 URL，也可以保持为空并在 App 首次启动时手动填写：

```text
android-client/assets/server-url.txt
android-admin-client/assets/server-url.txt
```

示例：

```text
https://example.com/
```

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
- API 默认限制 `loc-app` User-Agent，`api/app_challenge.php` 作为浏览器 Turnstile 质询页例外开放。
- 登录失败多次会临时锁定账号。
- 未同意用户协议、隐私条约和跨境加密传输协议的账号请求会被拒绝。
- 位置上报会做基础字段校验、地址一致性记录和异常日志。
- 原生 App 登录/注册可通过 `api/app_challenge.php` 桥接 Cloudflare Turnstile。

## 免责声明

本项目用于合法、知情、必要的家庭成员位置共享场景。不得用于跟踪、骚扰、侵犯隐私、冒用身份、上传虚假定位或其他违法违规用途。