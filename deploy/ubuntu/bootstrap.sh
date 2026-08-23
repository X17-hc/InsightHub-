#!/usr/bin/env bash
# Development-only bootstrap for the Ubuntu Agent VM. Run after copying this
# repository to /opt/insighthub/agent; secrets are never stored in this script.
set -euo pipefail

WINDOWS_HOST="${WINDOWS_HOST:-192.168.100.1}"
APP_ROOT="${APP_ROOT:-/opt/insighthub/agent}"
ENV_FILE=/etc/insighthub/agent.env
# The Ubuntu 25.10 repositories do not ship uv. Keep the exact official
# release and digest here so deployments remain reproducible without using a
# pipe-to-shell installer.
UV_VERSION=0.12.5
UV_SHA256=68a509da24b06b4223a1c0175fb5eb5bc79342b76cbeff0cfe51ac3f5b17b6b2

prompt_secret() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    read -r -s -p "${name}: " value
    echo
    if [[ -z "${value}" ]]; then
      echo "${name} must not be empty" >&2
      exit 2
    fi
    printf -v "${name}" '%s' "${value}"
  fi
}

# Prefer an interactive, non-echoed prompt. This prevents operators from
# exposing secrets in shell history or process command lines. Environment
# values remain supported only for non-interactive CI deployments.
prompt_secret AGENT_INTERNAL_TOKEN
prompt_secret POSTGRES_PASSWORD
prompt_secret DEEPSEEK_API_KEY
prompt_secret TAVILY_API_KEY

if ! command -v docker >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y docker.io docker-compose-v2
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required; Ubuntu 25.10 may need an upgrade to 26.04 LTS." >&2
  exit 3
fi
if ! command -v uv >/dev/null 2>&1; then
  uv_tmpdir=$(mktemp -d)
  trap 'rm -rf "${uv_tmpdir}"' EXIT
  uv_archive="${uv_tmpdir}/uv-x86_64-unknown-linux-gnu.tar.gz"
  # Download only the pinned official release; verify its published digest
  # before installing the single executable under /usr/local/bin.
  curl --fail --location --silent --show-error \
    "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz" \
    --output "${uv_archive}"
  printf '%s  %s\n' "${UV_SHA256}" "${uv_archive}" | sha256sum --check --status
  tar -xzf "${uv_archive}" -C "${uv_tmpdir}"
  sudo install -m 0755 "${uv_tmpdir}/uv-x86_64-unknown-linux-gnu/uv" /usr/local/bin/uv
  rm -rf "${uv_tmpdir}"
  trap - EXIT
fi

if ! id insighthub >/dev/null 2>&1; then
  sudo useradd --system --home-dir /opt/insighthub --create-home --shell /usr/sbin/nologin insighthub
fi
sudo usermod -aG docker insighthub
sudo install -d -o insighthub -g insighthub -m 0750 \
  /opt/insighthub/artifacts \
  /opt/insighthub/volumes/redis \
  /opt/insighthub/volumes/pgvector \
  /opt/insighthub/.uv-cache \
  /opt/insighthub/.uv-python
sudo install -d -o root -g insighthub -m 0750 /etc/insighthub

if [[ ! -d "${APP_ROOT}/agent-service-python" ]]; then
  echo "Expected Agent source at ${APP_ROOT}/agent-service-python" >&2
  exit 4
fi

# The release archive is extracted by sudo, so normalize ownership before
# creating the virtual environment as the least-privileged service account.
sudo chown -R insighthub:insighthub "${APP_ROOT}"

umask 077
{
  printf 'AGENT_INTERNAL_TOKEN=%s\n' "${AGENT_INTERNAL_TOKEN}"
  printf 'POSTGRES_PASSWORD=%s\n' "${POSTGRES_PASSWORD}"
  printf 'DEEPSEEK_API_KEY=%s\n' "${DEEPSEEK_API_KEY}"
  printf 'TAVILY_API_KEY=%s\n' "${TAVILY_API_KEY}"
  sed -e '/^AGENT_INTERNAL_TOKEN=/d' -e '/^POSTGRES_PASSWORD=/d' \
      -e '/^DEEPSEEK_API_KEY=/d' -e '/^TAVILY_API_KEY=/d' "${APP_ROOT}/deploy/ubuntu/agent.env.example"
} | sudo tee "${ENV_FILE}" >/dev/null
sudo chown root:insighthub "${ENV_FILE}"
sudo chmod 0640 "${ENV_FILE}"

sudo -u insighthub env \
  UV_CACHE_DIR=/opt/insighthub/.uv-cache \
  UV_PYTHON_INSTALL_DIR=/opt/insighthub/.uv-python \
  uv python install 3.11
# Dependencies are locked in agent-service-python/uv.lock. Select the Agent
# workspace member explicitly so its dependencies are installed.
sudo -u insighthub env \
  UV_CACHE_DIR=/opt/insighthub/.uv-cache \
  UV_PYTHON_INSTALL_DIR=/opt/insighthub/.uv-python \
  UV_PROJECT_ENVIRONMENT="${APP_ROOT}/.venv" \
  uv sync --frozen --no-dev --project "${APP_ROOT}" --package insighthub-agent-service

sudo systemctl enable --now docker
sudo -u insighthub docker compose -f "${APP_ROOT}/deploy/ubuntu/docker-compose.agent.yml" --env-file "${ENV_FILE}" up -d --wait
sudo -u insighthub docker build -t insighthub-analysis-sandbox:1.0.0 -f "${APP_ROOT}/agent-service-python/Dockerfile.sandbox" "${APP_ROOT}/agent-service-python"

if sudo ss -lntp | grep -Eq ':(2375|2376)\b'; then
  echo "Refusing deployment: Docker API is listening on TCP." >&2
  exit 5
fi

if ! command -v ufw >/dev/null 2>&1; then
  sudo apt-get install -y ufw
fi
sudo ufw allow from "${WINDOWS_HOST}" to any port 22 proto tcp
sudo ufw delete allow 8000/tcp >/dev/null 2>&1 || true
sudo ufw allow from "${WINDOWS_HOST}" to any port 8000 proto tcp
sudo ufw --force enable

sudo install -m 0644 "${APP_ROOT}/deploy/ubuntu/insighthub-agent.service" /etc/systemd/system/insighthub-agent.service
sudo systemctl daemon-reload
sudo systemctl enable --now insighthub-agent
sudo systemctl --no-pager --full status insighthub-agent
curl --fail --silent http://127.0.0.1:8000/health/ready >/dev/null
echo "Deployment succeeded. Ubuntu 25.10 is EOL; schedule migration to Ubuntu 26.04 LTS."
