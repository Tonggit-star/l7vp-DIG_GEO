# offline-deploy/ — L7VP 单机容器化部署与本地镜像验证

把 `java-server` 打成**自包含镜像**（Java 运行时在镜像里，前端静态内嵌在 jar 里），
配套一份 compose 编排应用 + 时序库 + Flink 三件套。

目标机只要有 Docker 即可，**不需要装 Java，也不需要独立 nginx**。

> 本目录是重建的。仓库里原本没有它——`CLAUDE.md` 大篇幅描述过 `offline-deploy/`，
> 但那个目录从未被提交进这个仓库（只有 `archive/offline-deploy-merged/` 残留了两份
> 更早的单服务 compose）。`Dockerfile` 是根据本机已有的 `l7vp-server:1.0.0` 镜像
> 用 `docker history` 还原的，逐字对齐，并已实测重建出的镜像与原镜像配置完全一致。

---

## 一、依赖矩阵：什么必须、什么可选

| 依赖 | 作用 | 必须吗 | 没有会怎样 |
|---|---|---|---|
| **达梦 DM8** | 9 张核心业务表 | **必须** | 应用**能启动**，但所有 `/api/*` 业务接口返回 500 |
| 前端静态资源 | 页面本体 | 已满足 | 已内嵌进 jar，无需处理 |
| Flink 集群 | 流式数据集作业 | 可选 | 只是流式功能不可用 |
| 时序库 | 历史轨迹查询 | 可选 | 「右键 → 历史轨迹」返回 503 |
| Kafka | 流式数据源 | 可选 | 只有配了流式的数据集受影响 |
| 数据中台 HTTP | 中台数据资源 | 可选 | `l7vp.auth.mode=local` 下本就 401 |

**只有达梦是硬依赖。** 只想验证界面/交互类改动的话，可以只起 `l7vp` 一个服务，
接受业务接口 500 —— 页面、路由、静态资源、登录态都是正常的。

---

## 二、前置条件

### 1. 达梦 JDBC 驱动（构建期，必做）

```bash
mkdir -p ../java-server/lib
cp /path/to/DmJdbcDriver18.jar ../java-server/lib/DmJdbcDriver8.jar   # 注意改名
```

**文件名必须是 `DmJdbcDriver8.jar`**，详情与自检方法见
[`../java-server/lib/README.md`](../java-server/lib/README.md)。
不放的话 `mvn package` 会在依赖解析阶段直接失败。

这个 jar 是商业授权文件，**不入库**，每台构建机都要自己放一次。

### 2. 达梦数据库实例（运行期，必做）

达梦**不提供公开 Docker 镜像**，只能用官网下载的 tar 包导入：

```bash
docker load -i dm8_20250521_x86_kylin10_64_rq_ent_8.1.4.80.tar
docker run -d --name dm8 --restart=always --privileged=true \
  -p 5236:5236 \
  -e PAGE_SIZE=16 -e EXTENT_SIZE=32 -e LOG_SIZE=1024 -e UNICODE_FLAG=1 \
  -e SYSDBA_PWD=SYSDba2026 \
  -v /opt/dm8data:/opt/dmdbms/data \
  dm8:dm8_20250521_rev270902_x86_kylin10_64
```

⚠️ **试用版 license 会过期**（启动日志会打印 `License will expire on ...`）。
长期使用需要正式授权。

首次建库后**必须手工执行建表脚本**——后端不会自动建表（`spring.sql.init.mode=never`）：

```bash
# 脚本在镜像里带了一份，也可以直接用仓库里那份
docker exec -i dm8 /opt/dmdbms/bin/disql SYSDBA/SYSDba2026 <<'SQL'
  -- 把 schema.sql 全文粘进来执行
SQL

# 或者取出脚本文件，用 DM 客户端执行
docker run --rm --entrypoint cat l7vp-server:1.0.0 /opt/l7vp/schema.sql > schema.sql
```

`schema.sql` 是**幂等**的，重复执行无副作用。

> ✅ 已修复（2026-09）：`schema.sql` 原先只建 9 张表，代码却在用第 10 张 `DB_CONNECTIONS`
> （`DbConnectionRepository`，「数据源连接」功能依赖它），DDL 单独躺在 `doc/sql/v2-new-tables.sql` 里，
> 照本 README 建库的新环境会漏掉它、`/api/db-connections/*` 全部报「无效的表或视图名」。
> 现已把该表的 DDL 并入 `schema.sql`（第 10 节），**新环境只跑 `schema.sql` 即可**；
> `doc/sql/v2-new-tables.sql` 保留作历史参考，不必再单独执行。
> 老库（表已存在）重复执行 `schema.sql` 也不会改动任何既有数据。

---

## 三、快速开始

```bash
cd offline-deploy
cp .env.example .env          # 按需改端口和达梦口令

# 1) 先有 jar —— 需要 java-server/lib/DmJdbcDriver8.jar 就位
cd ../java-server && mvn clean package -DskipTests && cd ../offline-deploy

# 2) 构建镜像
./build-image.sh

# 3) 起服务
docker compose up -d                 # 默认：只起应用（日常开发/界面验证够用）
docker compose --profile full up -d  # 连时序库 + Flink 一起起（只在需要流式/历史轨迹时）
```

打开 `http://<宿主机IP>:3003/l7vp/`。

**注意路径前缀 `/l7vp/`**：前端 `publicPath` 是 `/l7vp/`，直连时必须带上，
否则页面白屏。根路径 `/` 只有 `index.html`。

### 常用操作

```bash
docker compose ps                    # 状态（能看到 healthy）
docker compose logs -f l7vp          # 应用日志
docker compose down                  # 停止（不删数据卷）
docker compose down -v               # 停止并删卷（会丢时序库历史数据！）
docker exec -it l7vp-server sh       # 进容器
```

---

## 四、目录与配置的分工

```
offline-deploy/
├── Dockerfile              镜像定义（从既有镜像还原）
├── docker-compose.yml      三服务编排
├── .env.example            宿主机端口 / 凭据 / 镜像选择  → 复制为 .env 使用
├── build-image.sh          构建镜像（可 --export 导出离线包）
├── config/
│   └── application.properties   Spring Boot 外部配置 → /opt/l7vp/config/
├── web/
│   └── config.js                前端运行时配置   → /opt/l7vp/web/
├── data/
│   ├── icons/                   图标库（可写）
│   └── thumbnails/              项目封面（可写）
└── app/                         构建上下文，由 build-image.sh 生成，不入库
```

### 配置放哪儿：判断依据只有一个 —— 这份配置的「读者」是谁

| 配置内容 | 放哪里 | 为什么 |
|---|---|---|
| 端口、达梦地址/账号/口令、Flink/TSDB 地址、登录模式 | `docker-compose.yml` 的 `environment:` | 读者是容器里的 Java 进程 |
| 容器内静态目录（`l7vp.icons.path` 等）、日志级别 | `config/application.properties` | 读者也是 Java 进程，但更贴近「容器路径」而非「部署参数」 |
| `wsPort`、`streamMaxWindow`、底图、中台地址 | `web/config.js` | **读者是浏览器** |
| 宿主机端口、达梦口令、镜像 tag | `.env` | 只用于替换 compose 里的 `${...}` |

三条容易踩的规则：

1. **`.env` 不会自动变成容器环境变量。** compose 的 `.env` 只做文件内插值。
   要让某个值进容器，必须在 `environment:` 里显式引用它。
2. **`config.js` 不能 env 化。** 它由 `index.html` 的 `<script src="/config.js">`
   在浏览器里加载，容器环境变量只活在服务端，浏览器看不到——放 `.env` 里不会有任何效果。
3. **别碰 `spring.datasource.hikari.connection-init-sql`。** jar 内置配置已有
   `SET SCHEMA DIG_GEO`，覆盖 URL/账号/口令不会顶掉它。而这个属性名带连字符，
   环境变量形式极易拼错，且**拼错不报错、静默用默认值**。

### 配置优先级

```
compose environment:  >  config/application.properties  >  jar 内置 application.properties
```

是**合并**不是替换——外部文件里没写的键继续沿用 jar 内置值，不必把内置配置抄一遍。

---

## 五、为什么这么设计（几个非显而易见的点）

**容器间一律用 service 名互访**（`flink:8081` / `l7vp:3001` / `timescaledb:5432`）。
DNS 只保证 service 名可解析，`container_name` 不保证——别改回 `localhost`，
那会指向容器自己。

**Flink 用 JobManager + TaskManager 两个 service，而不是单容器。**
官方 `flink` 镜像默认 `CMD` 是 `help`（会打印用法后直接退出），一个容器只跑一个角色。
`archive/` 里那份单容器 JM+TM 的 compose **没有传 `command`**，在这版镜像上起不来。

**Flink 版本必须与作业对齐。** `java-server/flink-job` 是按 **Flink 1.17.2** 编的，
所以 compose 默认 `flink:1.17.2-scala_2.12`。别换成 1.18。

**时序库换成了 `timescale/timescaledb-ha:pg17`。**
原部署用的是自建镜像 `kag-ts-postgis:latest`，仓库里没有它的构建脚本、本机也没有。
`timescaledb-ha` 同样自带 PostGIS 3，但**卷路径不同**
（`PGDATA=/home/postgres/pgdata/data`，所以要挂 `/home/postgres/pgdata`，
挂 `/var/lib/postgresql/data` 是无效的）。要换回原镜像：`.env` 里设 `TSDB_IMAGE=kag-ts-postgis:latest`。

**时序库数据卷名钉死为 `kag-njupt_timescale_data`**，衔接既有数据
（`stream_events` 历史轨迹表），避免换名后「数据看起来丢了」。

**镜像构建不联网。** Dockerfile 里没有任何 apk/网络操作，`--pull=false` 保证
不会去拉基础镜像，只要基础镜像在本地就能全离线完成。

---

## 六、验证与排错

### ⚠️ 不要拿 `/api/health` 当健康检查

它返回的是**硬编码字符串，完全不碰数据库**：

```json
{"status":"ok","message":"L7VP后端服务运行正常"}
```

达梦彻底连不上时，它**照样返回 200**。用它做健康探针会出现
「容器 healthy 但所有业务接口 500」的假健康状态。

`docker-compose.yml` 里那个 healthcheck 只用于判断「进程是否起来」，
**不代表功能可用**。要判功能可用，探一个真正走库的接口：

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3003/api/projects
# 200 = 达梦通了；500 = 达梦没通
```

### 自检清单

```bash
# 1) 进程活着
curl -s http://localhost:3003/api/health

# 2) 达梦通了（这一步才是关键）
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3003/api/projects

# 3) 前端静态资源正常
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3003/l7vp/umi.1d43bce4.js

# 4) 前端运行时配置是从宿主机挂载进去的那份（而不是镜像内置的）
curl -s http://localhost:3003/config.js | grep -c '改完这个文件'
```

### 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `mvn package` 报 `Could not find artifact com.dameng:DmJdbcDriver8` | 驱动 jar 没放 | 见 `../java-server/lib/README.md` |
| 所有 `/api/projects` 返回 500，但 `/api/health` 是 200 | 达梦没连上 | 检查 `DM_HOST`/口令；容器内 `DM_HOST` 默认 `host.docker.internal` |
| 报「无效的表或视图名」 | 没执行建表脚本 | 执行一次 `schema.sql` 即可（10 张表都含在内） |
| 页面白屏 | 访问路径少了 `/l7vp/` 前缀 | 用 `http://IP:3003/l7vp/` |
| 改底图/端口不生效 | 改错文件了（config.js 是浏览器读的） | 改 `web/config.js` 后**强刷浏览器**（Ctrl+F5） |
| 图标全 404 | `l7vp.icons.path` 指向了容器里不存在的目录 | 确认 `config/application.properties` 里是 `/opt/l7vp/icons` |
| 时序库起来了但连不上 | 卷挂错路径（HA 镜像的 PGDATA 不是默认路径） | 挂 `/home/postgres/pgdata` |
| 流式数据没有反应 | Flink 版本不对，或 `FLINK_AUTO_START=false` | 用 1.17.x 集群；确认数据集带 `metadata.kafka` |

---

## 七、离线导出与目标机部署

```bash
./build-image.sh --export            # 产出 dist/l7vp-server_1.0.0.tar.gz + .sha256
```

把整个 `offline-deploy/` 目录拷到目标机（`dist/` 里备齐镜像包），然后：

```bash
cd offline-deploy
cp .env.example .env                 # 改端口与达梦口令
sha256sum -c dist/*.sha256           # 校验完整性
gunzip -c dist/l7vp-server_1.0.0.tar.gz | docker load
docker compose up -d
```

> `.sha256` 是**两段式**（哈希 + 文件名）。只写哈希的话 `sha256sum -c` 会报
> `no properly formatted checksum lines found`——仓库里那份老的 `load-image.sh`
> 就是这么坏的，所以这里没用它。

### 端口

| 服务 | 宿主机默认 | 容器内 | 变量 |
|---|---|---|---|
| HTTP | 3003 | 3001 | `L7VP_HTTP_PORT` |
| WebSocket | 3002 | 3002 | `L7VP_WS_PORT_HOST` |
| Flink UI/REST | 8081 | 8081 | `FLINK_UI_PORT` |
| Flink RPC | 6123 | 6123 | `FLINK_RPC_PORT` |
| 时序库 | 5432 | 5432 | `TSDB_PORT` |

HTTP 宿主机默认取 3003 而不是 3001，是为了避开常见的 3001 占用
（历史上被 dataease 占过）。容器内固定 3001 与镜像 `EXPOSE` 一致。

---

## 八、尚未做的（已知缺口）

- **`build-image.sh` 没有做端口冲突预检查**——老的 `deploy-load-l7vp.sh` 有，
  起栈前会查 `.env` 里 5 个宿主机端口是否被占（含 `docker ps` 里别的容器已发布的端口，
  因为 Docker 关掉 userland-proxy 时容器端口在 `ss`/`netstat` 里看不到）。
  需要的话可以补。
- **没有一键部署脚本**。老的 `deploy-load-l7vp.sh`（导三个镜像 → 起栈 → 等 healthy →
  `--status`/`--logs`/`--down`）未重建，目前用 `docker compose` 原生命令。
- **`kag-ts-postgis:latest` 的构建脚本不在仓库里**，时序库默认用了公开替代镜像。
- **Flink 的 `config/` 与连接器 `jars/` 挂载点没做**——archive 版有
  （`./config:/opt/flink/conf.d:ro`、`./jars:/opt/flink/usrlib:ro`），
  但这版官方镜像走 `FLINK_PROPERTIES` 环境变量注入配置，所以改成在 compose 里写了。
  需要额外连接器 jar 时，再加 `./jars:/opt/flink/usrlib:ro` 挂载。
