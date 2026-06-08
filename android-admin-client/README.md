# Android 后台客户端源码

`android-admin-client` 是独立后台 App 源码，包名 `com.familylocation.admin`，与用户端 `com.familylocation.client` 分离。

## 服务器地址

公开版不会内置任何默认服务器地址。使用方式二选一：

1. 打包前在 `android-admin-client/assets/server-url.txt` 写入 HTTPS 服务端地址。
2. 保持 `server-url.txt` 为空，首次打开 App 时手动输入后台服务器地址。

示例：

```text
https://example.com/
```

## 打包说明

公开仓库不提供 APK、签名文件或构建产物。需要发布时请自行配置 Android SDK、签名和混淆流程。