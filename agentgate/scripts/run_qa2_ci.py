"""Run the QA2 acceptance thresholds against the dedicated benchmark Compose stack."""
import os
from pathlib import Path
import shutil
import subprocess

from verify_qa5_onboarding import registered_read_tool


def main():
    parameters = [{"name": "id", "in": "path", "required": True, "schema": {"type": "string"}}]
    with registered_read_tool(8081, "http://benchmark-demo-tools:8082", "benchmark_read",
                              "/api/orders/{id}", parameters) as token:
        output = Path("target/c5")
        output.mkdir(parents=True, exist_ok=True)
        args = ["run", "--summary-export=target/c5/qa2-ci-summary.json", "scripts/benchmarks/qa2_ci.js"]
        env = {**os.environ, "AGENTGATE_TEST_TOKEN": token}
        if shutil.which("k6"):
            command = ["k6", *args]
        else:
            env["QA2_GATEWAY"] = "http://host.docker.internal:8081"
            command = ["docker", "run", "--rm", "--add-host", "host.docker.internal:host-gateway",
                       "-e", "AGENTGATE_TEST_TOKEN", "-e", "QA2_GATEWAY", "-v",
                       f"{Path.cwd() / 'scripts/benchmarks'}:/work/scripts/benchmarks:ro",
                       "-v", f"{output.resolve()}:/work/target/c5",
                       "-w", "/work", "grafana/k6:1.4.2", *args]
        subprocess.run(command, env=env, check=True)


if __name__ == "__main__":
    main()
