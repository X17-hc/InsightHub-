#!/usr/bin/env bash
# InsightHub Agent 一键安装（Ubuntu 22.04/24.04）
# 用法：sudo bash setup-agent.sh
set -euo pipefail

AGENT_ROOT="${AGENT_ROOT:-/opt/insighthub/agent}"
ETC_ENV="${ETC_ENV:-/etc/insighthub/agent.env}"
COMPOSE_FILE="${AGENT_ROOT}/deploy/centos/docker-compose.agent.yml"
SERVICE_FILE="${AGENT_ROOT}/deploy/centos/insighthub-agent.service"
SANDBOX_CTX="${AGENT_ROOT}/agent-service-python"
SANDBOX_IMAGE="${SANDBOX_IMAGE:-insighthub-analysis-sandbox:1.0.0}"
HOST_ALLOW="${HOST_ALLOW:-192.168.125.0/24}"

log() { echo -e "\n==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "请使用 sudo 运行"

log "检查代码目录"
[[ -d "${AGENT_ROOT}/agent-service-python" ]] || die "缺少 ${AGENT_ROOT}/agent-service-python"
[[ -f "${COMPOSE_FILE}" ]] || die "缺少 ${COMPOSE_FILE}"
[[ -f "${SERVICE_FILE}" ]] || die "缺少 ${SERVICE_FILE}"

log "安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl git ufw python3 python3-venv python3-pip docker.io docker-compose-v2
systemctl enable --now docker

# 国内环境默认配置 Docker Hub 镜像加速（可被已有 daemon.json 覆盖）
if [[ ! -f /etc/docker/daemon.json ]]; then
  log "写入 Docker registry-mirrors"
  mkdir -p /etc/docker
  cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run",
    "https://dockerproxy.net"
  ]
}
EOF
  systemctl restart docker
  sleep 2
fi

log "创建用户与目录"
id insighthub &>/dev/null || useradd -r -m -d /opt/insighthub -s /bin/bash insighthub
usermod -aG docker insighthub
mkdir -p /opt/insighthub/agent /opt/insighthub/artifacts \
  /opt/insighthub/volumes/redis /opt/insighthub/volumes/pgvector /etc/insighthub
chown -R insighthub:insighthub /opt/insighthub

resolve_token() {
  if [[ -n "${AGENT_INTERNAL_TOKEN:-}" ]]; then
    echo "${AGENT_INTERNAL_TOKEN}"
    return
  fi
  if [[ -f "${ETC_ENV}" ]]; then
    local t
    t="$(grep -E '^AGENT_INTERNAL_TOKEN=' "${ETC_ENV}" | tail -n1 | cut -d= -f2- | tr -d '\r')"
    if [[ -n "${t}" && "${t}" != "replace-with-the-same-secret-used-by-Java" ]]; then
      echo "${t}"
      return
    fi
  fi
  for f in \
    "${AGENT_ROOT}/.local-agent-token.txt" \
    /home/chang/insighthub/Demo/.local-agent-token.txt \
    "${AGENT_ROOT}/.env" \
    /home/chang/insighthub/Demo/.env
  do
    [[ -f "${f}" ]] || continue
    local t
    t="$(grep -E '^AGENT_INTERNAL_TOKEN=' "${f}" | tail -n1 | cut -d= -f2- | tr -d '\r' | tr -d '\"')"
    if [[ -n "${t}" && "${t}" != "change-me-in-local-env" && "${t}" != "replace-with-the-same-secret-used-by-Java" ]]; then
      echo "${t}"
      return
    fi
  done
  echo ""
}

TOKEN="$(resolve_token)"
[[ -n "${TOKEN}" ]] || die "未找到 AGENT_INTERNAL_TOKEN"

PG_PASS="${POSTGRES_PASSWORD:-}"
if [[ -z "${PG_PASS}" && -f "${AGENT_ROOT}/.env" ]]; then
  PG_PASS="$(grep -E '^POSTGRES_PASSWORD=' "${AGENT_ROOT}/.env" | tail -n1 | cut -d= -f2- | tr -d '\r' || true)"
fi
if [[ -z "${PG_PASS}" || "${PG_PASS}" == "replace-with-a-random-password" ]]; then
  PG_PASS="$(openssl rand -hex 16)"
fi

log "写入 ${ETC_ENV}"
cat > "${ETC_ENV}" <<EOF
AGENT_INTERNAL_TOKEN=${TOKEN}
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5432
POSTGRES_DB=insighthub_vector
POSTGRES_USER=insighthub
POSTGRES_PASSWORD=${PG_PASS}
REDIS_URL=redis://127.0.0.1:6379/0
ARTIFACT_ROOT_DIR=/opt/insighthub/artifacts
SANDBOX_IMAGE=${SANDBOX_IMAGE}
CHECKPOINT_BACKEND=postgres
CHECKPOINT_POOL_MAX_SIZE=10
EOF
if [[ -f "${AGENT_ROOT}/.env" ]]; then
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ "${line}" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || continue
    key="${line%%=*}"
    grep -q "^${key}=" "${ETC_ENV}" && continue
    echo "${line}" >> "${ETC_ENV}"
  done < "${AGENT_ROOT}/.env"
fi
chown root:insighthub "${ETC_ENV}"
chmod 640 "${ETC_ENV}"
chmod 755 /etc/insighthub
sudo -u insighthub test -r "${ETC_ENV}" || die "insighthub 仍无法读取 ${ETC_ENV}"

log "启动 Redis + PGVector"
cd "${AGENT_ROOT}"
runuser -u insighthub -- sg docker -c \
  "docker compose -f ${COMPOSE_FILE} --env-file ${ETC_ENV} up -d"
runuser -u insighthub -- sg docker -c \
  "docker compose -f ${COMPOSE_FILE} ps"

log "安装 Python 依赖"
if [[ ! -x "${AGENT_ROOT}/.venv/bin/python" ]]; then
  runuser -u insighthub -- python3 -m venv "${AGENT_ROOT}/.venv"
fi
runuser -u insighthub -- "${AGENT_ROOT}/.venv/bin/pip" install -U pip
runuser -u insighthub -- bash -lc \
  "cd ${SANDBOX_CTX} && ${AGENT_ROOT}/.venv/bin/pip install -e ."

log "构建 Sandbox 镜像（较慢）"
runuser -u insighthub -- sg docker -c \
  "docker build -f ${SANDBOX_CTX}/Dockerfile.sandbox -t ${SANDBOX_IMAGE} ${SANDBOX_CTX}"

log "安装 systemd 服务"
cp "${SERVICE_FILE}" /etc/systemd/system/insighthub-agent.service
systemctl daemon-reload
systemctl enable --now insighthub-agent
sleep 3
systemctl --no-pager --full status insighthub-agent || true

log "配置 ufw"
ufw --force reset >/dev/null 2>&1 || true
ufw default deny incoming
ufw default allow outgoing
ufw allow from "${HOST_ALLOW}" to any port 22 proto tcp comment 'ssh-from-lan'
ufw allow from "${HOST_ALLOW}" to any port 8000 proto tcp comment 'agent-from-lan'
ufw --force enable
ufw status numbered || true

log "健康检查"
for _ in 1 2 3 4 5 6 7 8; do
  if curl -fsS "http://127.0.0.1:8000/health"; then
    echo
    break
  fi
  sleep 2
done

echo
echo "========================================"
echo "安装完成: http://192.168.125.128:8000/health"
echo "env: ${ETC_ENV}"
echo "请重启 IntelliJ 后再启动 Java"
echo "========================================"
