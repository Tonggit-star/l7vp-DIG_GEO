# L7VP Docker 部署指南

> ## ⚠️ 本文档已废弃（2026-09）
>
> 它描述的 `deploy/` 目录（`backend/app.jar`、`backend/application.properties`、
> `l7vp-deploy-v2.tar.gz` 那套手工打包方式）**已从仓库删除**，本文中的路径与命令
> 在当前仓库里**都不存在**。
>
> 现行方式见 **[`offline-deploy/README.md`](offline-deploy/README.md)**：
> `./build-image.sh` 一键打自包含镜像（Java 内置）→ `dist/*.tar.gz` →
> 目标机 `./deploy-load-l7vp.sh` 一键部署（导三个镜像 + 起 L7VP/Flink/时序库三件套 + 等健康）。
> 早先那个 `load-image.sh`（只导单个镜像）已作废，移到 `archive/offline-deploy-merged/`。
>
> 另外提醒：老方案把配置目录 `/opt/l7vp/config/` 同时当成对外静态目录，
> 导致达梦口令可被 `curl http://IP:3001/application.properties` 下载；新方案已把
> `config/`（配置，不可访问）与 `web/`（静态覆盖）分开，**不要合并回去**。
>
> 以下内容仅作历史参考。
>
> ---

> 适用于 ARM64（鲲鹏/飞腾/麒麟）和 x86_64 架构的 Linux 服务器
> 测试环境：CentOS 7.6 ARM64 + Docker 26.1.4 + Docker Compose v2.27

---

## 一、部署包内容

```
l7vp-deploy-v2.tar.gz  (149MB)
解压后：
├── docker-compose.yml              # 编排文件
├── l7vp-images-v2.tar              # Docker 镜像包 (414MB)
├── backend/
│   └── application.properties       # 后端配置文件 ★需要修改★
├── data/
│   ├── icons/                       # 图标存储目录（持久化）
│   └── thumbnails/                  # 缩略图目录（持久化）
```

## 二、部署步骤

### 第一步：上传并解压

```bash
# 上传部署包到服务器
scp l7vp-deploy-v2.tar.gz root@<服务器IP>:/opt/

# SSH 登录服务器
ssh root@<服务器IP>

# 解压
cd /opt
tar -xzf l7vp-deploy-v2.tar.gz
mv docker l7vp    # 重命名目录（可选）
cd l7vp
```

### 第二步：导入 Docker 镜像

```bash
docker load -i l7vp-images-v2.tar
```

验证镜像：
```bash
docker images | grep l7vp
# 应显示 l7vp-backend:v2 和 l7vp-frontend:v2
```

### 第三步：修改配置

```bash
vi backend/application.properties
```

**必须修改**以下 4 项：

```properties
# 1. 达梦数据库连接（改为实际环境的值）
spring.datasource.url=jdbc:dm://实际达梦IP:5236/DIG_GEO
spring.datasource.username=实际用户名
spring.datasource.password=实际密码

# 2. 中台 SSO 认证（如需中台 API 功能）
zhongtai.sso.url=http://实际中台IP:8081/sso/oauth2/token
```

**可选修改**：

```properties
# 日志级别（排查问题时改为 DEBUG）
logging.level.com.antv.l7vp=INFO

# Token 缓存时间（默认 30 分钟）
zhongtai.token.cache-minutes=30
```

### 第四步：首次建表

在达梦数据库中执行建表脚本（`backend/schema.sql` 位于镜像内，可在容器中获取）：

```bash
# 从容器中提取 SQL
docker run --rm l7vp-backend:v2 cat /opt/l7vp/config/schema.sql > schema.sql
# 复制到有达梦客户端的机器上执行，或用 DM 管理工具连接执行
```

### 第五步：启动服务

```bash
docker compose up -d
```

### 第六步：验证

```bash
# 检查后端
curl http://localhost:3001/api/health
# 应返回 {"status":"ok","message":"L7VP后端服务运行正常"}

# 检查前端
curl http://localhost:8000/
# 应返回 HTML 页面

# 查看运行状态
docker compose ps
```

浏览器访问：`http://<服务器IP>:8000`

## 三、配置文件修改说明

### 完整配置项

```properties
# ========== 数据库 ==========
spring.datasource.driver-class-name=dm.jdbc.driver.DmDriver
spring.datasource.url=jdbc:dm://达梦IP:5236/DIG_GEO
spring.datasource.username=用户名
spring.datasource.password=密码

# ========== 文件上传 ==========
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=50MB

# ========== SQL初始化 ==========
# 首次部署用 always，之后改为 never
spring.sql.init.mode=never

# ========== 日志 ==========
logging.level.com.antv.l7vp=INFO

# ========== 图标存储路径（容器内，不要改）==========
l7vp.icons.path=/opt/l7vp/icons
l7vp.icons.url-prefix=/icons

# ========== 缩略图 ==========
l7vp.thumbnails.path=/opt/l7vp/thumbnails
l7vp.thumbnails.url-prefix=/thumbnails

# ========== 数据中台 SSO 认证 ==========
zhongtai.sso.url=http://中台IP:8081/sso/oauth2/token
zhongtai.sso.username=sjzt_admin
zhongtai.sso.password=sjzt_admin@4321
zhongtai.sso.grant-type=password
zhongtai.sso.client-id=clientId
zhongtai.sso.client-secret=clientSecret
zhongtai.api.space-id=default
zhongtai.api.scope-type=Space
# zhongtai.api.tenant-id=             # 项目工作区时启用
zhongtai.token.cache-minutes=30
```

### 修改配置后生效方式

```bash
docker compose restart backend
```

## 四、内网部署流程

拿到外网打包好的文件后，按以下顺序操作：

```bash
# 1. 将部署包复制到内网服务器
#    (刻盘/U盘均可，约 150MB)

# 2. 解压
tar -xzf l7vp-deploy-v2.tar.gz -C /opt/
cd /opt/l7vp

# 3. 导入镜像（不需要联网）
docker load -i l7vp-images-v2.tar

# 4. 修改配置
vi backend/application.properties
# 改达梦数据库连接、中台地址等

# 5. 启动
docker compose up -d

# 6. 验证
curl http://localhost:8000/
```

**整个内网部署过程不需要任何外网连接！**

## 五、常用运维命令

```bash
# 启动
docker compose up -d

# 停止
docker compose down

# 重启全部
docker compose restart

# 仅重启后端（修改配置后）
docker compose restart backend

# 查看日志
docker compose logs -f backend
docker compose logs -f frontend

# 进入容器排查
docker exec -it l7vp-backend sh
docker exec -it l7vp-frontend sh

# 查看容器状态
docker compose ps
```

## 六、数据备份

需要备份的数据（宿主机目录，与容器通过 volume 挂载）：

| 目录 | 内容 | 备份建议 |
|------|------|---------|
| `data/icons/` | 用户上传的图标 | 定期备份 |
| `data/thumbnails/` | 项目缩略图 | 定期备份 |
| `backend/application.properties` | 后端配置 | 备份一份 |

```bash
# 备份命令
tar -czf l7vp-data-backup.tar.gz data/ backend/application.properties
```

## 七、升级步骤

拿到新版本镜像后：

```bash
# 1. 停止服务
docker compose down

# 2. 导入新镜像
docker load -i l7vp-images-v2.tar

# 3. 更新 docker-compose.yml 中的镜像 tag（如有变更）

# 4. 重新启动
docker compose up -d

# 5. 清理旧镜像（可选）
docker image prune -a
```

## 八、网络架构

```
浏览器 :8000 → Nginx容器(前端) → 静态文件
                                   ↓ /api/*    /ws/*  (WebSocket)
                                 Java容器(后端:3001) → 达梦数据库
```

容器间通过 Docker 内部网络 `l7vp-network` 通信，nginx.conf 中 `proxy_pass http://backend:3001` 使用 Docker DNS 解析。

### 流式数据集 WebSocket 转发

单 jar 双端口：HTTP 在 3001，WebSocket 在 3002（`L7VP_WS_PORT` 默认 3002）。直连访问无需 nginx。nginx 反代时：

```nginx
location /ws/ {
    proxy_pass http://backend:3002;          # WS 独立端口
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}
```

`docker-compose.yml` 需同时暴露 3001 与 3002。若希望 WS 与 HTTP 同端口（3001），设 `L7VP_WS_PORT` 为空，`proxy_pass` 改回 3001。
流式数据集成方案详见 `doc/STREAM_DATASET_INTEGRATION.md`。

## 九、常见问题

**Q: 启动后前端 502 错误？**
A: 后端可能还在启动中（约 5 秒），等待后刷新。如果持续 502，检查后端日志：
```bash
docker compose logs backend
```

**Q: 达梦数据库连接失败？**
A: 检查 `backend/application.properties` 中的数据库 IP、端口、用户名、密码是否正确。确保达梦数据库允许该服务器的 IP 连接。

**Q: 镜像占用空间太大？**
A: 后端 338MB（含 JRE），前端 81MB。如需要精简可考虑使用 jre-slim 基础镜像，约可减半。

**Q: 端口冲突？**
A: 如果 3001 或 8000 端口已被占用，需要改两个文件：

1. `docker-compose.yml` — 改端口映射（左边是宿主机端口）：
```yaml
ports:
  - "13001:3001"   # 后端映射到 13001
  - "18000:8000"   # 前端映射到 18000
```

2. **不需要改** `application.properties` 和 `nginx.conf`——容器内部端口不变，只改对外暴露的端口。
