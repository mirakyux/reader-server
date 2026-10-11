# FU 非 AI 功能补齐验收

2026-10-09。参考 `E:/workspace/Java/legado_FU` 的 `0a726331e`，在 reader-dev 原有 React／Java 分离架构上补齐业务链路。实施前差距见 [功能审计](fu-function-comparison.md)，实施范围见 [补齐计划](fu-parity-plan.md)。本文件记录本轮实际实现与验证，不将按钮、保存字段或旧轮测试数量当作完成证据。

## 用户指定范围

补齐书源、阅读、设备离线、RSS、净化／查词、外观和数据同步。AI／Skills／MCP、听书入口默认隐藏；没有删除已有后台实现或用户数据。本地书格式与解析暂缓。TXT／EPUB 继续下载到浏览器，不增加远程导出；远程 WebDAV 上传的是数据备份和阅读进度。

## 已落地链路

| 范围 | 实际行为 | 主要实现与证据 |
| --- | --- | --- |
| 目录、正文规则 | 执行 `preUpdateJs`；正文首请求、分页和错误响应执行登录检查；分页传递 `webJs` 与章节上下文；规则级 `@webjs:` 调用浏览器 | `WebBook.kt`、`BookChapterList.kt`、`BookContent.kt`、`SourceResponse.kt`；本地 HTTP 与浏览器桥回归 |
| HTTP／JS 扩展 | HEAD、bodyJs、dnsIp、timeout、字节转换、并发测试与请求计时；最小现代书源保留请求头、Cookie 和启用状态 | `AnalyzeUrl.kt`、`JsExtensions.kt`、`StrResponse.kt`、`SourceAnalyzer.kt`；`SourceFuParityTests` |
| 源脚本归档桥 | 7z／RAR 通过现有 7-Zip 程序解压，校验路径、链接和大小；Java 写入私有缓存 | `SourceArchive.kt`；真实创建 7z 文件并提取测试。部署需 `7z`／`7zz` 或 `READER_7ZIP` |
| 动态登录与发现 | 传统脚本表单、动态标签、chars、控件动作、防抖、倒计时、登录函数与表单刷新；分类、过滤和样式实际执行 | `SourceLogin.kt`、`SourceActions.kt`、`Sources.tsx`、`SourceFeatures.tsx`；源回归与 Edge 流程 |
| 书源编辑与校验 | 字段编辑、补全和语法预览；搜索、发现、详情、目录和正文分阶段及批量校验；排序、自动分组、订阅增删改与更新 | `SourceValidation.kt`、`SourceSubscriptionsController.kt`、`Sources.tsx`；订阅真实 HTTP 集成及阶段校验 |
| 调试停止 | 关闭 SSE 或显式取消会取消后端请求任务；Rhino 指令检查可中断 CPU 循环 | `BookController.kt`、`SourceScriptRuntime.kt`；CPU 循环取消回归及前端取消契约 |
| 源事件与图片 | 书架刷新事件、书名／作者、分类、长按、分享／复制等触发点；图片解码收到 chapter；段评按段落定位 | `Library.tsx`、`SourceFeatures.tsx`、`SourceActions.kt`。段评是本项目原型，FU 参考版本未启用完整段评规则 |
| 阅读位置 | 服务端与离线队列保存章节、字符位置和时间；较新时间允许主动回退；旧时间不会覆盖新位置；读缓存、预读和导出不移动进度 | `ReadingPosition.kt`、`Reader.tsx`、`cache.ts`；精确进度／旧时间／主动回退 API 回归 |
| 换源 | 整书换源先获取有效目录，再按标题／章号校准；临时换源只替换本章，下一章回原书源 | `ReadingFeatureController.kt`、`BookController.kt`、`Reader.tsx`；插入卷节点的真实 API 回归及 Edge 流程 |
| 正文编辑 | 编辑正文和标题持久化；保留首次原文；重新缓存、清理下载缓存及 TXT／EPUB 导出保留编辑；可恢复原文；章节 URL 变化不会误用旧编辑 | `ChapterEdits.kt`；真实 API 回归、真实 UI 重载、导出与恢复联测 |
| 书签、搜索、查词 | 普通位置书签保存字符偏移；搜索命中定位和高亮；词典规则增删改、FU `dictRule.json` 导入导出、URL 与显示规则解析 | `ReadingTools.tsx`、`ReadingFeatureController.kt`、`readingPosition.ts`；本地 HTTP 查词与 Edge 定位流程 |
| 净化和简繁 | 遵循单书净化开关；保留共享规则；复用 FU 使用的 quick-transfer 0.2.17 词组数据及 Apache 许可 | `reading.ts`、`chinese.ts`、静态词典数据；单书开关和词组单元回归 |
| 分页、漫画和排版 | 按可视区域真实分栏分页，滑动／覆盖／无动画；漫画方向、缩放和滤色；字间距、缩进、标题和边距配置；预读与 Wake Lock | `Reader.tsx`、`useReadingPages.ts`、`ReadingAppearance.tsx`；桌面／390×844 分页与无溢出回归 |
| 设备离线 | IndexedDB 保存目录、正文和书架；Service Worker 保存应用代码及账号隔离的资源；断网新页面冷启动仍可读文字、图片和下一章 | `cache.ts`、`sw.js`、`navigation.ts`；真实生产离线脚本移除 API 模拟、关闭原页面后再验证 |
| 书架和主题 | 网格／列表、列数／间距／圆角与排序；FU NG 静态颜色／背景 ZIP 包导入导出和应用主色；不支持的主题字段保留并提示 | `Library.tsx`、`shelf.ts`、`themePackages.ts`、`ReadingAppearance.tsx`；主题 ZIP 校验、持久布局与截图 |
| 视频文字轨道 | WebVTT／SRT／LRC 字幕与 Bilibili XML 弹幕按播放时间显示；跳转清除过期文字；字幕点击定位、弹幕开关和容器全屏；远程文字通过书源请求头和 Cookie 获取 | `media.ts`、`MediaContent.tsx`、`getChapterMediaText`；解析、播放／跳转事件回归与带鉴权头的真实 HTTP 集成 |
| RSS | 实际下一页 URL、搜索 key、登录恢复和重定向；动态分类、单 URL 阅读；收藏／分组／历史／排序／调试；正文和图片离线 | `Rss.kt`、`RssSourceController.kt`、`RssLibrary.kt`、`RssJournal.tsx`；真实分页、收藏、图片打包联测 |
| RSS 网页登录 | RSS／书源同 URL 使用不同私有空间；sourceType 贯穿 drain、动作队列与浏览器；关闭验证页面可唤醒等待请求 | `SourceBrowserController.kt`、`SourceActionController.kt`、`api.ts`、`sourceActions.ts`；同 URL Cookie、页面存储及异步等待集成回归 |
| 阅读统计 | FU 累计毫秒记录单列展示与汇总；不伪造每日会话；支持删除；v2 恢复避免重复累计 | `ReadingStatistics.kt`、`ReadingStatisticsPanel.tsx`；FU 历史转换与重复计数回归 |
| 备份互通 | FU 原生业务条目与顶层兼容副本；副本纳入哈希并核对一致；RSS 私有状态可跨账号重映射 | `BackupArchive.kt`、`BookController.kt`；双向条目、篡改拒绝、回滚及真实备份恢复联测 |
| 远程数据同步 | WebDAV 配置、递归建目录、连接检查、列表／备份／校验恢复；按时间处理章内进度，兼容空作者；ETag 条件写入拒绝并发覆盖；服务器按日自动备份 | `RemoteWebdav.kt`、`ReaderDataController.kt`、`RemoteSync.tsx`；本地真实 DAV 协议联测及实际一分钟定时器触发备份 |

## 本轮验证

| 检查 | 结果与证据 |
| --- | --- |
| Java 21 后端测试与 bootJar | 147 项通过，0 失败、0 错误、0 跳过；`logs/fu-parity-backend.log` 和 JUnit XML |
| React lint／typecheck／单元测试／构建 | 通过；65 项单元测试；`logs/fu-parity-web-*.log` |
| Edge 界面回归 | 45 项通过；`logs/fu-parity-web-e2e.log` |
| 浏览器执行服务 | 4 项通过，覆盖真实 DOM、Cookie、页面存储、交互登录和图片解码 |
| 实际前后端联测 | 通过；不模拟后端 API，使用 18080／4173／18090 的隔离实例；`logs/fu-parity-live.json` |
| 每日自动备份 | 使用隔离实例的测试配置和本地 DAV，实际等待服务器定时器执行；联测 JSON 的 `dailyBackupTimer=true` |
| 生产离线冷启动 | 通过；10 个生产资源已缓存，2 章目录／正文与图片可读，另一账号不能读缓存图片；`web/screenshots/reading-offline-production.json` |
| 编辑后的 EPUB | EPUBCheck 5.4.0：0 错误、0 警告；解包 XML 验证修改正文与其余章节正文均存在；`logs/fu-parity-epubcheck.log`、`logs/fu-parity-epub-body.json` |
| 页面外观 | 已查看桌面阅读、手机书架与手机分页截图；文字、按钮和内容未溢出，常用入口维持顶部与折叠菜单 |
| 主用户数据与服务 | 已重启加载最终产物；原有 1 本书、10 个书源的 URL 身份逐项一致，三个服务健康检查均为 HTTP 200 |

普通界面测试关闭 Service Worker，以确保请求模拟确定；独立生产离线脚本使用真实 Service Worker、IndexedDB 与资源缓存。真实联测仅写 `build/gap-live` 的隔离数据，FU 仓库保持只读。

## 复现与主要改动

Java 使用 `C:/Users/mirakyux/.jdks/ms-21`，Node 使用用户指定的 `D:/programs/scoop/persist/nvm/nodejs/nodejs`。后端执行 `gradlew.bat test bootJar`；web 执行 `npm run lint`、`typecheck`、`test`、`build` 和 `test:e2e`；browser 执行 `node --test test/*.test.mjs`。

真实联测先执行 `dev.ps1 start -Verification -SkipBuild -ApiPort 18080 -WebPort 4173 -BrowserPort 18090`，然后在 web 目录执行 `node scripts/check-fu-parity-integration.mjs`。生产离线验证在 web 构建完成后执行 `node tests/browser/offline-production.mjs`。自动备份测试只会重置该验证实例的 `remoteWebdav.json` 测试日期。

本轮新增公共阅读服务为 `ReadingPosition.kt`、`ChapterEdits.kt`、`ReadingFeatureController.kt`；数据服务为 `ReaderDataController.kt`、`UserDataFiles.kt`、`RssLibrary.kt`、`RemoteWebdav.kt`；规则和 UI 改动集中在 `io/legado/app/model`、`io/legado/app/help`、`web/src/components` 和 `web/src/lib`。公共接口由 `YueduApi.kt` 注册，章节读取／缓存／导出及备份由 `BookController.kt` 整合。

复用现有解析器、HTTP、浏览器服务和用户存储，未新增 npm／Gradle 依赖；章节编辑与阅读进度使用同一条实际读取链路；RSS 网页登录复用现有浏览器组件，并显式隔离源身份。

## 真实边界

- 后续已补齐真正的 QuickJS 运行时及 FU 书源契约差异；当前结果见 [书源兼容验收](source-compatibility-verification.md)，本文件的测试数量保留为前一轮记录。
- Edge／Chromium 和 Android WebView 不完全一致，外部站点登录、反爬及购买动作需按站点验证。本轮未向真实远程账号写入，也未执行网站扣费。
- 提供真实 Web 分页和静态主题包；动态场景按用户要求排除。Android 仿真翻页、硬件电子纸刷新、音量键和窗口亮度没有等价 Web 实现。
- 设备完整离线已证明文字和图片链路；不承诺大容量流式音视频下载。浏览器配额、跨域资源许可及用户清理数据仍会影响离线缓存；失败资源会报错，不显示整书下载完成。
- RSS 离线正文与图片限制为最多 100 张、合计 16 MiB，移除远程脚本和 iframe。FU Android 加密服务器配置原样保留，不解密转换为 WebDAV 配置。
- 净化改变正文长度时，源字符偏移与显示文本偏移可能不同。章内定位使用渲染文本；严格跨设备一致需要相同净化及简繁配置。
- 词组字典使 Reader 产物约 1.39 MB、gzip 568 KB；构建保留体积提示，未通过调高阈值掩盖。

## 服务记录

主服务已用 `dev.ps1 stop`／`start -SkipBuild` 重启。React 为 `http://localhost:5173/`，API 为 8080，浏览器执行服务为 8090，健康检查均为 HTTP 200。隔离联测进程已停止，未写主用户 fixture。

主用户原有 1 本书、10 个书源的 URL 身份逐项一致，记录见 `logs/data-identity-fu-parity-before.json` 和 `logs/data-identity-fu-parity-after.json`。当前页面桌面与手机截图为 `logs/fu-parity-current-desktop.png`、`logs/fu-parity-current-mobile.png`；实际页面无脚本错误和水平溢出，记录为 `logs/fu-parity-current-page.json`。最后的词典接口序列化修正已重新通过 147 项后端测试；前端错误提示调整已通过完整静态检查、65 项单元测试、2 项相关 Edge 流程和生产离线复验。
