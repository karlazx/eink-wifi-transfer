#!/usr/bin/env python3
"""使用 JDK 和 Android SDK 构建调试 APK，无需 Gradle 或 Android Studio。"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build'
SDK = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
TOOLS_VERSION = os.environ.get('EINK_BUILD_TOOLS', '34.0.0')
PLATFORM = os.environ.get('EINK_ANDROID_PLATFORM', 'android-34')
ZXING_SHA = '8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82'


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def main():
    tools = SDK / 'build-tools' / TOOLS_VERSION
    android = SDK / 'platforms' / PLATFORM / 'android.jar'
    if not android.exists() or not (tools / 'aapt').exists():
        sys.exit(f'缺少 Android SDK：需要 platforms;{PLATFORM} 和 build-tools;{TOOLS_VERSION}，可用 ANDROID_SDK_ROOT 指定路径。')
    java_home = os.environ.get('EINK_JAVA_HOME')
    if not java_home and sys.platform == 'darwin':
        java_home = subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()
    def java_tool(name):
        return str(Path(java_home) / 'bin' / name) if java_home else name
    BUILD.mkdir(exist_ok=True)
    dep = BUILD / 'deps' / 'zxing-core-3.5.3.jar'
    dep.parent.mkdir(exist_ok=True)
    if not dep.exists():
        print('下载 ZXing 3.5.3（首次构建需要联网）…', flush=True)
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar', timeout=60) as response:
            dep.write_bytes(response.read())
    if hashlib.sha256(dep.read_bytes()).hexdigest() != ZXING_SHA:
        sys.exit('ZXing 校验失败，请移除 build/deps 下该文件后重试。')
    classes, dex = BUILD / 'classes', BUILD / 'dex'
    for directory in (classes, dex):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    src = ROOT / 'app/src/main'
    packaged_assets = BUILD / 'assets'
    if packaged_assets.exists():
        shutil.rmtree(packaged_assets)
    shutil.copytree(src / 'assets', packaged_assets)
    licenses = packaged_assets / 'licenses'
    licenses.mkdir()
    shutil.copyfile(ROOT / 'LICENSE', licenses / 'MIT.txt')
    shutil.copyfile(ROOT / 'third_party/ZXing-LICENSE.txt', licenses / 'ZXing-LICENSE.txt')
    shutil.copyfile(ROOT / 'third_party/ZXing-NOTICE.txt', licenses / 'ZXing-NOTICE.txt')
    sources = sorted((src / 'java').rglob('*.java'))
    run(java_tool('javac'), '--release', '8', '-encoding', 'UTF-8', '-classpath', str(android) + os.pathsep + str(dep), '-d', classes, *sources)
    jar = BUILD / 'classes.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        for file in classes.rglob('*.class'):
            archive.write(file, file.relative_to(classes))
    # 直接运行 D8 的 Java 入口，确保与编译器使用同一 JDK。
    run(java_tool('java'), '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', android, '--output', dex, jar, dep)
    unsigned = BUILD / 'unsigned.apk'
    run(tools / 'aapt', 'package', '-f', '-M', src / 'AndroidManifest.xml', '-I', android, '-S', src / 'res', '-A', packaged_assets, '-F', unsigned, '--version-code', '1', '--version-name', '0.1.0')
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        for file in dex.glob('*.dex'):
            archive.write(file, file.name)
    aligned = BUILD / 'aligned.apk'
    run(tools / 'zipalign', '-f', '4', unsigned, aligned)
    key = BUILD / 'debug.keystore'
    if not key.exists():
        run(java_tool('keytool'), '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=EInk Transfer,C=CN')
    output = BUILD / 'eink-wifi-transfer-0.1.0-debug.apk'
    run(java_tool('java'), '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, aligned)
    run(java_tool('java'), '-jar', tools / 'lib/apksigner.jar', 'verify', output)
    print(f'构建完成：{output}\nSHA-256：{hashlib.sha256(output.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    main()
