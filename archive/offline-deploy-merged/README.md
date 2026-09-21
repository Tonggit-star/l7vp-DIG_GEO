# 已合并作废：三份单服务 compose 与 sjk.env

2026-09-20 起，`offline-deploy/` 把 l7vp、Flink、时序库三件套整合成**一份**编排，
本目录里的文件已被取代，**不要再使用**，只作历史参考。

| 归档文件 | 内容被并入 / 为何作废 |
|---|---|
| `docker-compose - flink.yml` | `offline-deploy/docker-compose.yml` 的 `flink` service |
| `docker-compose - sjk.yml` | `offline-deploy/docker-compose.yml` 的 `timescaledb` service |
| `sjk.env` | `offline-deploy/.env` 末段（NEO4J_/KAG_ 变量，保留备查，本栈未使用）|
| `load-image.sh` | 只导 l7vp 一个镜像，已被 `deploy-load-l7vp.sh` 完全取代（导三个镜像 + 起栈 + 等健康 + `--status/--logs/--down`）。**它本身也是坏的**：内部用 `sha256sum -c` 校验，而当时 `build-image.sh` 写的 `.sha256` 只有 hash 没有文件名，会直接报 "no properly formatted checksum lines found" 并以 `set -e` 退出（`build-image.sh` 已改为写标准两段式）|

原 `offline-deploy/docker-compose.yml`（只有 l7vp 一个 service）也一并被新文件覆盖
（内容并入新文件的 `l7vp` service）。

现在的入口：

- 编排：`offline-deploy/docker-compose.yml`（三个 service：`timescaledb` / `flink` / `l7vp`）
- 变量：`offline-deploy/.env`（镜像、端口、时序库凭据）
- 一键部署：`offline-deploy/deploy-load-l7vp.sh`

容器名加了 `-l7vp` 后缀便于查询：`l7vp-server` / `flink-l7vp` / `timescaledb-l7vp`。
容器之间互访仍用 **service 名**（`l7vp` / `flink` / `timescaledb`）——DNS 只保证 service 名可解析。
