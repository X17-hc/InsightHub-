# CentOS Agent deployment

Deploy `agent-service-python` and `deploy` to `/opt/insighthub/agent`. Create `/etc/insighthub/agent.env` from `agent.env.example`; its internal token must match Windows Java's `AGENT_INTERNAL_TOKEN`. Start Redis/PGVector with `docker compose -f deploy/centos/docker-compose.agent.yml --env-file /etc/insighthub/agent.env up -d`, build the fixed image using `agent-service-python/Dockerfile.sandbox`, then install and enable the systemd unit.

Expose TCP 8000 only to the Windows host on the VM network. Do not expose Docker's socket, Redis, or PGVector TCP ports externally.
