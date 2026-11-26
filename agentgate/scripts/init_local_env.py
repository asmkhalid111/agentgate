"""Generate a private .env on a fresh local checkout."""

import os
from pathlib import Path
from secrets import token_hex


def main():
    root = Path(__file__).resolve().parents[1]
    keys = [line.partition("=")[0] for line in (root / ".env.example").read_text().splitlines()
            if line and not line.startswith("#")]
    try:
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        fd = os.open(root / ".env", flags, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            for key in keys:
                output.write(f"{key}={token_hex(24)}\n")
    except FileExistsError:
        print("The existing .env was kept. See .env.example for the required keys.")
    else:
        print("Created a private .env with local development credentials.")


if __name__ == "__main__":
    main()
