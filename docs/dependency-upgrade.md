# 依赖升级记录

2026-10-09。当前按用户澄清：单独声明的直接依赖检查升级，可复用的传递依赖交给 Spring Boot／上游库管理。已删除只为追逐传递包最新版增加的覆盖，纠正上一轮把独立直接库也降至平台默认版本的处理；版本来源与验收见[当前依赖管理](dependency-management.md)。此前“全部最新稳定”的表述不准确；[依赖复核](dependency-recheck.md)保留为先前逐项升级的历史记录。依赖以编译、规则回归、真实服务联测与 HTTP 测试验证；不使用动态版本或忽略 peer 冲突的安装方式。

此前追加升级 `lucide-react 1.53.0 → 1.54.0`、`hutool-crypto/core 5.8.47 → 5.8.48`，加入同版本 Kotlin BOM，将 Boot 默认引入的 `kotlin-reflect 2.3.21` 对齐到编译器和 stdlib 使用的 `2.4.21`。JSON 运行库已由 Jackson 2 迁移至 3 系列，当前直接 Kotlin 模块及其传递 Core／Databind 均为 3.2.3。

## 后端

| 组件 | 原版本 | 迁移版本 |
| --- | --- | --- |
| Java | 8 | 21 |
| Gradle | 6.1.1 | 9.8.1，发行包与 wrapper 均核对官方 SHA-256 |
| Kotlin JVM / Spring 插件 | 1.5.21 / 1.3.61 | 2.4.21，统一版本 |
| Spring Boot | 2.1.6.RELEASE | 4.1.1 |
| Spring Framework（Core／Context／Beans／AOP／Expression） | 由旧 Boot 管理 | 7.0.9，由 Boot BOM 统一管理 |
| Kotlin Stdlib／Reflect | 升级前 Reflect 仍为 2.3.21 | 统一 2.4.21，由同版本 Kotlin BOM 管理 |
| Vert.x | 3.8.1 | 5.2.1，统一 BOM |
| Netty | 旧 Vert.x 传递引入 | 4.2.19.Final，复用 Boot／Vert.x BOM |
| SLF4J | 旧 Boot 传递引入 | 2.0.18，Boot 管理 |
| Logback | 旧 Boot 传递引入 | 1.5.38，Boot 默认日志实现 |
| Coroutines | 旧框架传递引入 | 1.11.0，独立直接声明 |
| Gson | 2.8.5 | 2.14.0，独立直接声明 |
| Jackson Core／Databind／Kotlin | 2.13.+，旧 Boot 管理混用；此前暂升至 2.22.3 | 3.2.3，直接 Kotlin 模块及其上游传递依赖 |
| Jackson Annotations | 旧 Jackson 2 BOM 管理 | 2.22，Jackson 3 官方继续共用的注解包 |
| Kotlin Logging | 1.6.24 | 8.0.4 |
| Guava | 28.0-jre | 33.7.2-jre，独立直接声明 |
| OkHttp / 日志组件 | 4.9.1 / 4.1.0 | 5.5.0，统一 BOM |
| Retrofit | 2.6.1 | 3.0.0 |
| Rhino | 本地定制 1.7.13-1 | 官方 Rhino / Rhino Engine 1.9.1 |
| Jsoup | 1.14.1 | 1.23.2 |
| JsoupXpath | 2.5.0 | 2.5.5 |
| JsonPath | 2.6.0 | 3.0.0，独立直接声明 |
| Hutool Crypto／Core | 5.8.0.M1 | 5.8.48 |
| XMLPull | 本地 1.1.3.1 | Maven Central 1.1.3.4a |

删除未使用的 `retrofit-vertx`、`sysout-over-slf4j` 和 `vertx-lang-kotlin` 扩展模块。移除本地旧 JAR 和已不需要的 JCenter、JitPack、GitLab、Google、mavenLocal 仓库。框架迁移涵盖 Jakarta 注解、Vert.x 5 Future/协程、日志 API 和 Kotlin 新编译器兼容。

Gradle 已由 9.7.1 升至官方当前稳定版 9.8.1，完整编译、225 项后端测试与打包通过。[Kotlin 官方兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)当前只列 KGP 2.4.20 的完全支持范围 7.6.3–9.7.0；[2.4.21 发布说明](https://github.com/JetBrains/kotlin/releases/tag/v2.4.21)没有明确扩大该范围。因此 9.8.1 应标为本项目实测通过，不能写成官方完全支持。之前仅据表格上限保留 9.7.1 的决定没有经过新版实测，本轮已纠正。

[Spring Boot 4 默认使用 Jackson 3](https://docs.spring.io/spring-boot/reference/features/json.html)，Jackson 2 支持已弃用，仅作为迁移兼容。此前以 Vert.x 为由保留 Jackson 2 的说明不准确：Vert.x 5.2.1 在 Java 21 中提供 Jackson 3 实现。本项目经官方 `JsonFactory` SPI 选择 Jackson 3，并重建 Vert.x 的不可变 mapper，加入 Kotlin 模块及旧数据未知字段兼容配置。删除启动时修改 Jackson 2 mapper 的代码，排除旧 core／databind／Kotlin 模块；`com.fasterxml.jackson.annotation` 是 Jackson 3 官方保留的共享注解包，不能按包名前缀误判为仍使用 Jackson 2 databind。

Spring Boot 4.1.1 BOM 管理 Jackson 3.1.5，Vert.x 5.2.1 要求 3.1.7；独立直接声明的 Jackson Kotlin 模块 3.2.3 带入同版本 Core／Databind 及共享注解，最终使用 3.2.3，无需另加 Jackson BOM。Spring Framework 7.0.9 与 Spring Boot 4.1.1 是两个版本号；后者统一管理前者，不单独覆盖 Spring 模块。日志和测试套件复用 Boot 默认组合，Netty 等复用上游平台管理。[迁移计划](jackson3-migration-plan.md)记录兼容契约与验证步骤。

`logs/dependency-maven-upgrades.json`、`logs/recheck-backend-metadata-after.json` 和 `logs/managed-resolved-final.json` 是之前各轮扫描的历史记录。当前完整解析结果为 `logs/direct-resolved-final.json`，不要求每个传递坐标都是 latest。JsoupXpath 2.5.5 的解析器由 ANTLR 4.7.2 生成，直接替换为 4.13.2 已实证初始化失败，因此严格保留匹配版本。Hutool 6／7 新坐标目前只有里程碑版，保留 5.8.48。

## 前端

Vue 2、Vue CLI、Element UI、Vuex、Vue Router、Axios、LocalForage 由 React 19.3.0、Vite 8.3.4、TypeScript、shadcn/ui、Radix、Tailwind CSS 4 替换。API 使用原生 fetch，离线章节缓存使用 IndexedDB；React 与前端构建版本固定在 `web/package.json`，依赖树锁定在 `web/package-lock.json`。

TypeScript 使用 6.0.3：当前 `typescript-eslint` 的 peer 范围不支持 TypeScript 7.0.2，保持类型检查与 ESLint 的有效支持，不强制安装冲突版本。

shadcn/ui 是源码组件集合，采用 [MIT 许可](https://github.com/shadcn-ui/ui/blob/main/LICENSE.md)，不依赖 MUI、Ant Design 或付费网格组件。

| 运行组件 | 当前版本 | 用途 |
| --- | --- | --- |
| React／React DOM | 19.3.0 | 页面和状态渲染 |
| shadcn/ui | 本地组件源码 | MIT 组件集合，没有单独运行时包版本 |
| Radix UI（`radix-ui`） | 1.7.0 | 可访问的基础交互组件 |
| Lucide React | 1.54.0 | 图标，本次升级 |
| Sonner | 2.0.8 | 消息提示 |
| DOMPurify | 3.4.16 | HTML 净化 |
| Class Variance Authority | 0.7.1 | 组件样式变体 |
| clsx | 2.1.1 | 条件类名 |
| tailwind-merge | 3.7.0 | 合并 Tailwind 类名 |

| 构建／测试组件 | 当前版本 |
| --- | --- |
| TypeScript | 6.0.3 |
| Vite | 8.3.4 |
| React Vite 插件 | 6.1.2 |
| Tailwind CSS／Vite 插件 | 4.3.3 |
| ESLint | 10.12.0 |
| typescript-eslint | 8.71.1 |
| React Hooks ESLint 插件 | 7.1.1 |
| React Refresh ESLint 插件 | 0.5.7 |
| Vitest | 5.0.3 |
| jsdom | 30.1.2 |
| Testing Library React | 16.3.3 |
| Testing Library jest-dom | 7.0.1 |
| Playwright Test | 1.64.0 |
| React／React DOM 类型 | 19.3.0 |
| Node 类型 | 24.19.1 |

npm 核验后仅剩两个有意保留的跨大版本项：TypeScript 最新为 7.0.2，但 `typescript-eslint@8.71.1` 的官方 peer 范围为 `>=4.8.4 <6.1.0`；`@types/node` 最新为 26.6.4，但本机运行的是 Node 24，保留 24 系列类型。其余前端直接依赖无更新提示，记录为 `logs/dependency-web-outdated-after.json`。本次 npm 安装审核报告 0 个漏洞，此结果仅适用于该次 npm 依赖树。

上述两个项仅指直接依赖。传递依赖存在由当前父包限制主版本或精确锁定版本的项目，不要求整棵树都为 registry latest。此前刷新了兼容范围内更新；当前按用户澄清删除仅为追逐 `aria-query` latest 添加的 override，复用 jest-dom 的 `^5.0.0` 与 DOM 的精确 `5.3.0` 声明，锁文件保留符合上游范围的结果。Web Node 声明按 jsdom 要求为 `^22.22.2 || ^24.15.0 || >=26.0.0`。此前逐包消费者记录见 `logs/recheck-npm-summary-final.json`，当前策略及验证见[依赖管理](dependency-management.md)。

## 书源辅助运行时

为对齐 FU 的 QuickJS 书源桥，在既有 Node 辅助服务加入 `quickjs-emscripten-core@0.32.0`、`@jitl/quickjs-wasmfile-release-sync@0.32.0` 及绑定类型传递依赖。后端和前端未为此新增依赖。Bellard 引擎版本与 FU 一致；原生 Promise 拒绝追踪扩展、构建来源及许可随产物发布，生产环境不需要编译器。具体契约和验收见 [书源兼容验收](source-compatibility-verification.md)。

| 组件 | 当前版本 |
| --- | --- |
| Playwright | 1.64.0，与前端测试版本相同 |
| quickjs-emscripten-core | 0.32.0 |
| QuickJS WASM 发行绑定 | 0.32.0 |
| QuickJS FFI Types（传递依赖） | 0.32.0 |
| Bellard QuickJS 引擎 | FU quickjs-kt 1.0.4 对应的 `f1139494d18a2053630c5ed3384a42bb70db3c53` 提交 |

辅助服务的 `npm outdated` 无更新提示，记录为 `logs/dependency-browser-outdated.json`。QuickJS 原生产物另行记录编译器和绑定提交；本次升级没有更换该引擎。

## 构建与发布

GitHub Actions 的 checkout、Node、Java、Docker 构建和 release action 均升级至核验时的稳定发行版本。API 与 Web 独立验证、打包和发布，移除旧 Node 14/16、Java 8 镜像及网页资源复制步骤。构建产物和启动参数参见 [部署说明](deployment.md)。

本轮发现并删除漏迁移的 `.github/workflows/build.yml`：它仍固定旧上游仓库、使用两个 `@master` action，且没有构建根 Dockerfile 所需 JAR。正式独立发布入口继续由 `release.yml`／`release-github.yml` 提供。浏览器发布归档补齐必需的 QuickJS 模块与整个 runtime 目录，已验证解压后的安装和实际 WASM 执行。Slim Alpine 升至 3.24.2；Web 构建固定 Node 24.21.0，Nginx 从浮动 mainline 标签改为明确的 stable 分支 1.30.5。

本机实际使用 Java 21.0.11 LTS 与用户指定 nvm 路径下的 Node 24.15.0。项目部署要求 Java 21；容器前端构建使用 Node 24，后端与 Web 独立打包。此轮没有修改用户的全局 Java／nvm 安装。

Jackson 3 迁移前，本次其他依赖升级的完整构建与 213 项后端回归通过；最终 JAR 中 `kotlin-stdlib`／`kotlin-reflect` 均为 2.4.21，`hutool-crypto`／`hutool-core` 均为 5.8.48。前端 lint、typecheck、68 项单元测试、生产构建和 46 项浏览器回归通过。真实 Java → QuickJS 11 组及 Java → 浏览器 6 组联测通过，fixture 已清理。证据为 `logs/dependency-backend.log`、`logs/dependency-backend-summary.json`、`logs/dependency-web-*.log`、`logs/dependency-quickjs-live.json` 和 `logs/dependency-browser-live.json`。初次架构迁移的历史记录见 [验收记录](verification.md)。

主要改动文件为 `build.gradle.kts`、`web/package.json`、`web/package-lock.json` 和本记录。通过 BOM 统一 Kotlin 版本，继续复用既有运行库；原有阅读字典 chunk 体积提示和既有编译警告保留，检查均成功。工作区改动没有提交或推送。

上述其他依赖升级完成后，主服务曾重启验证 API 8080、Web 5173、辅助服务 8090 的健康检查均为 HTTP 200，记录为 `logs/dependency-health.json`。原有 1 本书、10 个书源的 URL 身份与升级前逐项一致，记录为 `logs/data-identity-dependency-after.json`；桌面与手机页面无水平溢出或未捕获脚本错误。Jackson 3 最终运行验证见下文。

## Jackson 3 初次迁移验收（历史）

以下记录初次迁移至 3.2.3 的验证；当前直接库升级、传递库复用的组合已重新通过完整后端回归与产物检查，见[当前依赖管理](dependency-management.md)。

`build.gradle.kts` 改用 Jackson 3 BOM 和 Kotlin 模块，并排除 Jackson 2 core／databind／Kotlin。`ReaderJsonFactory.java` 与 `META-INF/services/io.vertx.core.spi.JsonFactory` 通过官方 SPI 一次性配置 JSON；`ReaderApplication.kt` 删除启动后修改全局 mapper 的旧代码。Java 21 编译器可解析 Vert.x 的多版本 JAR 中 Jackson 3 类，当前 Kotlin 编译器无法直接解析，因此这个小型 SPI 实现使用 Java，其余应用继续使用 Kotlin。

新增 `JsonCompatibilityTests.kt` 和 `Jackson3RuntimeTests.kt` 共 12 项回归。最初 5 项公开 JSON 格式回归在 Jackson 2.22.3 与 Jackson 3.2.3 分别通过；另以旧 mapper 配置和当前模型单独运行 JVM，确认旧书源的 `bookSourceType:null` 转换为 0、`enabled:null` 转换为 false、`customOrder:"7"` 转换为 7。Jackson 3 默认拒绝 primitive null，这项回归先复现失败，再显式关闭 `FAIL_ON_NULL_FOR_PRIMITIVES` 恢复旧语义。非空 Kotlin 字符串仍拒绝 null，未知扩展字段、嵌套对象、阅读位置、64 位时间、中文、ISO Instant 和无 padding URL-safe Base64 保持兼容。编码错误、非法或尾随 JSON、流的调用方所有权也有回归覆盖。

最终 `test bootJar` 通过：39 个测试套件、225 项测试，失败／错误／跳过均为 0，证据为 `logs/jackson3-backend.log` 和 `logs/jackson3-backend-summary.json`。最终 JAR 的 Core／Databind／Kotlin 均为 3.2.3，共享 Annotations 为 2.22；测试确认应用选择自定义 Jackson 3 codec，旧 Jackson 2 core／databind／Kotlin 类不在运行 classpath。

最终 JAR 在隔离实例通过 Java → QuickJS 11 组、Java → 浏览器 6 组真实书源联测，fixture 清理成功；阅读编辑与刷新、全章缓存、TXT／EPUB 下载、RSS 分页／收藏／离线图片、字典、WebDAV 阅读进度、备份恢复和每日备份计时器均通过。记录为 `logs/jackson3-quickjs-live.json`、`logs/jackson3-browser-live.json` 和 `logs/jackson3-fu-parity-live.json`。另以两个独立 JVM 对比旧／新 mapper 与真实模型，章节 `isVolume`／`isVip`／`isPay` 字段名、字符串数字及小数转整数、nullable 字段行为一致。

主服务已重新启动最终 Jackson 3 JAR，API 8080、Web 5173、辅助服务 8090 健康检查均为 HTTP 200，记录为 `logs/jackson3-health.json`；产物依赖清单为 `logs/jackson3-jar-dependencies.json`。原有 1 本书、10 个书源的 URL 身份逐项一致，记录为 `logs/data-identity-jackson3-after.json`。桌面与手机页面没有水平溢出或未捕获脚本错误，页面记录为 `logs/jackson3-current-page.json`，截图为 `logs/jackson3-current-desktop.png` 和 `logs/jackson3-current-mobile.png`。隔离联测实例已停止。

此迁移保留既有 RSS 模型限制：原始 JSON 信封能保留未知扩展，转成 `RssSource` 再导出时没有新增未知字段保留能力。回归区分这两条路径，不能把 Jackson 迁移验收解释为 RSS 未知扩展能力已经补齐。既有编译警告未在这次迁移中处理。
