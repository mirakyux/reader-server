# EPUB 导出补齐计划

用户已要求处理 FU 对照发现的缺口。本次在现有 EPUB 2 写入器、章节缓存、书源脚本和用户存储基础上补齐，不增加应用依赖，不改 FU 仓库，不写入真实存储联测数据。

1. 先补回归：PNG/透明封面转换真实 JPEG、实际图片格式与资源声明一致、WebP 入 manifest、缺图重试并拒绝损坏输出、卷目录层次、禁用净化的单本书、交互图片过滤、用户自定义模板上传/恢复/实际导出。
2. 图片下载复用 SourceActions.imageBytes/decodeImage，缓存遍历全部图片，按书源/书籍/URL 去重并原子写入。导出对既有漏图缓存自动补图，发现不能读取的必需图片时报告错误，不生成悬空包内引用。
3. 保留正文 XML 转义、UTF-8 和失败传播。封面使用 Java ImageIO 转 JPEG 并铺白透明底；目录复用 TOCReference 父子结构，净化同时尊重导出选项与书籍开关。WebP 与 WOFF2 补媒体类型声明。校验确认 EPUB 2 不能仅声明 WebP，最终复用现有浏览器服务将 WebP 正文图转 PNG、封面转 JPEG；转换失败时明确报错，不输出不兼容的正文图片。
4. 模板服务接受 FU 的 Asset ZIP 布局，按用户隔离并先验证再替换；正文使用 Text/chapter.html 或 chapter.xhtml，其他模板/样式/图片/字体资源复用已有 ResourceUtil。缺封面/简介时使用内置页。加入 authenticated get/save/deleteEpubTemplate 接口及 EPUB 导出样式折叠区域。
5. 针对测试通过后统一运行完整后端与前端检查。覆盖标准样本和含图片/卷/模板的隔离 HTTP 导出；运行 EPUBCheck，独立 EPUB 引擎/Edge 检查正文。构建 JAR 前停止主服务，构建后重启。保留真实书架和书源身份，关闭隔离服务并清理唯一测试文件。

并行工作范围：图片缓存与解码、模板服务、前端入口各由独立原生子代理负责；主代理负责导出集成、API 路由、资源类型、回归整合与最终验收。禁止并发 Gradle。

## 完成记录

2026-10-09 完成上述实现。图片缓存修改前回归复现 4 项失败；修复后后端完整构建和 114 项测试通过，前端 lint、typecheck、46 项单元测试、生产构建及 34 项 Edge 浏览器测试通过。独立浏览器服务 4 项真实浏览器测试通过，包含 WebP 转 PNG 的尺寸和全部像素核对、并发、失败恢复和无网络请求。

5 份 HTTP 图片／模板导出、真实书籍和 WebP 联测导出共 7 份样本，EPUBCheck 5.4.0 均为 0 错误、0 警告。另一个中文／空格文件名样本为 0 错误、5 条 PKG-010 空格文件名兼容警告；OPF、NCX、XHTML 和 CSS 的资源 URI 均编码并能解析，ZIP 保留用户原始文件名。

真实书《人在死牢马甲成圣》326 章、1,276,128 个忽略排版空白后的正文字符逐章与缓存一致，MuPDF 独立 EPUB 引擎能读取全部正文；首、中、末章与 WebP 图片章节在 Edge、React 桌面和 390 像素手机视图可见。模板上传和恢复通过真实 UI 接入验证。主服务已运行新版，1 本书和 10 个书源身份与先前记录完全一致；隔离联测服务已关闭。

导出流程删除旧 `fixPic` 和重复下载逻辑，复用 `SourceActions` 解码及现有 epublib、图片缓存和浏览器服务，没有增加应用依赖。启动脚本设置 Vite 子进程的 CI 环境，修正启动命令结束后 Vite 随输入管道关闭而退出的问题。

主要改动：`BookController.kt`、`YueduApi.kt`、`EpubImages.kt`、`EpubTemplateStore.kt`、`BookHelp.kt`、`SourceActions.kt`、`MediaTypes.java`、`ResourceUtil.java`、`StringUtil.java`、`PackageDocumentWriter.java`、`NCXDocumentV2.java`、`NCXDocumentV3.java`、`BookDownload.tsx`、`download.ts`、`browser/server.mjs`、`dev.ps1` 及对应回归。

证据：`logs/epub-fu-final-build.log`、`logs/epub-fu-web-*.log`、`logs/epub-fu-check-*.log`、`logs/epub-fu-real-check.log`、`logs/epub-fu-webp-check.log`、`logs/epub-fu-real-body.json`、`logs/epub-fu-live.json`、`logs/epub-fu-reader.json`、`logs/data-identity-epub-fu-after.json`、`build/epub-fu-browser`。可重新导入的最终样本为 `build/exports/人在死牢马甲成圣-FU对齐版.epub`。

边界：尚未在用户最初报告空白的具体阅读器确认，不能断言唯一根因。正文 WebP 转换依赖随应用启动的浏览器服务及其图片尺寸限制；自定义模板须使用 EPUB 2 支持的页面／图片格式，模板附加图片建议 JPEG、PNG、GIF 或 SVG，不能仅凭 MIME 声明把任意资源视为核心类型。含空格的模板资源文件名有已确认的旧阅读器兼容警告。
