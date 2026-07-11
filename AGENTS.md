# AGENTS

适用范围：本公开镜像仓库。

## 目录定位

- 本目录公开镜像 `v3/` 的 Go 后端源码，不包含私有 Android 源码、APK、域名、服务器地址、凭据或签名材料。
- 私有实现先在 `..\v3\` 完成验证，再同步适合公开的源文件到这里。
- 公开部署样例只能使用占位值，不能复制私有线上配置。

## 当前结构

- `cmd/server/`：Go 服务入口。
- `internal/`：按 `config / database / httpx / middleware / session / repositories / services / handlers` 分层。
- `deploy/`：systemd 与 Nginx 灰度样例。
- `build-v3.ps1`：本地构建入口。
- `verify-v3.ps1`：本地验证入口。

## 构建与验证

- 默认先跑：`.\verify-v3.ps1`
- 构建 Linux 二进制：`.\build-v3.ps1`
- 如果本机没有 Go，可通过 `LOC_GO_EXE` 指向便携 Go 的 `go.exe`。

## 边界

- 敏感配置只走环境变量，不写回源码。
- 同步后必须检查工作树、敏感字面量和公开部署样例，并保证 `go test ./...` 通过后再提交。
