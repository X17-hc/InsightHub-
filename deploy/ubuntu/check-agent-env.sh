#!/usr/bin/env bash
set -euo pipefail

ENV_FILE=/etc/insighthub/agent.env
[[ -r "${ENV_FILE}" ]] || { echo "agent environment file is not readable" >&2; exit 2; }
set -a
# The file is root-controlled (0640) and contains only deployment variables.
source "${ENV_FILE}"
set +a

[[ "${APP_ENV:-}" == "production" ]] || { echo "APP_ENV must be production" >&2; exit 3; }
[[ "${AGENT_MOCK_LLM:-}" == "false" ]] || { echo "mock LLM is forbidden" >&2; exit 3; }
for name in AGENT_INTERNAL_TOKEN DEEPSEEK_API_KEY TAVILY_API_KEY POSTGRES_PASSWORD; do
  [[ -n "${!name:-}" && "${!name}" != replace-* ]] || { echo "required Agent configuration is missing: ${name}" >&2; exit 4; }
done
