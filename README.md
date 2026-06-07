# 位置

一个基于 PHP + MySQL + Android WebView 的家庭定位共享项目。服务端部署在 PHP/Nginx/MySQL 环境中，手机客户端通过 WebView 访问服务端页面并上报位置。

## 授权说明

本项目采用双授权策略：

- 非商业用途：可按 GPL-3.0 使用、学习、修改和分发。
- 商业用途：必须先联系作者并取得单独书面授权。

注意：这不是无条件商业可用的 GPL-3.0-only 授权。任何商业产品、商业服务、公司内部业务、收费部署、代部署、SaaS 服务、软硬件打包销售或其他商业场景，都不在本仓库公开授权范围内。

## APK Release

因防止滥用，本项目不提供可直接安装的 APK release。

如果需要客户端，请自行审查源码、配置服务器地址、导入图标后在本地打包。不要使用来源不明的第三方 APK。

## 使用前必须修改

部署前先编辑：

```text
private/config.php
```

至少需要确认这些配置：

- MySQL 数据库地址、库名、账号和密码
- Redis 地址、端口、DB 和可选认证信息
- 后台登录账号和密码
- 后台访问路径 `ADMIN_PATH`
- 后台源码目录 `ADMIN_SOURCE_DIR`
- IP 探测相关 token 或接口配置

`private/config.php` 含敏感信息，线上 Nginx 必须禁止外部访问 `/private/`。

## 高德地图配置

项目使用高德地图 JS API 2.0。前端只暴露 JS API Key，安全密钥应留在服务器端，并通过 Nginx 的 `/_AMapService` 代理规则追加。

部署前需要修改：

```php
const AMAP_JS_API_KEY = '你的高德 JS API Key';
const AMAP_SECURITY_JS_CODE = '你的高德安全密钥';
const AMAP_SERVICE_PROXY_PATH = '/_AMapService';
```

同时把 `nginx-location.conf` 中的 `/_AMapService` 规则放入站点 `server { ... }`，并把 `你的高德安全密钥` 替换成真实值。

## Android 客户端准备

GitHub 发布版只保留 Android 客户端源码，不提供 APK、签名文件或打包脚本。打包软件前先准备两件事：

1. 导入应用图标

```text
android-client/res/drawable/app_icon.png
```

2. 写入服务器 URL

```text
android-client/assets/server-url.txt
```

示例：

```text
https://example.com/
```

建议使用 HTTPS，否则 Android WebView/浏览器定位权限可能受限。

## 目录结构

- `index.php`：统一入口，负责用户端页面输出和后台路径路由。
- `api/`：用户登录、定位上报、定位读取、版本检测、App 更新等接口。
- `assets/`：用户端前端资源。
- `admin/`：后台管理源码目录，实际目录名可通过 `private/config.php` 配置。
- `private/`：配置文件、公共库和数据库初始化文件，禁止公网访问。
- `android-client/`：Android WebView 客户端源码。
- `nginx-location.conf`：Nginx 站点规则片段。

## 部署流程

1. 上传仓库内容到支持 PHP 和 MySQL 的服务器。
2. 确认 PHP 已启用 `pdo_mysql`。
3. 编辑 `private/config.php`。
4. 导入或确认数据库初始化 SQL。
5. 将 `nginx-location.conf` 的规则放入对应站点的 `server { ... }` 中。
6. 确认 `/private/` 不能被公网访问。
7. 使用 Android 客户端访问网站根目录。

## Redis 缓存

项目支持可选 Redis 缓存，用于加速家庭组最新位置列表读取。Redis 不可用时会自动回退 MySQL，不影响原有逻辑。

配置位置：

```php
const REDIS_HOST = '127.0.0.1';
const REDIS_PORT = 6379;
const REDIS_DB = 0;
const REDIS_USERNAME = '';
const REDIS_PASSWORD = '';
const REDIS_CACHE_TTL_SECONDS = 15;
```

说明：

- `REDIS_DB` 可以改成你想使用的 Redis 数据库编号。
- Redis 没有 username 时，`REDIS_USERNAME` 留空。
- Redis 没有 password 时，`REDIS_PASSWORD` 留空。
- 只有填写了密码才会执行 Redis auth。
- 如果 PHP Redis 扩展未安装、Redis 连接失败或认证失败，程序会继续使用 MySQL。

## 主要功能

- 家庭组管理。
- 同一账号可加入多个家庭组。
- 同一账号在不同家庭组内可设置不同身份。
- 监测端持续上报位置。
- 监护端可手动上报，也可按家庭组开启持续上报。
- 地图显示同组成员位置。
- 历史定位记录查询、筛选和删除。
- 定位地址、IP 探测、WebRTC 探测三方对比。
- 后台管理账号、家庭组、上报频率和历史记录。
- App/Web 版本检测与刷新。

## 后台入口

后台不单独保留登录页，统一走根目录登录入口。

后台访问路径由 `private/config.php` 控制：

```php
const ADMIN_PATH = 'admin';
const ADMIN_SOURCE_DIR = 'admin';
```

访问地址示例：

```text
https://example.com/admin/
```

如果访问时没有末尾 `/`，入口会自动跳转到带 `/` 的地址，避免后台 CSS 和 JS 相对路径加载失败。

## 安全说明

- 用户端和 API 默认限制 `loc-app` User-Agent。
- 后台同样限制 `loc-app` User-Agent。
- 登录失败多次会临时锁定账号。
- 未同意用户协议和隐私条约的账号请求会被拒绝。
- 位置上报会做基础字段校验和地址一致性记录。

## 免责声明

本项目用于合法、知情、必要的家庭成员位置共享场景。不得用于跟踪、骚扰、侵犯隐私、冒用身份、上传虚假定位或其他违法违规用途。

定位、IP、WebRTC、地图服务、系统权限和网络环境都可能造成误差。本项目不应作为紧急救援、执法取证、人身安全判断或其他高风险场景的唯一依据。
