#!/usr/bin/env python3
"""Build the offline SDK package using only Python's standard library."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parent
output = root / 'offline-mock.meisource'
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as package:
    for name in ('manifest.json', 'index.js', 'assets/tone.wav'):
        package.write(root / name, name)
print(output)
