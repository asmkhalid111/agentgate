"""Generate a private .env on a fresh local checkout."""

import argparse
import os
from pathlib import Path
from secrets import token_hex


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--add-missing", action="store_true", help="Add newly required keys to an existing .env")
    args = parser.parse_args()
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
        if not args.add_missing:
            print("The existing .env was kept. See .env.example for the required keys.")
            return
        path = root / ".env"
        with path.open("r+", encoding="utf-8-sig") as output:
            existing = output.read()
            configured = {line.partition("=")[0].strip() for line in existing.splitlines()
                          if "=" in line and not line.lstrip().startswith("#")}
            missing = [key for key in keys if key not in configured]
            if missing:
                output.seek(0, os.SEEK_END)
                if existing and not existing.endswith("\n"):
                    output.write("\n")
                for key in missing:
                    output.write(f"{key}={token_hex(24)}\n")
        print(f"Added {len(missing)} missing local credential keys.")
    else:
        print("Created a private .env with local development credentials.")


if __name__ == "__main__":
    main()
