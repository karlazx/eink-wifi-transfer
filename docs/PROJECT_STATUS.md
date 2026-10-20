# 当前项目状态

更新日期：2026-10-20。

- 当前交付：M2，正式版 `v1.0.0`，文件管理器入口、共享存储只读浏览/下载、5 GiB 上传上限。
- 用户最终确认：上传后不用自动处理，用户在文件管理器自行打开文件或安装 APK。
- 迭代分支：`feature/m2-file-access-v1.0.0`；验证完成后合并 `main`，交付以 `main`、标签与 Release 为准。
- 验证：15 项主机接口、8 项网页逻辑、31 项实机小文件与 11 项只读检查通过；正式签名 APK 已安装 Likebook K78W / Android 8.1，文件管理器入口通过。
- 按用户要求不进行大文件实传。范围及限制见 [M2 测试记录](iterations/M2/TEST_REPORT.md)。
- MIT 开源：[GitHub 仓库](https://github.com/karlazx/eink-wifi-transfer)，[正式版下载](https://github.com/karlazx/eink-wifi-transfer/releases/tag/v1.0.0)。
- 发布密钥保存在本机 `.signing/`，应私下备份，不入库、不作为公开附件。

遵循 [协作规范](../AGENTS.md)，用户已禁用 sdd-governance；每个新迭代从最新 main 新建分支。后续按实际反馈确认需求，配对、后台、断点续传与更广设备兼容尚未纳入实现。
