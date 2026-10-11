# EPUB 正文显示兼容修复

用户报告导出的 EPUB 可以打开，但只能看到标题。当前真实样本包含 326 章，正文确实写入 XHTML；EPUBCheck 5.4.0 报告 543 个错误。因此不能将问题描述成缓存或正文丢失，也不能在未获得阅读器名称前断言单一根因。

1. 先给实际导出资源增加回归：章节标题不重复、正文属于 XHTML namespace、EPUB 2 使用 XHTML 1.1 声明、封面登记在 guide、不要求设备专属字体或包外文件。
2. 保留现有 EPUB 2 写入器及文本转义，参考 FU 的正文不包含标题及正常标题边距；删除旧字体路径表和未使用的多看样式，使用阅读器默认字体与颜色。
3. 修正封面 alt 和 OPF guide；省略空 guide，并删除强制多看正文字体 metadata。既有 TXT、章节顺序、缓存及用户数据保持原行为。
4. 对实际样本运行完整 EPUBCheck；逐章对比缓存正文，并在独立浏览器和本项目导入链路检查正文可见。完成后统一后端构建、重启主服务，生成可下载的新样本。

不增加应用依赖。EPUBCheck 仅下载到被忽略的 build/tools 用作验证工具。未能在用户具体阅读器验证时，应明确兼容性验证的边界。

## 修复与验证结果

2026-10-09 完成导出兼容修复。改动文件为 `BookController.kt`、`PackageDocumentMetadataWriter.java`、`PackageDocumentWriter.java`、`epub/chapter.html`、`epub/cover.html`、`epub/intro.html`、`epub/main.css`，删除 `epub/fonts.css`。回归位于 `EpubExportTests.java` 和 `BookDownloadIntegrationTests.java`。

删除约 800 行未使用的设备字体路径和样式，移除强制 `DK-SONGTI` 正文字体；默认字体及颜色由阅读器决定。使用 XHTML 1.1 声明、正确的封面 guide 和 alt，通用写入器省略空 guide；正文标题只在 heading 中输出一次，标题边距参考 FU 改为正值。保留现有转义、UTF-8 编码和 EPUB 2 写入器，没有引入应用依赖。

- 先增加失败回归：8 项针对测试中有 4 项在修复前失败；修复后全通过。
- 完整 `gradlew test build bootJar` 通过：89 项测试，0 失败、0 错误、0 跳过。`git diff --check` 通过。日志为 `logs/epub-final-build.log`。
- 真实书《人在死牢马甲成圣》326 章导出经 EPUBCheck 5.4.0 校验，从 543 个错误降为 0 错误、0 警告。报告为 `build/epub-fix-before-report.json` 和 `build/epub-fix-after-report.json`。
- 逐章与原始缓存比较，忽略排版空白后 1,276,128 字内容全部一致；没有重复标题，spine 含封面、简介及 326 章。证据为 `logs/epub-body-verification.json`。
- 用独立 MuPDF 1.28.2 EPUB 引擎排版成 4514 页，全部 326 章正文可提取；抽检首章、中间章、末章渲染图片，中文正文可见。临时验证工具仅安装在被忽略的 `build/tools/epub-render`。证据为 `logs/epub-independent-all-chapters.json` 和 `build/epub-render-chapter-*.png`。
- 在 18080/4173 隔离服务重新导入实际导出文件，恢复 328 个目录项；Edge 检查首章、中间章、末章资源，以及 React 桌面和 390 像素手机正文，全部通过，页面错误为空。证据为 `logs/epub-browser-verification.json` 和 `build/epub-browser`。

主服务已重启，原 1 本书及 10 个书源身份一致，主存储未被联测写入。修复样本保存在 `build/exports/人在死牢马甲成圣-修复版.epub`。旧 EPUB 需要重新导出和重新导入阅读器；本次未获得用户具体阅读器名称，不能宣称已在该软件或设备上复现并确认解决。
