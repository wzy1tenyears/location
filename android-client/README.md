# Android 用户端

原生用户端包名为 `com.familylocation.client`，源码不包含服务器凭据、签名材料或可直接安装的 APK。

- 修改 `assets/server-url.txt` 为自己的 HTTPS 服务地址后再构建；仓库默认值是 `https://example.com/`。
- 页面切换复用高德地图 WebView，App 退出前台后才释放。
- IP/WebRTC 仅显示在地图下方的详细数据与历史位置文本，地图只绘制 GPS 数据。
- 无障碍保活必须由用户在系统设置中手动开启，服务不读取窗口内容、不执行手势或截图。
- `ip-api.com` 因供应商免费接口限制使用 HTTP，Network Security Config 只放行该精确域名，其他明文流量仍禁止。

从仓库根目录运行 `build-android.ps1` 可生成未签名 Release 构建。生产签名材料必须留在仓库外。
