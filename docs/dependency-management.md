# 当前依赖管理策略

2026-10-09。按用户最终澄清：**单独声明的直接依赖检查升级；可复用的传递依赖交给框架／上游管理。** 上一轮把独立直接库也降至平台默认版本的处理范围过大，现已纠正。不要求每个传递包都等于仓库 latest。此前逐项升级记录见[历史复核](dependency-recheck.md)，当前解析图为 `logs/direct-resolved-final.json`；`managed-*` 记录为上一轮历史状态。

## 已简化的配置

`build.gradle.kts` 继续删除 6 个额外 BOM：Jackson、Netty、SLF4J、Log4j、JUnit、Mockito，以及 14 项仅为追逐传递包最新版添加的约束。Coroutines、Gson、JsonPath、Guava 恢复直接声明的升级版本；Jackson Kotlin 模块明确使用 3.2.3，Core／Databind／共享注解复用该模块的上游传递依赖，不另加 Jackson BOM。

官方 Maven 元数据复核了 18 个独立版本来源（直接库、平台及插件），无新增稳定更新或查询错误，记录为 `logs/direct-backend-metadata.json`。Web 26 项与 Browser 3 项直接 npm 依赖也重新核查：Web 仅保留 TypeScript 的 ESLint peer 限制与匹配 Node 24 的类型版本；其余没有新增可升级项。

Boot BOM 使用官方 `SpringBootPlugin.BOM_COORDINATES`，版本自动与 Boot 插件一致，避免重复维护版本号。不增加 dependency-management 插件或新的业务依赖。

日志实现为 Boot 默认的 **Logback**。`log4j-api`／`log4j-to-slf4j` 是 Boot starter 自带的日志转接组件，最终 JAR 没有 Log4j Core，也没有另一个 SLF4J 日志绑定。

## 当前解析版本

| 组件 | 当前版本 | 版本来源 |
| --- | --- | --- |
| Spring Boot／Spring Framework | 4.1.1／7.0.9 | Boot 插件及对应 BOM |
| Logback Classic／Core | 1.5.38 | Boot |
| SLF4J API／JUL 转接 | 2.0.18 | Boot |
| Log4j API／SLF4J 转接 | 2.25.5 | Boot |
| Jackson Kotlin 模块 | 3.2.3 | 单独声明的直接依赖，核查最新稳定 |
| Jackson Core／Databind | 3.2.3 | Jackson Kotlin 模块的上游传递依赖 |
| Jackson 共享注解 | 2.22 | Jackson Kotlin 模块的上游传递依赖；沿用原注解包 |
| Netty 核心模块 | 4.2.19.Final | Boot 与 Vert.x 平台解析后的统一版本 |
| Netty tcnative classes | 2.0.84.Final | Netty 上游 BOM |
| Coroutines | 1.11.0 | 单独声明的直接依赖，核查最新稳定 |
| Gson | 2.14.0 | 单独声明的直接依赖，核查最新稳定 |
| JsonPath | 3.0.0 | 单独声明的直接依赖，核查最新稳定 |
| Guava | 33.7.2-jre | 单独声明的直接依赖，核查最新稳定 |
| JUnit Jupiter／Platform | 6.0.3 | Boot 测试套件 |
| Mockito | 5.23.0 | Boot 测试套件 |
| Byte Buddy／Agent | 1.18.11 | Boot 测试套件 |
| Objenesis | 3.3 | Mockito 上游依赖 |
| XMLUnit | 2.11.0 | Boot 测试套件 |
| SnakeYAML／Commons Logging／Commons Lang | 2.6／1.3.6／3.20.0 | Boot |
| Okio／Okio JVM | 3.18.1 | OkHttp 上游依赖 |

Boot 4.1.1 管理 Jackson 3.1.5 和 Netty 4.2.17.Final；Vert.x 5.2.1 要求 3.1.7 和 4.2.19.Final。直接 Jackson Kotlin 模块进一步要求 Core／Databind 3.2.3。普通 Gradle `platform` 与传递依赖合并时满足这些更高要求，因此当前版本不必严格等于 Boot 单独的 BOM，也不使用 `enforcedPlatform` 将直接库／Vert.x 的要求压低。

Web 删除仅为追逐 `aria-query` latest 添加的整个 `overrides`。由上游 jest-dom 的 `^5.0.0` 和 DOM 的精确 `5.3.0` 声明解析，保留符合各自范围的锁文件结果；不人为规定传递包必须升级或降级。

## 保留的版本来源及约束

Kotlin 2.4.21 BOM 与当前 JVM／Spring 编译器保持一致，确保 stdlib 和 reflect 不混用不同版本。Vert.x 5.2.1 和 OkHttp 5.5.0 是 Boot 没有管理的独立技术栈，继续通过各自 BOM 统一模块。Retrofit 3.0.0 上游依赖 OkHttp 4.12.0，保留 OkHttp 5 是当前 HTTP 栈的选择，不能解释成 Retrofit 3 强制要求它。

Coroutines、Gson、Guava、JsonPath、Jackson Kotlin、Kotlin Logging、Retrofit、Rhino／Engine、Jsoup、JsoupXpath、XMLPull、Hutool 都是当前单独选用的直接功能库，固定已核查的升级版本。Boot starter、Vert.x／OkHttp 模块与 Kotlin stdlib 复用其对应平台的同版本家族；测试 starter 和 launcher 复用 Boot 测试套件，不额外升级传递的 JUnit／Mockito。后端只保留一项严格传递约束：ANTLR 4.7.2 与 JsoupXpath 的生成解析器匹配；较新 ANTLR 无法读取其 ATN 格式，取消约束需要重新生成解析器。

Jackson 继续使用 3 系列，保留 Vert.x `JsonFactory` SPI、Kotlin 模块、旧数据的 null／未知字段兼容及编码规则。测试改为断言主版本 3 与实际行为，不再将具体补丁号写死；补丁号由解析图与产物检查记录。

## 已完成验证

上一轮 225 项后端测试已保护既有行为。本次纠正后完整后端 39 个套件、225 项测试重新通过，失败／错误／跳过均为 0；Web lint、typecheck、68 项单元测试和完整 npm 依赖树检查通过，安装 audit 为 0。`bootJar` 成功，最终依赖图与完整测试时逐项相同。最终 JAR 包含 79 个运行依赖 JAR，确认直接库的升级版本、只有 Logback 日志绑定，且没有 Jackson 2 core／databind／Kotlin。

证据：`logs/direct-backend.log`、`logs/direct-backend-summary.json`、`logs/direct-package.log`、`logs/direct-resolved-{after,final}.json`、`logs/direct-final-jar.json` 和 `logs/direct-web-{install,lint,typecheck,test}.log`。

本次隔离实例重新完成 Java → QuickJS 11 组、Java → 浏览器桥 6 组，以及真实阅读编辑／重载、全章缓存、TXT／EPUB 导出、字典、RSS 分页／收藏／离线图片、WebDAV 进度、备份恢复与每日备份计时器；没有未捕获的前端脚本错误，fixture 已清理。证据为 `logs/direct-quickjs-live.log`、`logs/direct-browser-live.log` 和 `logs/direct-fu-parity-live.json`。

主服务已加载本次 JAR。原有 1 本书、10 个书源的 URL 身份逐项一致，桌面／手机页面无水平溢出和未捕获脚本错误，记录为 `logs/data-identity-direct-after.json` 和 `logs/direct-current-page.json`。

API 8080、Web 5173、辅助服务 8090 最终健康检查均为 HTTP 200，隔离实例已停止，证据为 `logs/direct-health.json`；完整汇总为 `logs/direct-verification-summary.json`。

既有 Kotlin／嵌入 kXML 等编译警告保留；审计用临时 Gradle init 脚本还有 Gradle 10 的执行期 API 弃用提示。本次没有再次构建容器。

官方依据：

- [Boot Gradle 依赖管理与 BOM 常量](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html)
- [Spring Boot 4.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom)
- [Boot 默认日志 starter](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-logging/4.1.1/spring-boot-starter-logging-4.1.1.pom)
- [Vert.x 5.2.1 BOM](https://repo.maven.apache.org/maven2/io/vertx/vertx-dependencies/5.2.1/vertx-dependencies-5.2.1.pom)
