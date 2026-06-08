# Android 用户端源码

这里是 v2 的独立用户端 App，包名 `com.familylocation.client`，不包含后台端包名、后台 Activity 或后台登录界面。

## 目录

- `AndroidManifest.xml`：Android 权限和入口 Activity。
- `src/com/familylocation/client/MainActivity.java`：原生主界面、登录、更新和权限逻辑。
- `src/com/familylocation/client/KeepAliveService.java`：后台定位服务。
- `assets/server-url.txt`：服务器地址示例文件，打包前请改成你自己的 HTTPS 地址。
- `res/drawable/app_icon.png`：应用图标，打包前可替换。

## 打包说明

如需生成 APK，请自行使用 Android SDK/Gradle 或你自己的构建流程打包，并自行处理签名、混淆和发布审查。

因防止滥用，本项目不提供可直接安装的 APK release。
