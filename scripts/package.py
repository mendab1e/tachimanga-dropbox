#!/usr/bin/env python3
"""Package a built APK and fail if any host-provided runtime classes were bundled."""
import hashlib
import pathlib
import re
import shutil
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
config = (root / 'extension/build.gradle.kts').read_text()
version = re.search(r'versionName = "([^"]+)"', config).group(1)
version_code = re.search(r'versionCode = (\d+)', config).group(1)
source = root / 'extension/build/outputs/apk/release/extension-release.apk'
with zipfile.ZipFile(source) as apk:
    dex = b''.join(apk.read(name) for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name))
    if not dex:
        raise SystemExit('APK contains no DEX code')
    # A class descriptor table contains references to external classes too. Inspect
    # class definitions with dexdump, rather than rejecting ordinary references.
    if any(name.startswith('lib/') for name in apk.namelist()):
        raise SystemExit('Unexpected native library in extension APK')

import os
import subprocess
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    local = root / 'local.properties'
    if local.exists():
        sdk = next((line.split('=', 1)[1] for line in local.read_text().splitlines() if line.startswith('sdk.dir=')), None)
if not sdk:
    raise SystemExit('Set ANDROID_HOME or local.properties to verify the APK class definitions')
dexdump = pathlib.Path(sdk) / 'build-tools/34.0.0/dexdump'
aapt = pathlib.Path(sdk) / 'build-tools/34.0.0/aapt'
badging = subprocess.run([str(aapt), 'dump', 'badging', str(source)],
                         check=True, capture_output=True, text=True).stdout
metadata = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
expected = ('eu.kanade.tachiyomi.extension.all.dropbox', version_code, version)
if metadata is None or metadata.groups() != expected:
    raise SystemExit('APK package/version differs from the configured release. Rebuild :extension:assembleRelease before packaging.')
import tempfile
with tempfile.TemporaryDirectory() as temp:
    path = pathlib.Path(temp) / 'classes.dex'
    with zipfile.ZipFile(source) as apk:
        for name in apk.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            path.write_bytes(apk.read(name))
            result = subprocess.run([str(dexdump), str(path)], check=True, capture_output=True, text=True)
            classes = re.findall(r"Class descriptor\s+: '([^']+)'", result.stdout)
            forbidden = [c for c in classes if not c.startswith(('Leu/kanade/tachiyomi/extension/all/dropbox/', 'Ldev/tachimanga/'))]
            if forbidden:
                raise SystemExit('Host/dependency classes leaked into the APK: ' + ', '.join(forbidden[:10]))
dist = root / 'dist'
dist.mkdir(exist_ok=True)
target = dist / f'tachiyomi-all.dropbox-v{version}.apk'
shutil.copyfile(source, target)
sha = hashlib.sha256(target.read_bytes()).hexdigest()
(target.with_suffix('.apk.sha256')).write_text(f'{sha}  {target.name}\n')
print(f'Packaged {target.name} ({target.stat().st_size:,} bytes)\nSHA-256: {sha}')
