# Ubuntu Agent deployment

This directory deploys the InsightHub Python Agent on the internal Ubuntu VM.
Ubuntu 25.10 is end-of-life and is permitted here only for development; move to
Ubuntu 26.04 LTS before any production use.

Run `bash deploy/ubuntu/bootstrap.sh` from the root of a complete checked-out
repository on the VM. Keep the root `pyproject.toml` and `uv.lock` with the
`agent-service-python` directory: this is a uv workspace, and the bootstrap
selects the Agent package when it installs locked runtime dependencies. The
command requires `sudo` and prompts without echoing for the internal token,
database password, DeepSeek key and Tavily key; it never supplies operational
defaults or writes any secret into the repository.
The Agent listens on port 8000; UFW allows that port and SSH only from the
Windows VM host `192.168.100.1`.

After deployment, verify `systemctl is-active insighthub-agent`, `docker
compose -f deploy/ubuntu/docker-compose.agent.yml ps`, and `curl
http://127.0.0.1:8000/health/ready`. Redis, PGVector, and the Docker API must not
listen on the VM network.
