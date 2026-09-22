#!/usr/bin/env bash
#
# 构建 L7VP 应用镜像（单 jar，前端静态已内嵌）。
#
# 做的事：定位 jar → 连同 schema.sql 复制到 app/ → docker build →（可选）导出离线包
#
# 用法：
#   ./build-image.sh                      # 用 java-server/target 下的 jar 构建
#   ./build-image.sh --export             # 构建并导出 dist/*.tar.gz（供离线拷贝）
#   ./build-image.sh --jar /path/to.jar   # 指定 jar
#   ./build-image.sh --base eclipse-temurin:17-jre-alpine
#   ./build-image.sh --tag l7vp-server:test
#
# 注意：构建【不联网】。Dockerfile 里没有任何 apk/网络操作，
#       只要基础镜像已在本地，全程可离线完成；--pull=false 保证不会去拉基础镜像。

set -euo pipefail

cd "$(dirname "$0")"

# ------------------------------------------------------------------ 默认值
IMAGE_TAG="l7vp-server:1.0.0"
BASE_IMAGE="eclipse-temurin:21-jre-alpine"
JAR_PATH=""
PLATFORM=""
DO_EXPORT="0"

APP_DIR="app"
JAR_NAME="l7vp-server-1.0.0.jar"

# ------------------------------------------------------------------ 参数解析
while [ $# -gt 0 ]; do
  case "$1" in
    --jar)      JAR_PATH="${2:-}"; shift 2 ;;
    --base)     BASE_IMAGE="${2:-}"; shift 2 ;;
    --tag)      IMAGE_TAG="${2:-}"; shift 2 ;;
    --platform) PLATFORM="${2:-}"; shift 2 ;;
    --export)   DO_EXPORT="1"; shift ;;
    -h|--help)  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1（用 --help 看用法）" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[36m[build-image]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[build-image]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31m[build-image]\033[0m %s\n' "$*" >&2; exit 1; }

# ------------------------------------------------------------- 前置环境检查
command -v docker >/dev/null 2>&1 || die "找不到 docker 命令"
docker info >/dev/null 2>&1       || die "docker 守护进程不可用（docker info 失败）"

# --------------------------------------------------------------- 定位 jar
if [ -z "$JAR_PATH" ]; then
  # 按顺序找：仓库里构建出来的 → 上次构建留下的副本
  for candidate in \
      "../java-server/target/${JAR_NAME}" \
      "${APP_DIR}/${JAR_NAME}"
  do
    if [ -f "$candidate" ]; then JAR_PATH="$candidate"; break; fi
  done
fi

if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
  cat >&2 <<'EOF'
找不到后端 jar。先构建它：

  cd ../java-server
  # 前提：java-server/lib/DmJdbcDriver8.jar 已就位（见 java-server/lib/README.md）
  mvn clean package -DskipTests

或者用 --jar 直接指定路径。
EOF
  exit 1
fi

JAR_PATH="$(cd "$(dirname "$JAR_PATH")" && pwd)/$(basename "$JAR_PATH")"
log "使用 jar: $JAR_PATH  ($(du -h "$JAR_PATH" | cut -f1))"

# ------------------------------------------------------------- 准备构建上下文
SCHEMA_PATH="../java-server/src/main/resources/schema.sql"
[ -f "$SCHEMA_PATH" ] || die "找不到 $SCHEMA_PATH"

mkdir -p "$APP_DIR"

# 逐个复制，并跳过「源和目标是同一个文件」的情况
# （jar 本来就放在 app/ 里时，裸 cp 会报 "are the same file" 并把整个构建中断）
copy_if_needed() {
  [ "$1" = "$2" ] && return 0
  cp -f "$1" "$2"
}

DEST_JAR="$(pwd)/${APP_DIR}/${JAR_NAME}"
DEST_SCHEMA="$(pwd)/${APP_DIR}/schema.sql"

copy_if_needed "$JAR_PATH" "$DEST_JAR"
copy_if_needed "$SCHEMA_PATH" "$DEST_SCHEMA"
log "已准备构建上下文：${APP_DIR}/{${JAR_NAME},schema.sql}"

# ------------------------------------------------------------------ 构建镜像
BUILD_ARGS=(--build-arg "BASE_IMAGE=${BASE_IMAGE}" --pull=false -t "$IMAGE_TAG")
[ -n "$PLATFORM" ] && BUILD_ARGS+=(--platform "$PLATFORM")

log "构建镜像 $IMAGE_TAG（基础镜像 $BASE_IMAGE）..."
docker build "${BUILD_ARGS[@]}" .

log "构建完成："
docker images --format '  {{.Repository}}:{{.Tag}}  {{.Size}}  ({{.CreatedSince}})' \
  | sed -n "1p;/^  ${IMAGE_TAG%%:*}/p"

# ------------------------------------------------------------------ 导出离线包
if [ "$DO_EXPORT" = "1" ]; then
  mkdir -p dist
  SAFE_NAME="$(printf '%s' "$IMAGE_TAG" | tr '/:' '__')"
  TARBALL="dist/${SAFE_NAME}.tar.gz"

  log "导出离线包 $TARBALL ...（镜像较大的话要等一会儿）"
  docker save "$IMAGE_TAG" | gzip > "$TARBALL"

  # 两段式（哈希 + 文件名）。只写哈希的话 `sha256sum -c` 会直接报
  # "no properly formatted checksum lines found" —— 老脚本就是这么坏的。
  ( cd dist && sha256sum "$(basename "$TARBALL")" > "$(basename "$TARBALL").sha256" )

  log "离线包就绪："
  ls -lh "$TARBALL" "${TARBALL}.sha256" | sed 's/^/  /'
  log "目标机校验方式：cd dist && sha256sum -c $(basename "$TARBALL").sha256"
  log "目标机导入方式：gunzip -c $(basename "$TARBALL") | docker load"
fi

log "全部完成。启动：docker compose up -d l7vp"
