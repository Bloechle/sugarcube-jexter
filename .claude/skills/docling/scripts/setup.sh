#!/usr/bin/env bash
# Install docling in the sandbox. Two profiles:
#   bash setup.sh          -> full docling (torch CUDA wheels from PyPI, ~4 GB on disk)
#   bash setup.sh light    -> docling-core + docling-parse only (no torch, seconds)
# The full profile still cannot run the ML pipeline here (huggingface.co is not an
# allowed egress host) - it only adds the CLI and the pipeline code. Use `light`
# unless you need `docling` on the path to test something else.
set -euo pipefail
PROFILE="${1:-full}"

df -h / | tail -1
# The sandbox has a small disk quota (~3 GB free is typical). The pip cache alone
# blew the first install (ENOSPC); purge it and always install with --no-cache-dir.
pip cache purge >/dev/null 2>&1 || true

if [ "$PROFILE" = "light" ]; then
  pip install --no-cache-dir --break-system-packages docling-core docling-parse pillow >/dev/null
else
  pip install --no-cache-dir --break-system-packages docling >/dev/null
fi

python3 - <<'EOF'
import importlib.metadata as m
for p in ("docling", "docling-core", "docling-parse", "docling-ibm-models", "torch"):
    try: print(f"{p:20s} {m.version(p)}")
    except m.PackageNotFoundError: print(f"{p:20s} -")
EOF
df -h / | tail -1

# Model weights: huggingface.co is blocked here. Say so instead of retrying.
if curl -s -o /dev/null -w "%{http_code}" https://huggingface.co >/dev/null 2>&1; then :; fi
code=$(curl -s -o /dev/null -w "%{http_code}" https://huggingface.co 2>/dev/null || echo 000)
if [ "$code" = "200" ]; then
  echo "huggingface.co reachable -> 'docling-tools models download' should work"
else
  echo "huggingface.co NOT reachable (HTTP $code) -> ML pipeline unavailable in this sandbox;"
  echo "run the real converter on a machine with network: bash scripts/run_real_docling.sh"
fi
