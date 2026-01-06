#!/usr/bin/env python3
"""Update model.rs with correct size_bytes from HuggingFace URLs."""

import re
import urllib.request

MODEL_RS_PATH = "crates/spweeboard-core/src/model.rs"

def get_file_size(url: str) -> int | None:
    """Get file size via HTTP HEAD request."""
    try:
        req = urllib.request.Request(url, method="HEAD")
        req.add_header("User-Agent", "Mozilla/5.0")
        with urllib.request.urlopen(req, timeout=30) as resp:
            return int(resp.headers.get("Content-Length", 0))
    except Exception as e:
        print(f"  Error fetching {url}: {e}")
        return None

def main():
    with open(MODEL_RS_PATH, "r") as f:
        content = f.read()

    # Find all model blocks with url and size_bytes
    # Pattern matches: url: "...", followed eventually by size_bytes: N,
    pattern = r'(url:\s*"([^"]+)"[^}]*?size_bytes:\s*)(\d+(?:_\d+)*)'

    def replace_size(match):
        prefix = match.group(1)
        url = match.group(2)
        old_size = match.group(3)

        print(f"Fetching size for: {url.split('/')[-1]}")
        size = get_file_size(url)

        if size is None:
            print(f"  Keeping old value: {old_size}")
            return match.group(0)

        # Format with underscores for readability
        size_str = f"{size:_}".replace(",", "_")
        print(f"  {old_size} -> {size_str}")
        return f"{prefix}{size_str}"

    new_content = re.sub(pattern, replace_size, content, flags=re.DOTALL)

    with open(MODEL_RS_PATH, "w") as f:
        f.write(new_content)

    print("\nDone! Updated", MODEL_RS_PATH)

if __name__ == "__main__":
    main()
