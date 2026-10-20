# M2 · v1.0.0 测试记录

日期：2026-10-20。实机：Likebook K78W / Android 8.1 / API 27。

| 范围 | 结果 |
| --- | --- |
| 主机 HTTP 接口 | 15 项通过，覆盖全部类型、重名、中断、共享存储嵌套与特殊名称、只读方法拒绝、路径边界与符号链接 |
| 网页逻辑 | 8 项通过，覆盖队列、取消、失败继续、只读页无删除和 5 GiB 阈值 |
| 正式版实机小文件回归 | 31 项通过，含 EPUB/TXT/PDF/PNG/ZIP/APK/TTF/任意扩展名/无扩展名、零字节、中文特殊名称、哈希一致、同名竞争、中断重试 |
| 实机只读功能 | 11 项通过：自建共享目录浏览、下载哈希、两个接口的 DELETE/PUT/POST 均 405、旧删除接口不能越界、共享目录越界拒绝、网页含页签 |
| 文件管理器入口 | 从正式 App 按钮进入 `com.boyue.filemanager/.MainActivity`，再返回 App 正常恢复；打开的是管理器分类首页 |
| 正式包 | versionName 1.0.0 / versionCode 10000，非 debuggable，无安装权限，apksigner 验证通过，已安装实机 |

执行命令：

```sh
python3 -m unittest discover -s tests -v
node --test tests/test_web.js
python3 scripts/smoke_device.py --url http://阅读器地址:8080
python3 scripts/build.py --release
```

实机只读检查使用唯一命名的自建小目录和 18 字节样本，完成后全部清理；未删除、修改或公开用户既有文件。按钮通过 ADB 操作并检查前台 Activity；网页测试为隔离 DOM 的脚本逻辑验证，不宣称真实浏览器文件选择上传已实测。

5 GiB 限制通过前端伪文件元数据及服务端 Content-Length 边界验证；3 GiB 声明不因旧 2 GiB 阈值被拒绝，立即 EOF，无大文件数据传输。按用户要求不执行大文件实传，不承诺其性能；更高 Android 版本和其他型号未验证。

正式 APK SHA-256：`2729d28f7505ebfef4eebe67b6e693b6e41cb174b882454a4d0fefaea9b895de`。
