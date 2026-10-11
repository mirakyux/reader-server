# 迁移与缺口补齐验收

2026-10-09 完成最新代码验收。运行环境为用户 nvm Node.js 24.15.0、Java 21.0.11 和 Windows Edge。React、Java API、浏览器执行服务独立运行，主入口为 `http://localhost:5173/`；API 为 8080，内部浏览器为 8090。`dev.ps1` 管理三个进程。

本文记录迁移及首轮缺口补齐的历史验收范围，不代表全面对齐 FU。[FU 完整功能对照](E:/workspace/Java/reader-dev/docs/fu-function-comparison.md) 保留实施前审计；按用户最新范围完成的书源、阅读、设备离线、RSS、外观与数据同步，见 [FU 非 AI 功能补齐验收](fu-parity-verification.md)。

首轮依赖版本表是历史快照。后续 Jackson 3 迁移和完整依赖复核已补升 Gradle 9.8.1 及 24 个 Maven 坐标，最新实际版本、验证与保留例外见[依赖复核与补漏结果](dependency-recheck.md)，不能从本表得出全部依赖为最新版本。

随后完成 EPUB 导出兼容修复，当时完整后端验收为 **89 项测试全部通过**。326 章实际导出经 EPUBCheck 5.4.0 校验，**0 错误、0 警告**；全部正文与缓存一致，独立 MuPDF 引擎以及 Edge 中重新导入后的桌面、手机阅读均能显示正文。删除设备专属字体路径及强制正文字体，规范 XHTML/OPF，并去掉重复章标题；具体变更、证据及设备验证边界见 [EPUB 兼容修复记录](epub-compatibility-plan.md)。旧文件需重新导出后导入阅读器。

后续 EPUB 对照补齐的完整验收为后端 **114 项测试**、前端 **46 项单元测试**和 **34 项浏览器测试**、浏览器服务 **4 项测试**，具体样本及边界见 [EPUB 与 FU 对照](E:/workspace/Java/reader-dev/docs/epub-fu-comparison.md)。下表保留首轮迁移验收结果。

## 完成的能力

| 范围 | 实现与验证 |
| --- | --- |
| 前后端分离及免费 UI | React、TypeScript、Vite、shadcn/ui、Radix、Tailwind；独立网页与 API 产物。移除 Vue 和桌面运行时，JAR 无首页、JavaFX 和桌面启动类 |
| 依赖升级 | Java 21、Gradle 9.7.1、Kotlin 2.4.21、Spring Boot 4.1.1、Vert.x 5.2.1、Rhino 1.9.1、React 19.3、Vite 8.3.4、TypeScript 6.0.3，以及锁文件中的兼容解析和 UI 依赖 |
| 书源 | 单文件 JS、共享库、CryptoJS、登录表单、Cookie 与用户隔离、未知字段往返、校验关键词、目录格式化及收费状态、文件下载地址、正文标题/歌词/弹幕、图片解码、购买回调、动态发现、段评读取与引用 |
| 网页规则及验证 | 独立真实 Edge/Chromium 执行 DOM，保留请求头、Cookie、最终 URL 和 POST 返回 HTML；交互截图支持点击、输入和键盘。`startBrowserAwait` 完成后返回页面并继续原脚本；`getVerificationCode` 展示实际图片、接收输入并返回验证码；超过 30 秒的人工等待回归通过 |
| 阅读配置 | 字体与背景上传/删除、配置方案保存/切换/导入/导出、旧 Vue 配置迁移；合并保存未知用户配置字段 |
| 阅读增强 | 标题/正文/排除范围替换、超时保护、范围书签与高亮、正文元数据展示；同源 EPUB 支持实际选区、跨章高亮及书签定位。前端正则在 Web Worker 执行，超时终止并保留该条处理前文本 |
| AI、Skills、MCP | 用户模型/提示词配置、正文净化与原文恢复、流式助手、持久会话与阅读工具、Skills、MCP 工具发现和调用；密钥脱敏与用户隔离 |
| 听书与统计 | 浏览器听书及远程角色声音映射、有效 MP3 合成和隔离缓存；仅统计可见阅读页面的活跃会话，按书籍和日期汇总 |
| 缓存与下载 | 整本章节缓存、进度、取消、失败重试、缺章拒绝导出；下载完整 TXT/EPUB，可应用替换规则且不改原文缓存。空卷标题正确保留，普通空正文会重取 |
| 备份恢复 | v2 清单、SHA-256、大小/路径/版本验证、有界读取、写入失败回滚；用户实体、配置、资源、AI/TTS、会话、统计及书源状态。跨用户恢复重绑已知字体/背景 URL，登录缓存用当前密钥重新加密 |

## 首轮自动验证

| 检查 | 该轮结果 |
| --- | --- |
| `gradlew test build bootJar` | 成功；87 项后端测试通过，失败、错误、跳过均为 0 |
| 书源及规则测试 | 44 项，包含图片验证码、安全图片 URL、长等待恢复、验证头不递归、用户及源隔离、CSS/XPath/JSONPath、现代源及未知字段 |
| HTTP 集成测试 | 16 项，覆盖认证/CORS、原生 MCP、完整下载、卷标题及空缓存、备份及资源跨用户恢复、浏览器 Java 桥、本地 EPUB/CBZ 公共资源及章节锚点 |
| React 单元测试 | 38 项通过，含首次轮询前结束的 SSE 交互投递和取消后迟到动作抑制 |
| React 浏览器回归 | Playwright/Edge 34 项通过，含 HTML/XHTML 选区、高亮、跨章恢复、助手和朗读上下文、连续听书等待正文，以及顶部导航、分组菜单和键盘焦点；测试服务器使用独立 15173 端口 |
| React 质量与构建 | ESLint 零错误、零警告；TypeScript 检查与 Vite 生产构建通过 |
| 浏览器服务回归 | 真实 DOM、鉴权、Cookie、网页存储、资源捕获、交互登录及完成后重新加载通过 |
| Java → 浏览器真实联测 | 6/6 条通过；真实 Edge DOM、GET 最终地址、POST HTML、人工登录及重试、等待验证后原脚本继续、图片验证码输入后继续 |
| 部署静态检查 | Git diff 空白、Shell/PowerShell 语法及 8 个 YAML 文件解析通过；JAR 无网页和桌面运行时 |

Gradle 在 `build/backend/test-workspace` 使用隔离存储；真实联测 API/Web/Browser 为 18080/4173/18090，工作目录为 `build/gap-live`。fixture 使用随机本地源与书籍，完成后关闭浏览器会话并清理临时源、书架、资源、书签及会话，恢复原配置。测试不向主 8080 写入 fixture。

后端报告：`build/backend/reports/tests/test/index.html`。构建与浏览器证据：`logs/gap-final-build.log`、`logs/gap-browser-service.log`、`logs/gap-browser-stack.log`。前端及真实联测日志保存在 `web/logs/gap-frontend.log`、`gap-browser-final.log`、`gap-advanced-live.log`、`gap-download-live.log`、`gap-epub-live.log`；截图在 `web/screenshots`。这些本地证据不纳入版本控制，也不受 Playwright 清理测试结果目录影响。

## 实际联测与数据保留

- 新 API、React 和浏览器服务健康检查为 200，API 根路径为 404，符合独立部署。
- 原有 1 本书和 10 个书源的 URL 集合与切换前完全一致，核对记录为 `logs/data-identity-gap-before.json` 和 `logs/data-identity-gap-after.json`。
- 使用最新 API，通过浏览器完成原始 JS 导入、动态验证码登录、搜索、入架、目录、阅读、整本缓存和实际 TXT/EPUB 下载。TXT 为 179 字节，EPUB 为 197401 字节，包含三个章节；解压检查 mimetype、ZIP CRC、XML 和正文完整性。
- 高级联测验证模型发现、净化/恢复、工具调用、流式答案与 6 条持久化消息、alloy/nova 角色 MP3 可播放、非零阅读统计、MCP，以及实际 WOFF2/背景上传、方案切换和高亮。
- 使用真实上传的两章 EPUB 验证 `application/xhtml+xml` 章节。选区跨越行内标签，后端持久化范围 `[11,20)` 与文字“真实阅读正文。重点”；重开后恢复三个标记并定位原文。实际字体、背景和简繁转换正常，阅读记录为 56 字符、4 秒。章节脚本和图片事件属性未执行，XHTML 命名空间保留，页面错误为空；结束后清理临时书籍、书签及字体。
- 检查桌面与 390 像素手机页面，无横向溢出，控制台和页面错误为空。截图包括书架、书源、阅读设置、助手、角色听书和移动阅读高亮。

验收完成后停止 18080/4173/18090 隔离服务，并删除失败联测遗留的唯一 EPUB 临时目录。主 API、React 和浏览器服务继续运行。隔离目录中的阅读统计与日志保留为验收证据。

## 简化与主要改动

导航布局另见 [layout-plan.md](layout-plan.md)：删除常驻侧栏和手机底部重复导航，统一顶部“书架、找书、更多”。搜索与发现归入找书，管理工具在更多中按用途分组，书架批量及分组操作收进管理菜单。桌面、390 像素手机和 320 像素窄屏的实际截图检查通过。

主要实现位于 `BookController.kt`、`api/service`、`help/source`、`model/jsSource`、`JsExtensions.kt`、`web/src/components` 和 `web/src/lib`。启动、容器和 CI 配置位于 `dev.ps1`、`build.sh`、`browser`、Docker/Compose 与 `.github/workflows`。本地 EPUB/CBZ 复用实际静态资源目录生成公共 URL，统一 Windows/Linux 分隔符，并编码中文、空格及章节锚点。

删除旧 Vue/桌面代码、重复打包、旧本地 JAR 和临时代码编辑脚本。复用已有 EPUB 写入器、章节缓存校验、用户存储和 HTTP 客户端；使用原生 fetch、IndexedDB、Web Worker、FontFace 和 Audio。替换规则按 ID 更新，备份先验证再写入，避免重复逻辑及移动活跃文件。

## 明确边界

本机无 Docker，镜像构建、容器运行和 GitHub Actions 尚未实际执行；配置已静态验证。旧 Kotlin 空值调用与 KXML 仍有编译警告，完整构建无错误。

外部 AI/TTS 服务需用户配置地址、模型和密钥，本次用本地兼容协议服务验证实际 Java API 与 React 链路。MCP 支持 2025-03-26、2025-06-18、2025-11-25 的 JSON HTTP 模式；通知返回 202，GET 不提供 SSE 并返回 405，错误版本头返回 400。认证沿用既有会话/查询 accessToken，未实现 OAuth 资源服务器发现或新的 Bearer 认证。

服务端常规规则采用 Rhino，网页 DOM 采用独立浏览器；后续已补齐与 FU 同版本引擎的 QuickJS 桥及源共享缓存等契约，见 [书源兼容验收](source-compatibility-verification.md)。Android 任意设备 API 仍需适配，动态场景按用户要求排除。人工网页验证最长等待 120 秒，第三方站点认证取决于其自身规则。段评写入接口未启用，对照项目也未启用完整写入入口；本次支持读取、引用请求和解析。详见 [书源迁移矩阵](book-source-migration.md)。

跨域 EPUB 的字体、背景、选区和高亮等 DOM 操作受浏览器同源规则约束。旧备份缺少原用户元数据时保持原资源 URL；音频运行缓存可重新生成，不备份系统用户数据库。
