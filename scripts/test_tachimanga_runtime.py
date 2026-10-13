#!/usr/bin/env python3
"""Offline settings smoke test with the runtime from a locally installed Tachimanga app."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--runtime', type=Path, default=Path('/Applications/Tachimanga.app/Wrapper/Tachimanga.app/MCHome.bundle/res.zip'))
parser.add_argument('--java-home', type=Path, default=Path(os.environ.get('JAVA_HOME', root / '.tooling/jdk')))
args = parser.parse_args()
if not args.runtime.is_file():
    parser.error('Tachimanga runtime not found. Supply --runtime with its MCHome.bundle/res.zip; do not redistribute it.')
classes = [root / 'extension/build/tmp/kotlin-classes/release', root / 'extension/build/intermediates/javac/release/classes']
if not all(path.is_dir() for path in classes):
    parser.error('Compile :extension:assembleRelease first.')
classpath = os.pathsep.join(str(path) for path in [args.runtime, *classes])
with tempfile.TemporaryDirectory(prefix='dropbox-settings-') as temp:
    subprocess.run([str(args.java_home / 'bin/javac'), '-cp', classpath, '-d', temp,
                    str(root / 'scripts/runtime/SettingsSmoke.java')], check=True)
    subprocess.run([str(args.java_home / 'bin/java'), '-cp', classpath + os.pathsep + temp,
                    'SettingsSmoke', 'actions', str(root / 'extension/build/outputs/apk/release/extension-release.apk')], check=True)
