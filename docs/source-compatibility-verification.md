# FU 书源兼容补齐

参考仓库 `E:/workspace/Java/legado_FU`，提交 `0a726331e0a5d61b03a307aead5ab875d97bca33`，只读对照。目标是现有书源协议、脚本宿主和执行行为对齐；动态场景、AI／Skills／MCP、听书、本地书扩展和远程导出不在本轮范围。既有 React／Java 分离架构与用户数据保留。

## 已整合能力

| 契约 | 实现与验证方式 |
| --- | --- |
| QuickJS | `java.getQuickJsSandbox().evalString()` 只向单文件源 java 宿主开放；不占用用户脚本的 `isolatedJs` 全局。引擎提交与 FU quickjs-kt 1.0.4 相同，真实 WASM 执行，无 Rhino 回退 |
| 字符串与异步 | 原始全局脚本使用 FU 的 async eval 标志；支持顶层 await 和微任务。非字符串结果失败；未 await 的 Promise 返回 FU 状态文本；原生拒绝追踪覆盖 detached async 和晚到 catch。保留 NUL、孤立代理项与补充平面字符 |
| 限额与隔离 | 每调用独立 worker／引擎；输入 384000、输出 65536 UTF-16 字符；引擎堆 64 MiB、栈 512 KiB、截止 15 秒；最多 4 个并行任务。无文件、网络、Node、Java 或模块加载器；断开的 HTTP 调用取消 worker |
| Java 包与模板 | FU 官方 Jsoup 模板可直接执行，安全 JSONPath、Gson、JDK 集合与加密类可导入；返回数组按元素类型检查，`ajaxAll` 响应数组可直接使用；区分宿主包装和直接类构造，拒绝跨用户缓存构造、反射、进程和任意文件 |
| cache | 同用户书源／RSS 共用注入 cache；不同用户隔离。原始对象保存在 50 MiB 内存 LRU；普通磁盘状态和文件缓存同键可共存，磁盘 TTL 与 FU 内存语义独立；源登录、Cookie 和文件保持私有 |
| jsLib | 库内容和远程库使用共享父作用域，业务请求使用独立子作用域；闭包中的 java/source 等动态解析当前请求，缓存不持有请求。普通库按用户、源、库内容跨线程共享；无库时 CryptoJS 按线程共享，各自为 16 项弱引用 LRU；刷新清除库下载与作用域 |
| 编码与加密 | Base64 charset、hex 字符串、简繁词组转换、HMAC、对称／非对称／签名工厂；AES/HMAC 标准向量和独立 RSA 签名验证 |
| 字体 | FU 新版 QueryTTF 公共映射与复合轮廓、旧接口别名、cmap 4/6/10/12/13；字节／私有文件／URL／Base64 输入、useCache、过滤、补充平面替换。真实 Arial 与生成字体验证 |
| 源变量与请求 | 源 put/get/putVariable、登录默认值、规则和 URL 变量回退；fromBookInfo、详情重新获取；bodyJs/dnsIp 参数桥；动态并发跨请求生效且按用户隔离 |
| 归档与交互 | RAR／7z 文本及字节读取复用既有私有解压服务；openUrl 接入浏览器／视频动作；refreshExplore 通知 Web 重新读取发现控件 |
| 当前配置 | 已保存的 Web 阅读／外观设置在普通书源调用前同步；服务端合并保存并保留无关用户配置；配置桥映射 FU 读取字段 |

## 验证记录

2026-10-09，最终代码构建、隔离实例联测与主服务重启均已完成。所有 fixture 只操作测试目录／18080 隔离实例，脚本拒绝主实例 8080；FU 仓库保持只读且工作区干净。

| 检查 | 最终结果与证据 |
| --- | --- |
| Java 21 后端测试／bootJar | 37 个测试类、213 项测试全部通过，0 失败、0 错误、0 跳过；`logs/source-compat-backend.log`、`logs/source-compat-backend-summary.json`、JUnit XML |
| 前端 lint／typecheck／构建 | 全部通过；`logs/source-compat-web-lint.log`、`logs/source-compat-web-typecheck.log`、`logs/source-compat-web-build.log` |
| 前端单元测试 | 18 个文件、68 项通过；`logs/source-compat-web-test.log` |
| Edge 界面回归 | 46 项通过，包括详情与书架入口的 fromBookInfo 差异；`logs/source-compat-web-e2e.log` |
| Node 辅助服务 | 13 项通过，包含 9 项真实 QuickJS 语法、异步、隔离、资源限额、取消与 HTTP 鉴权检查；`logs/source-compat-sidecar.log` |
| Java → QuickJS 真实联测 | 11 组通过：导入、搜索、详情、目录、两章正文、发现、动态登录、隔离、异常传播和恢复；测试书籍、书源与登录状态已清理；`logs/source-compat-quickjs-live.json` |
| Java → 浏览器真实联测 | 6 组通过：动态 DOM、GET／POST、请求头、重定向、Cookie、截图交互登录、原脚本等待恢复和实际图片验证码；`logs/source-compat-browser-live.json` |
| 原生运行产物 | 模块与 WASM 的 SHA-256 与 `browser/quickjs-runtime/build.json` 一致 |
| 静态检查 | `git diff --check` 通过；FU 参考提交及干净工作区已复核 |
| 主服务和原用户数据 | 8080 API、5173 Web、8090 辅助服务均为 HTTP 200；原有 1 本书、10 个书源的 URL 身份逐项一致；`logs/source-compat-health.json`、`logs/data-identity-source-compat-before.json`、`logs/data-identity-source-compat-after.json` |
| 当前页面 | 已查看 1440×1000 桌面与 390×844 手机截图，无水平溢出或未捕获脚本错误；`logs/source-compat-current-page.json`、`logs/source-compat-current-desktop.png`、`logs/source-compat-current-mobile.png` |

后端回归进一步覆盖普通库跨线程保留可变对象、每次请求绑定恢复、独立 CryptoJS 的线程作用域，以及 `ajaxAll` 从 IO 线程再次调用同库请求头脚本；响应数组按元素类型检查。加密使用已知向量与独立签名验证，字体覆盖生成 fixture 和真实 Arial；这些验证不依赖外部小说网站。

复现：`gradlew.bat test bootJar`，web 的 `npm run lint/typecheck/test/build/test:e2e`，browser 的 `npm test`。隔离 API 用 `dev.ps1 start -Verification -SkipBuild -ApiPort 18080 -WebPort 4173 -BrowserPort 18090` 启动，再执行 `node browser/check-quickjs-stack.mjs --api http://127.0.0.1:18080` 与已有 `browser/check-java-stack.mjs`，测试结束停止隔离进程。

## 主要改动文件

| 文件 | 改动目的 |
| --- | --- |
| `browser/quickjs.mjs`、`quickjs-worker.mjs`、`quickjs-runtime/`、`server.mjs` | 真正的 QuickJS 引擎、原生拒绝追踪、独立 worker、取消与鉴权；请求体按字节收集后统一解码 |
| `quickjs/QuickJsSandboxBridge.kt`、`model/jsSource/JsSourceEngine.kt` | 单文件书源的 FU 字符串桥与明确失败行为 |
| `model/jsSource/SourceScriptRuntime.kt`、`SourceSharedJsScope.kt`、`JsSourceConfig.kt` | 安全包与数组包装、共享库／CryptoJS 缓存、请求绑定隔离、原始 File 返回语义和导入契约 |
| `help/source/SourceStorage.kt`、`help/JsExtensions.kt`、`data/entities/BaseSource.kt` | 同用户共享缓存、源私有存储、变量、配置、编码、加密、归档与刷新接口 |
| `help/FontSourceBridge.kt`、`model/analyzeRule/QueryTTF.java`、`help/ChineseConversion.kt` | 复用 FU 字体映射与简繁词组数据，统一 code point 处理 |
| `model/analyzeRule/AnalyzeRule.kt`、`AnalyzeUrl.kt`、`model/webBook/`、`api/controller/BookController.kt` | 源变量回退、详情入口、重新获取详情、动态并发和分页上下文 |
| `web/src/lib/api.ts`、`web/src/components/Reader.tsx`、`SourceFeatures.tsx`、`Sources.tsx` | 当前偏好同步、详情入口上下文与发现刷新 |
| `src/test/`、`browser/test/quickjs.test.mjs`、`browser/check-quickjs-stack.mjs`、`web/tests/` | 契约、隔离、已知输入与真实服务链路回归 |

Java 文件路径均位于 `src/main/java/io/legado/app/`，`BookController.kt` 位于 `src/main/java/com/htmake/reader/`。完整部署与依赖说明见 [部署说明](deployment.md) 和 [依赖升级](dependency-upgrade.md)。

## 平台适配与来源

兼容性按服务器实际需要执行的书源链路判断：导入、规则解析、脚本与宿主方法、HTTP、登录验证、目录正文、缓存、字体和导出。手机亮度、音量、启动其他手机应用等设备操作不在需求范围，不计为书源兼容缺口；动态场景按用户要求排除。

网页桥使用 Edge／Chromium 执行 FU 书源的网页脚本与登录验证接口。需实际核验的是请求、DOM、Cookie 与验证结果能否满足书源，浏览器平台不同本身不作为未完成的判据。

本轮未逐一验证第三方书源网站，也未向真实站点执行购买扣费；验证证明列出的契约与流程，不构成所有外部书源百分百可用的承诺。FU 的 QuickJS 接口自身仍标为实验性。

普通 jsLib 与 FU 一样共享库对象和闭包状态，执行不持有全库锁，避免 `ajaxAll`／`ajaxTestAll` 跨线程调用同库脚本时死锁。独立 CryptoJS 缓存保留 FU 的线程隔离。缓存使用弱引用和 LRU，持久业务状态仍应使用 `cache` 或 `source` 变量接口。原始 `java.io.File` 返回值与 FU 一样在脚本中为 `null`；私有 `readFile`／`readTxtFile`／`deleteFile` 继续可用，任意路径文件构造保持禁用。

QuickJS 扩展仅增加原生 Promise 拒绝追踪绑定，不修改 Bellard 引擎或 Promise 对象；`browser/quickjs-runtime/build.json` 记录引擎、绑定、编译器及 SHA-256，源码重建脚本为 `browser/scripts/build-quickjs-runtime.py`。运行组件随辅助服务发布，生产无需编译器。源码许可随产物保存。

本轮复用既有 HTTP、用户存储、归档及浏览器服务；删除旧的字符逐个替换字体路径，统一为完整 code point 与 FU 字形映射。前端／后端不新增依赖，Node 服务新增 QuickJS 运行组件。改动未提交或推送，原有未提交迁移保持原样。

主服务已加载最终构建，访问地址为 `http://localhost:5173/`；隔离联测进程已停止。保留的构建提示为 Rhino 的弃用配置项及既有阅读字典导致的前端 chunk 体积提示，不影响本轮检查通过。
