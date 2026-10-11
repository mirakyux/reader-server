# 依赖复核与补漏结果

本文是此前按逐项 latest 检查的历史记录。此后用户要求复用 Spring Boot／上游管理，后端取消了不必要覆盖；下表的“复核后”版本不代表当前。当前策略、实际版本与重新验收见[依赖管理](dependency-management.md)。Gradle、CI 和部署修复继续保留。

2026-10-09。此前“升级已完成，全部都是最新稳定版”的结论不准确：只检查部分直接坐标和成功构建，遗漏了传递依赖、Gradle、旧 CI 和浏览器发布包。完整依赖树仍有必要保留的版本，不能用“全部最新”概括。

## 本轮覆盖范围

后端获取 `runtimeClasspath` 与 `testRuntimeClasspath` 的实际解析图：升级前 117、升级后 119 个 Maven 组件，包含 BOM；逐项读取 Maven Central 元数据，并另行核查 Kotlin／Boot 插件及 Hutool 新坐标。前端核查 26 项直接依赖及全部已安装传递依赖，辅助服务核查 3 项直接依赖与完整树。还比对了 17 个本地 shadcn 组件的当前官方源码、5 份有效 YAML、16 个 Actions 引用、7 个实际基础镜像标签和本机运行环境。

原始记录：`logs/recheck-resolved-backend-{before,after}.json`、`logs/recheck-backend-metadata-after.json`、`logs/recheck-backend-changes.json`、`logs/recheck-npm-registry.json`、`logs/recheck-npm-summary-final.json`、`logs/recheck-npm-shadcn.json`、`logs/recheck-package-static.json` 与 `logs/recheck-package-registry.json`。

## 实际补升

后端共补升 24 个既有 Maven 坐标，通过家族 BOM 和约束统一版本，未引入新业务库。

| 组件 | 复核前 | 复核后（历史） |
| --- | --- | --- |
| Gradle wrapper／发行包 | 9.7.1 | 9.8.1 |
| JUnit Jupiter／Platform 家族 | 6.0.3 | 6.1.3 |
| Mockito Core／JUnit 适配 | 5.23.0 | 5.24.0 |
| Byte Buddy／Agent | 1.18.11 | 1.18.14 |
| Objenesis | 3.3 | 3.6 |
| XMLUnit Core | 2.11.0 | 2.14.0 |
| SnakeYAML | 2.6 | 2.7 |
| ASM | 9.7.1 | 9.10.1 |
| Okio／Okio JVM | 3.18.1 | 3.18.2 |
| Commons Logging | 1.3.6 | 1.4.0 |
| Commons Lang | 3.20.0 | 3.21.0 |
| Log4j API／SLF4J 桥 | 2.25.5 | 2.26.1 |
| Error Prone Annotations | 2.48.0 | 2.50.0 |
| JetBrains Annotations | 23.0.0 | 26.1.0 |
| Acorn 的兼容 peer | 7.4.1 | 8.19.0 |
| baseline-browser-mapping | 2.11.27 | 2.11.28 |
| electron-to-chromium | 1.5.451 | 1.5.452 |
| jest-dom 使用的 aria-query | 5.3.0 | 5.3.2；DOM 库仍单独解析 5.3.0 |
| Slim 镜像 Alpine | 3.23 | 3.24.2 |
| Web 构建 Node | 浮动 `24-alpine` | 固定 `24.21.0-alpine3.24` |
| Web Nginx | 浮动 `nginx:alpine`，实际 mainline 1.31.6 | 明确 stable 分支 `1.30.5-alpine3.24` |

Gradle 发行包与 wrapper JAR 均匹配官方 SHA-256，记录为 `logs/recheck-gradle-verified.json`。Kotlin 官方完全支持范围仍止于 Gradle 9.7.0，超范围不等于不能使用；9.8.1 已在本项目完成构建与回归，但不能称为 Kotlin 官方完全支持。

Web 的 Node engines 修正为 `^22.22.2 || ^24.15.0 || >=26.0.0`，与实际 jsdom 要求一致。ARIA 使用限定消费者的 override，并为其嵌套 DOM 库保留精确原版本，避免把 DOM 的 5.3.0 要求一起覆盖成 5.3.2。

## 仍保留的版本及依据

| 项目 | 保留决定／限制 |
| --- | --- |
| ANTLR 4.7.2 | 最新是 4.13.2。JsoupXpath 2.5.5 的 Lexer／Parser 嵌入 ATN 格式 3；真实字节码配新 runtime 初始化失败，错误为 `Could not deserialize ATN with version 3 (expected 4)`。严格约束 4.7.2，升级需要从语法重新生成解析器或维护 XPath 库 fork。 |
| TypeScript 6.0.3 | 最新 7.0.2；最新 typescript-eslint 8.71.1 明确要求 `>=4.8.4 <6.1.0`。6.0.3 是其允许范围内最新稳定版。 |
| Node 类型 24.19.1 | 最新 26.6.4 对应其他 Node 主版；当前开发与部署目标为 Node 24。 |
| Web 的传递依赖 | 最终仍有 68 个已安装包名、113 条消费者关系落后于 registry latest，多数受当前最新版父包的主版本范围或精确版本约束。逐包原因记录于 `logs/recheck-npm-summary-final.json`，不把父包为 latest 等同于全部子包也为 latest。 |
| Babel／AJV／humanfs 等 | React Hooks／Testing Library 等限制 Babel 7；最新 ESLint 仍要求旧 AJV／ignore／humanfs 主线。整族跨主版本替换会超出声明范围。 |
| lightningcss／mdn-data／why-is-node-running | 最新 Tailwind／css-tree／Vitest 分别精确锁定 1.32.0／2.27.1／3.2.1。未绕过这些精确要求；不能写成新版必定不可用，只能说明本轮保留上游指定组合。 |
| Hutool 5.8.48 | `org.dromara.hutool` 6 系列只有 6.0.0-M22；`cn.hutool.v7` 7 系列只有 7.0.0-M8，均为里程碑版，不符合稳定版要求。 |
| Jackson Annotations 2.22 | Jackson 3 官方共用原注解包；Core／Databind／Kotlin 已为 3.2.3，没有继续使用旧 Jackson 2 运行模块。 |
| 嵌入 EPUB／kXML2／UMD 源码 | 不由 Gradle 坐标管理，现有改造源码缺少可核实的上游版本标记；不能认定为最新版，也不能直接增加同名 JAR。保留当前源码及 EPUB 兼容修正。 |
| QuickJS 引擎与编译来源 | 引擎按 FU quickjs-kt 的提交对齐；Emscripten 4.0.12 是现有 WASM 的实际历史编译来源，最新工具为 6.0.12。未重编该产物，不能改来源元数据冒充已升级；生产无需安装编译器。 |

核查未发现需要升级的其他直接主线：Spring Boot 4.1.1、Spring Framework 7.0.9、Kotlin 2.4.21、Vert.x 5.2.1、Jackson 3.2.3、Netty 4.2.19.Final、SLF4J 2.0.20、Logback 1.6.5、Coroutines 1.11.0、Gson 2.14.0、OkHttp 5.5.0、Retrofit 3.0.0、Rhino 1.9.1、Jsoup 1.23.2、JsoupXpath 2.5.5、JsonPath 3.0.0、Guava 33.7.2-jre 及辅助服务 npm 组件。Beta／Milestone／Alpha 均不计作稳定升级。shadcn 本地 17 个组件归一化安装路径后与当前官方 registry 源码一致。

## 运行环境与部署修复

本机环境只核查，未修改全局安装：Node 24.15.0，24 系列最新 LTS 为 24.21.0；npm 11.13.0，最新为 12.2.0；Microsoft JDK 21.0.11，21 系列最新补丁为 21.0.12.1。Java 项目目标仍为 21，最新 LTS 25 与非 LTS 27 分开记录；Node 26 为 Current。项目依赖升级不代表宿主工具已全部升级。

删除 `.github/workflows/build.yml`：它使用两个 `@master` action，固定旧上游仓库且不先构建根 Dockerfile 需要的 JAR；有效的验证与独立镜像／归档发布入口已有专门工作流。浏览器 release tar 命令补齐 `quickjs.mjs`、`quickjs-worker.mjs` 和整个 `quickjs-runtime/`，实际归档有 13 个条目，包括模块、WASM、来源及许可。

Actions 的 16 处引用与官方最新稳定发行一致。7 个基础镜像标签均经 Registry 实查存在并支持 amd64／arm64，Temurin21 和 Semeru21 浮动标签已包含该目标分支最新补丁。Nginx 此次为明确选择 stable 发行分支，不能描述为把 1.31.6 升成 1.30.5。

## 验证记录

Gradle 9.8.1 下完整 `test bootJar --warning-mode all` 通过：39 个套件、225 项测试，失败／错误／跳过均为 0。最终 Maven 119 个组件重新核查无元数据错误，坐标内最新稳定差异仅为已解释的 ANTLR。最终 ARIA 调整与锁文件刷新后，Web lint／typecheck／生产构建、68 项单元测试和 46 项 Edge 测试通过，辅助服务 13 项测试通过；npm audit 两套均为 0 已知漏洞，npm ls 无依赖或 peer 错误。最终浏览器测试显式代理至隔离 API，没有代理连接失败。

真实隔离实例通过 QuickJS 11 组、浏览器桥 6 组，以及阅读编辑、全章缓存、TXT／EPUB 导出、RSS、字典、WebDAV 阅读进度与备份恢复／每日计时器。发布归档在解压目录执行 npm ci、模块导入和 7 组真实 QuickJS／Promise／失败恢复均通过，WASM 哈希一致。

后端与真实链路证据：`logs/recheck-backend.log`、`logs/recheck-backend-summary.json`、`logs/recheck-quickjs-live.json`、`logs/recheck-browser-live.json`、`logs/recheck-fu-parity-live.json`。前端最终检查汇总为 `logs/recheck-web-verification-summary-final.json`，辅助服务为 `logs/recheck-browser-test.log`。归档证据为 `logs/recheck-package-{static,registry,quickjs}.json`。

主服务已加载最终产物，API 8080、Web 5173、辅助服务 8090 均返回健康状态 200，证据为 `logs/recheck-health.json`；JAR 内实际运行依赖清单为 `logs/recheck-final-jar.json`。原有 1 本书和 10 个书源 URL 身份逐项一致，证据为 `logs/data-identity-recheck-after.json`；桌面／手机页面没有未捕获脚本错误或水平溢出，记录为 `logs/recheck-current-page.json`。隔离进程已停止。

主要改动文件为 `build.gradle.kts`、Gradle wrapper／脚本、`web/package.json`／锁文件、`Dockerfile.slim`、`web/Dockerfile`、`.github/workflows/release-github.yml` 及依赖说明；删除旧 `.github/workflows/build.yml`。简化为统一家族 BOM、限定已有依赖约束与有效发布入口，复用原有业务实现和测试。

既有编译及 Reader chunk 体积警告仍存在，不宣称日志零警告。本机没有 Docker，容器仅完成配置／标签／平台检查，没有实际容器构建或容器内运行验证。参考 FU 保持只读，所有改动未提交或推送。
