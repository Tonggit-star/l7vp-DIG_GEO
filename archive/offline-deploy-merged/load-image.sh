#!/usr/bin/env bash
# ============================================================================
#  目标机（内网）导入离线镜像
#
#  用法：
#    ./load-image.sh dist/l7vp-server_1.0.0-amd64.tar.gz
#    ./load-image.sh dist/l7vp-server_1.0.0-amd64.tar.gz --up    # 导入后直接启动
#
#  .tar 与 .tar.gz 都支持（docker load 会自动识别 gzip）。
#  若同目录存在同名 .sha256，先做一次完整性校验。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() { echo "✗ $*" >&2; exit 1; }

TAR="${1:-}"
DO_UP=0
for arg in "$@"; do
  case "$arg" in
    --up) DO_UP=1 ;;
    -h|--help) ;;
  esac
done

if [ -z "$TAR" ] || [ "$TAR" = "-h" ] || [ "$TAR" = "--help" ] || [ "$TAR" = "--up" ]; then
  cat <<'EOF'
用法: ./load-image.sh <镜像包.tar|.tar.gz> [--up]

  <镜像包>   由 build-image.sh 在联网机器上导出的 dist/*.tar.gz
  --up       导入后直接 docker compose up -d（需先改好 config/）
EOF
  exit 2
fi

[ -f "$TAR" ] || die "找不到文件：$TAR"
command -v docker >/dev/null 2>&1 || die "未找到 docker 命令"
docker info >/dev/null 2>&1 || die "docker 守护进程不可用（未启动 / 无权限？）"

# ---- 完整性校验（有 .sha256 才做）----
if [ -f "$TAR.sha256" ]; then
  echo "==> 校验文件完整性"
  ( cd "$(dirname "$TAR")" && sha256sum -c "$(basename "$TAR").sha256" )
else
  echo "==> 未找到 $(basename "$TAR").sha256，跳过校验"
fi

echo
echo "==> 导入镜像（大包需要一两分钟，请稍候）"
docker load -i "$TAR"

echo
echo "==> 当前镜像"
docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}' | head -10

echo
echo "镜像已导入。接下来："
echo "  1) 改配置  $SCRIPT_DIR/config/application.properties（★达梦）"
echo "              $SCRIPT_DIR/config/config.js（★底图 / 中台地址）"
echo "  2) 启动    cd $SCRIPT_DIR && docker compose up -d"
echo "  3) 查看    docker compose ps && docker compose logs -f --tail=100 l7vp"
echo "  4) 访问    http://<服务器IP>:3001/l7vp/#/project"

if [ "$DO_UP" -eq 1 ]; then
  echo
  echo "==> --up：启动容器"
  ( cd "$SCRIPT_DIR" && docker compose up -d )
  docker compose -f "$SCRIPT_DIR/docker-compose.yml" ps || true
fi
