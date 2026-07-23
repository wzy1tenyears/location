# AGENTS

适用范围：本公开镜像仓库。

## 目录定位

- 本目录公开镜像 `v3/` 的 Go 后端以及去敏后的原生 Android 用户端、管理端和共享模块；不包含 APK、私有域名、服务器地址、凭据或签名材料。
- 私有实现先在 `..\v3\` 或 `..\v2\` 完成验证，再同步适合公开的源文件到这里；Android 的服务器地址和账号默认值必须保持通用占位值。
- 公开部署样例只能使用占位值，不能复制私有线上配置。

## 当前结构

- `cmd/server/`：Go 服务入口。
- `internal/`：按 `config / database / httpx / middleware / session / repositories / services / handlers` 分层。
- `deploy/`：systemd 与 Nginx 灰度样例。
- `android-client/`、`android-admin-client/`、`android-common/`：可公开构建的原生 Android 源码。
- `build-android.ps1`：使用外部 Gradle 9.5.0 构建未签名 Release 包。
- `build-v3.ps1`：本地构建入口。
- `verify-v3.ps1`：本地验证入口。

## 构建与验证

- 默认先跑：`.\verify-public.ps1`
- Android 构建验收：`.\verify-public.ps1 -BuildAndroid -Offline`
- 构建 Linux 二进制：`.\build-v3.ps1`
- 如果本机没有 Go，可通过 `LOC_GO_EXE` 指向便携 Go 的 `go.exe`。

## 边界

- 敏感配置只走环境变量，不写回源码。
- 同步后必须检查工作树、敏感字面量和公开部署样例，并保证 `go test ./...` 通过后再提交。
- 地图只接收 GPS 坐标与 GPS 地址；IP/WebRTC 诊断仅保留在地图下方的详细数据和历史位置文本中。
