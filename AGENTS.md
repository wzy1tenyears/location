# AGENTS

适用范围：`F:\program\location\github` 公开版仓库。

## 公开边界

- 这里是公开版，不要提交私有域名、服务器 IP、密码、证书、签名文件，或任何只该出现在私有版的部署细节。
- 不要加入 `environment_report.php`、`device_report.php`、激进设备探测、已安装应用包名上传，或其他私有环境/设备上报链路。
- 可以保留本地环境风险检测，但默认不要把已安装应用包名列表上传到服务端。
- 默认服务端地址不要写死为私有地址；发布前确认 `android-client/assets/server-url.txt` 和 `android-admin-client/assets/server-url.txt` 处于公开版可接受状态。

## 构建与验证

- 发布前优先运行 `.\verify-public.ps1`，它是公开边界的首选回归检查。
- Android 相关工作默认走命令行脚本，例如 `.\android-client\build.ps1` 和 `.\android-admin-client\build.ps1`，不要假设需要 Android Studio。
- 从私有树同步改动过来时，先删掉秘密、私有 URL、受保护下载路径和只在私有版成立的行为，再提交到公开版。

## 同步约定

- 从 `v2/` 或其他私有树同步时，优先同步用户可见功能、通用 bugfix 和不含隐私风险的验证脚本。
- 任何涉及私有部署、服务器后台、环境指纹或上传端点的实现，都应先证明公开化后仍然安全，否则不要同步。
