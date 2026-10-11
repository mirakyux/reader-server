# 独立浏览器服务

Java API 通过此服务执行动态网页书源和网页登录。它运行真实 Chromium；Windows 默认使用已安装的 Edge，其他环境使用 Playwright Chromium。`dev.ps1 start` 自动启动服务，并生成仅供 API 与浏览器进程共享的随机令牌。服务不会把令牌写进进程参数或浏览器页面。

单独运行：安装 Node 24 后执行 `npm ci`，使用 `npx playwright install chromium` 安装浏览器，设置 `READER_BROWSER_TOKEN` 后执行 `npm start`。Windows 如需 Chrome，设置 `READER_BROWSER_CHANNEL=chrome`。Java 配置为 `reader.app.browserUrl` 与 `reader.app.browserToken`。

默认监听 `127.0.0.1:8090`。容器镜像内含匹配版本的 Chromium，Compose 仅在内部网络暴露服务，需在 `.env` 设置 `READER_BROWSER_TOKEN`。同时最多 4 个会话；操作超时 30 秒，网页登录闲置 5 分钟回收。每次规则调用创建隔离 context，导入和回传当前用户、书源的 Cookie 与 localStorage。

支持 `webView`、`webViewGetSource`、`webViewGetOverrideUrl` 的缓存和延迟重载。登录界面通过截图、坐标、文字和键盘动作操作真实页面，支持普通表单及可人工处理的验证码。第三方站点可能有反自动化限制；不保证所有网站授权可通过。

`startBrowserAwait` 支持在原脚本等待期间打开验证页，完成并关闭后保存登录状态，返回当前页面或重新加载后的 DOM，让原脚本继续执行。等待上限为 120 秒，不计入规则脚本的 30 秒指令预算。前端在源请求未返回时获取交互队列，无需先取消和重新搜索。

`getVerificationCode(imageUrl)` 显示验证码图片与输入框，输入完成后关闭窗口，原脚本获得验证码字符串。图片请求沿用同一浏览器书源会话，空值或非 HTTP 图片链接明确失败。

EPUB 导出复用 `POST /decode-image` 将 JDK 无法解码的 WebP 转成 PNG，封面再由 Java 转成 JPEG。该接口沿用 Bearer 鉴权和并发限制，仅接受 `{base64:string}` 中的图片字节，返回同结构 PNG 字节；不接受 URL。转换在阻断网络的独立空白页面中执行。输入最多 2 MiB、单边最多 8192 像素、总像素最多 16 Mi，PNG 输出最多 8 MiB。浏览器服务不可用或图片转换失败时，正文图片导出明确失败；封面沿用 FU 的模板／默认图片回退。

单文件 JS 书源的 `java.getQuickJsSandbox().evalString(script)` 通过已鉴权的 `POST /quickjs` 执行真正的 Bellard QuickJS WebAssembly。引擎与 FU quickjs-kt 1.0.4 使用相同提交 `f1139494d18a2053630c5ed3384a42bb70db3c53`。每次调用创建独立 worker、运行时和上下文，不提供 Node、文件、网络或 Java 宿主。最大 384000 个输入字符、65536 个输出字符、64 MiB 引擎堆、512 KiB 引擎栈和 15 秒执行时间，最多并行 4 个任务。支持顶层 await；返回值和 Promise 状态文本遵循 FU，非字符串值及未处理拒绝明确失败，不回退 Rhino。

原生拒绝追踪通过 `JS_SetHostPromiseRejectionTracker` 实现，使用绑定 0.32.0 和 Emscripten 4.0.12 构建；模块、WASM、MIT 许可与 SHA-256 来源记录在 `quickjs-runtime/`。生产运行只需 `npm ci` 和随项目发布的产物。需要重建时使用 `scripts/build-quickjs-runtime.py --source <官方v0.32.0源码目录> --emcc <Emscripten4.0.12的emcc路径>`；工具链放在被忽略的 `build/quickjs-build/`，无需部署。

回归使用本地动态站点验证 DOM、Cookie、网页存储、资源捕获、人工点击登录和请求鉴权：`npm test`。实现依据 [Playwright BrowserContext](https://playwright.dev/docs/api/class-browsercontext)、[Page](https://playwright.dev/docs/api/class-page) 和 [浏览器配置](https://playwright.dev/docs/browsers)。

书源 QuickJS 整链路验证执行 `node check-quickjs-stack.mjs --api http://127.0.0.1:18080`，只允许隔离的本地 API，禁止使用主实例 8080。结果见 [书源兼容验收](../docs/source-compatibility-verification.md)。
