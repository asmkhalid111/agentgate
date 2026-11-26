"""Load private local development credentials without displaying their values."""

import os
from pathlib import Path


def credential(key: str) -> str:
    value = os.environ.get(key)
    if value is None:
        path = Path(__file__).resolve().parents[1] / ".env"
        if path.is_file():
            for line in path.read_text(encoding="utf-8-sig").splitlines():
                name, found, configured = line.partition("=")
                if found and name.strip() == key:
                    value = configured.strip().strip("\"'").strip()
                    break
    if not value or value == "<set-locally>":
        raise RuntimeError(f"Set {key} in the environment or AgentGate .env")
    return value
