# Android 管理端

原生管理端包名为 `com.familylocation.admin`，与用户端分离，不包含定位上报服务。

- 修改 `assets/server-url.txt` 为自己的 HTTPS 服务地址后再构建；仓库默认值是 `https://example.com/`。
- 默认管理员用户名仅为通用占位值 `admin`，实际凭据由部署环境设置。
- 支持用户、家庭组、邀请码、公告、工单和更新管理；自定义邀请码为 4 至 64 位字母数字。

从仓库根目录运行 `build-android.ps1` 可生成未签名 Release 构建。生产签名材料必须留在仓库外。
