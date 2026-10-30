#!/usr/bin/env python3
"""Small synthetic emulator evidence for environments unable to download artifacts."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile

api = os.environ["EVIDENCE_API"]
root = Path("android/app/build/reports/native-window-evidence/after")
files = {name: root / f"{name}.png" for name in ("welcome", "dashboard-cards", "editor", "settings")} if api == "29" else {"picker": Path("/tmp/myvault-picker.png")}
for name, source in files.items():
    if not source.exists():
        continue
    with tempfile.NamedTemporaryFile(suffix=".jpg") as output:
        result = subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(source), "-vf", "scale=160:-2", "-q:v", "18", output.name], check=False)
        if result.returncode:
            continue
        value = base64.b64encode(Path(output.name).read_bytes()).decode("ascii")
    parts = [value[i:i + 3000] for i in range(0, len(value), 3000)]
    for i, part in enumerate(parts):
        print(f"::notice title=API{api} image {name} {i+1}of{len(parts)}::{part}", flush=True)
