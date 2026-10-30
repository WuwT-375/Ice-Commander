#!/usr/bin/env python3
"""Reproducible Linux cloud toolchain. All downloads retain TLS and digest checks."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

ROOT = Path('/workspace/toolchains')
ROOT.mkdir(parents=True, exist_ok=True)
PACKAGES = [
    ('jdk.tar.gz', 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz', 'sha256', '810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f', 'jdk-21.0.9+10'),
    ('gradle.zip', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip', 'sha256', 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6', 'gradle-8.11.1'),
    ('platform.zip', 'https://dl.google.com/android/repository/platform-36_r02.zip', 'sha1', '2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576', 'android-sdk/platforms/android-36'),
    ('build-tools.zip', 'https://dl.google.com/android/repository/build-tools_r35_linux.zip', 'sha1', '2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc', 'android-sdk/build-tools/35.0.0'),
    ('platform-tools.zip', 'https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip', 'sha1', '477254aa5f903c15cf51001717bdf347fb6b53e0', 'android-sdk/platform-tools'),
]

def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

for filename, url, algorithm, checksum, target_name in PACKAGES:
    archive = ROOT / filename
    if not archive.exists():
        temporary = archive.with_suffix('.download')
        subprocess.run(['curl', '-fSL', '--retry', '2', url, '-o', str(temporary)], check=True)
        if digest(temporary, algorithm) != checksum:
            raise RuntimeError(f'Checksum mismatch: {filename}')
        temporary.rename(archive)
    if digest(archive, algorithm) != checksum:
        raise RuntimeError(f'Checksum mismatch: {filename}; remove corrupt download and retry')
    target = ROOT / target_name
    if not target.exists():
        temporary = ROOT / (filename + '-unpack')
        temporary.mkdir(exist_ok=True)
        if filename.endswith('.tar.gz'):
            with tarfile.open(archive) as f:
                f.extractall(temporary, filter='data')
        else:
            with zipfile.ZipFile(archive) as f:
                f.extractall(temporary)
        children = list(temporary.iterdir())
        if len(children) != 1:
            raise RuntimeError(f'Unexpected archive layout: {filename}')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(children[0]), target)
        temporary.rmdir()
    for f in target.rglob('*'):
        if f.is_file():
            with f.open('rb') as stream:
                prefix = stream.read(4)
            if prefix.startswith(b'#!') or prefix == b'\x7fELF':
                f.chmod(f.stat().st_mode | 0o111)

(ROOT / 'ice-env.sh').write_text('''export JAVA_HOME=/workspace/toolchains/jdk-21.0.9+10
export ANDROID_HOME=/workspace/toolchains/android-sdk
export GRADLE_USER_HOME=/workspace/toolchains/gradle-home
export PATH="$JAVA_HOME/bin:/workspace/toolchains/gradle-8.11.1/bin:$ANDROID_HOME/platform-tools:$PATH"
''')
(ROOT / 'test-network.init.gradle').write_text('''allprojects {
    tasks.withType(Test).configureEach {
        ['https.proxyHost', 'https.proxyPort', 'http.proxyHost', 'http.proxyPort', 'javax.net.ssl.trustStore'].each { key ->
            def value = System.getProperty(key)
            if (value != null) systemProperty key, value
        }
        systemProperty 'robolectric.dependency.repo.url', 'https://repo.maven.apache.org/maven2'
    }
}
''')
(ROOT / 'build-ice.sh').write_text('''#!/usr/bin/env bash
set -euo pipefail
source /workspace/toolchains/ice-env.sh
network_args=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
  read -r proxy_host proxy_port < <(python3 -c 'import os,urllib.parse; u=urllib.parse.urlparse(os.environ["HTTPS_PROXY"]); print(u.hostname,u.port or 80)')
  network_args+=("-Dhttps.proxyHost=$proxy_host" "-Dhttps.proxyPort=$proxy_port" "-Dhttp.proxyHost=$proxy_host" "-Dhttp.proxyPort=$proxy_port")
fi
if [[ -f /etc/ssl/certs/java/cacerts ]]; then
  network_args+=("-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts")
fi
cd /workspace/Ice-Commander
exec gradle "${network_args[@]}" -I /workspace/toolchains/test-network.init.gradle --no-daemon --max-workers=2 "$@"
''')
(ROOT / 'build-ice.sh').chmod(0o755)
print('Toolchain ready. Run /workspace/toolchains/build-ice.sh testDebugUnitTest lintDebug assembleDebug')
