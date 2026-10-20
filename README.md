# 墨屏传文件 · EInk WiFi Transfer

给 Android 墨水屏阅读器用的局域网文件传输应用。阅读器打开应用，同一 Wi-Fi 下的手机或电脑用浏览器访问屏幕上的地址或扫描二维码，即可传输文件。

**支持全部文件类型**：不设扩展名、MIME 或内容白名单，包括 APK、字体、压缩包、自定义扩展名、无扩展名及零字节文件。只负责原样传输，不自动打开或安装。

## 首版功能

- 阅读器黑白界面、访问地址、二维码和接收文件列表。
- 网页多选串行上传、进度、取消和失败提示。
- 查看名称与大小、附件下载、确认删除；重名拒绝覆盖。
- 保存至共享存储 `EInkTransfer`，与原应用文件隔离。
- 上传中断清理临时文件；已完整提交的文件保留。
- 手动停止、确认退出或切换应用时停止服务。保持界面打开时防止休眠。

仅在可信局域网使用。服务没有账号或配对认证，开启期间同网用户可以管理接收目录。文件使用 HTTP 传输，不经过云端。

## 安装和使用

1. 安装本项目 APK，打开“墨屏传文件”，授予文件读写权限。
2. 确保阅读器与手机 / 电脑连接同一 Wi-Fi，关闭其他占用 8080 端口的传书应用。
3. 在浏览器输入阅读器显示的完整地址，或扫描二维码。
4. 选择文件开始传输，完成后可在网页下载 / 删除，或在阅读器查看接收列表。
5. 使用完毕点击“停止服务”或返回并确认退出。

包名 `io.github.einktransfer`，独立安装，不替换原厂传书应用。最低 Android 8.0 / API 26；已在 Likebook K78W、Android 8.1 实机验证。新版 Android 的共享存储机制尚未适配，首版不承诺兼容。

单文件防护上限为 2 GiB，仍受可用空间及文件系统限制；按用户要求未执行大文件测试，不承诺大文件性能。文件名 UTF-8 最多 240 字节，不能含路径或控制字符。

## 从源码构建

需要 Python 3.9+、JDK 17，以及 Android SDK 的 `platforms;android-34` 和 `build-tools;34.0.0`。无需 Gradle 或 Android Studio。首次构建联网从 Maven Central 获取 ZXing 3.5.3，校验固定 SHA-256，后续可离线构建。

```sh
export ANDROID_SDK_ROOT=/你的/Android/sdk
export EINK_JAVA_HOME=/你的/jdk-17
python3 scripts/build.py
```

macOS 默认查找 `~/Library/Android/sdk` 和系统登记的 JDK 17。其他环境可通过上述变量指定；SDK 版本可用 `EINK_BUILD_TOOLS`、`EINK_ANDROID_PLATFORM` 调整，但默认组合为本次验证配置。

生成 `build/eink-wifi-transfer-0.1.0-debug.apk`，可通过 ADB 安装：

```sh
adb install -r build/eink-wifi-transfer-0.1.0-debug.apk
adb shell am start -n io.github.einktransfer/.MainActivity
```

此版本为调试签名的首版预览。构建脚本在 `build/debug.keystore` 生成本机调试密钥，不提交到仓库；清理或更换密钥后，旧安装与新 APK 签名可能不同，需要卸载后重装。正式发布签名与应用商店分发留待后续安排。

## 验证

```sh
# 需要 JDK；监听回环地址的临时端口
python3 -m unittest discover -s tests -v
# 需要 Node.js 18+；隔离 DOM / 可控网络下测试真实网页队列脚本
node --test tests/test_web.js
# 阅读器已打开应用时，可选执行小文件实测并自动清理自建样本
python3 scripts/smoke_device.py --url http://阅读器地址:8080
```

测试仅在临时目录或唯一命名的自建样本上操作，不清理用户文件。实际测试范围和未验证项见 [M1 测试记录](docs/iterations/M1/TEST_REPORT.md)。

## 项目文档

- [需求与验收范围](docs/iterations/M1/PRD.md)、[交互说明](docs/iterations/M1/UX.md)
- [实现说明](docs/iterations/M1/IMPLEMENTATION.md)、[当前状态](docs/PROJECT_STATUS.md)
- [现有应用调研](docs/research/现有WiFi传书调研.md)、[路线图](docs/ROADMAP.md)
- [协作规范](AGENTS.md)、[第三方许可](THIRD_PARTY_NOTICES.md)

## 开源

本项目原创代码和文档采用 [MIT 许可证](LICENSE)。原厂应用仅用于行为研究，不收录其 APK、代码、网页、图片或用户文件。二维码组件 ZXing Core 按 Apache 2.0 许可使用，完整许可证与上游 NOTICE 同时随源码和 APK 提供。

后续功能根据实际需求逐个迭代；每个新迭代新建分支，实现和验证完成后合并 `main`。
