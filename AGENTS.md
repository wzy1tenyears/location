# AGENTS

适用范围：`F:\program\location\v3`。

## 目录定位

- `v3/` 是 Go 后端目录，和 `v2/` 的 PHP 后端分离维护。
- 这里优先处理 Go 服务的目录组织、模块拆分、构建、验证、灰度部署，不在这里改 Android 客户端。
- `v2/` 仍是当前线上 PHP 版；如果任务只涉及当前线上 PHP 行为，不要误改到 `v3/`。

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
- `LOC_PUBLIC_BASE_DIR` 可以暂时指向 `v2/` 的公开资源目录；这是兼容过渡手段，不代表 `v3` 应把代码继续放回 `v2/`。
- 灰度接流前，先保证 `go test ./...` 通过，再逐个路径启用 Nginx 转发。
