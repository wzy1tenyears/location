# Android 后台端源码

`android-admin-client` 是 v2 的独立后台 App，包名 `com.familylocation.admin`，与用户端 `com.familylocation.client` 分离。

- 主流程为原生界面；仅 Cloudflare Turnstile 质询等必要场景在前台按需使用 WebView，用完即释放。
- 不包含定位上报服务。
- 保留后台登录、服务端连通性和原生后台概览。
- 用户端 APK 不包含后台端包名、后台 Activity 或后台登录界面。

构建命令：

```powershell
.\build.ps1 -OutputApk ..\private\location-admin-release.apk
```
