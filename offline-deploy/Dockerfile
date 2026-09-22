# syntax=docker/dockerfile:1
#
# L7VP 应用镜像（单 jar，前端静态已内嵌在 jar 里，不需要独立 nginx）
#
# 构建上下文 = offline-deploy/ 目录，只需要 app/ 下的两个文件：
#   app/l7vp-server-1.0.0.jar   后端 fat jar（含内嵌前端 + 内嵌 flink 作业 jar）
#   app/schema.sql              达梦建表脚本（一并带进镜像，方便在目标机取出执行）
# 这两个文件由 build-image.sh 自动准备，不要手工维护。
#
# 基础镜像默认 eclipse-temurin:21-jre-alpine。换版本/换架构用 --build-arg BASE_IMAGE=...
# 注意：jar 本身是 Java 8 目标字节码，用 21 运行是被验证过的组合（线上就是 21）。
#
# 本文件是根据本机已有 l7vp-server:1.0.0 镜像的构建历史还原的，
# 命令行、目录布局、端口、entrypoint 均与原镜像逐字一致。

ARG BASE_IMAGE=eclipse-temurin:21-jre-alpine
FROM ${BASE_IMAGE}

LABEL org.opencontainers.image.title="L7VP Server" \
      org.opencontainers.image.description="L7VP 地理可视化平台后端（前端静态已内嵌于 jar）" \
      org.opencontainers.image.version="1.0.0"

WORKDIR /opt/l7vp

# 应用本体与建表脚本
COPY app/l7vp-server-1.0.0.jar /opt/l7vp/app.jar
COPY app/schema.sql            /opt/l7vp/schema.sql

# 四个外挂目录，运行时由 compose 挂载：
#   config/      Spring Boot 外部配置（application.properties）
#   web/         前端运行时配置（config.js）——浏览器读不到环境变量，只能给文件
#   icons/       图标库静态文件
#   thumbnails/  项目封面缩略图
RUN mkdir -p /opt/l7vp/config /opt/l7vp/web /opt/l7vp/icons /opt/l7vp/thumbnails

# 3001 = HTTP（jar 内置是 3003，容器里用 SERVER_PORT=3001 覆盖）
# 3002 = WebSocket 流式数据集（L7VP_WS_PORT）
EXPOSE 3001 3002

# JAVA_OPTS 故意不加引号：要支持在里面写多个 -D 参数，靠 shell 分词拆开。
# 末尾的 "$@" 让 docker-compose 的 command: 能追加 Spring Boot 命令行参数。
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -Duser.timezone=${TZ:-Asia/Shanghai} -Dfile.encoding=UTF-8 -jar /opt/l7vp/app.jar \"$@\"","--"]
