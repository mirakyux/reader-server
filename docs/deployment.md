# 前后端独立部署

React 客户端使用 TypeScript、Vite、shadcn/ui + Tailwind CSS；API 使用 Kotlin、Spring Boot、Vert.x，运行于 Java 21。前端产物为 `web/dist/`，后端产物为 `build/backend/libs/reader.jar`。JAR 不再提供首页。

## 本机运行

需要 Java 21 JDK 和 Node.js 24。Windows 使用：

```powershell
.\dev.ps1 start
.\dev.ps1 status
.\dev.ps1 stop
```

脚本默认使用 nvm 目录 `D:/programs/scoop/persist/nvm/nodejs/nodejs`，不修改全局 nvm 版本。可用 `-NodeDirectory` 和 `-JavaDirectory` 指定环境。首次启动执行构建与检查；已验证的产物可用 `-SkipBuild`。前端地址为 `http://localhost:5173/`，API 为 `http://localhost:8080/`。

脚本也会启动独立浏览器服务（默认 `127.0.0.1:8090`），使用现有 Edge。浏览器执行和网页登录无需桌面端应用；随机内部令牌只通过进程环境传递。详细配置见 [浏览器服务](../browser/README.md)。

Linux/macOS 先安装浏览器运行环境并设置共享令牌，在三个终端分别运行 API、前端和浏览器：

```bash
./build.sh run
```

```bash
cd web
npm ci
npm run dev
```

```bash
cd browser
npm ci
npx playwright install --with-deps chromium
export READER_BROWSER_TOKEN='your-random-internal-token'
export READER_APP_BROWSERTOKEN="$READER_BROWSER_TOKEN"
npm start
```

API 终端也需设置同一 `READER_APP_BROWSERTOKEN`，服务地址默认 `http://127.0.0.1:8090`。

## 构建与检查

```bash
./gradlew test bootJar
cd web
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

`./build.sh check` 执行全部检查。API 可单独通过 `java -jar build/backend/libs/reader.jar` 运行，网页无需复制到 JVM 资源目录。

## 代理与跨域

Vite 的 API 目标默认为 `http://127.0.0.1:8080`，可通过环境变量 `READER_API_PROXY` 调整。前端可通过 `VITE_API_BASE_URL` 指定 API 地址；它是构建变量，变更后需要重建。

生产推荐同源部署：Nginx 托管前端，将 `/reader3/`、`/assets/`、`/epub/`、`/health` 转发给 API。`web/nginx.conf` 配置上传限制，并关闭 SSE 响应缓冲。WebDAV、本地 EPUB、封面与导出仍由 API 提供。

若直接跨域访问 API，将实际前端 Origin 加入配置：

```yaml
reader:
  app:
    storagePath: storage
    secure: false
    secureKey: ""
    inviteCode: ""
    cacheChapterContent: true
    userLimit: 50
    userBookLimit: 200
  server:
    port: 8080
    contextPath: ""
    cors:
      allowedOrigins:
        - http://localhost:5173
        - http://127.0.0.1:5173
        - http://localhost:4173
        - http://127.0.0.1:4173
      allowCredentials: true
```

所有常规接口继续返回 `{isSuccess, errorMsg, data}`，路径仍为 `/reader3`。原书源和书架 JSON 无需手动转换。

## Docker 部署

```bash
docker compose up -d --build
```

先在 `.env` 设置随机 `READER_BROWSER_TOKEN`，然后访问 `http://localhost:4396/`。Compose 分别运行 `reader` API、`web` 和 `browser`；API 和浏览器端口仅暴露给容器网络。数据和日志保存于本地 `storage/`、`logs/`。

通过环境变量配置 `READER_WEB_PORT`、`READER_APP_SECURE`、`READER_APP_SECUREKEY`、`READER_APP_INVITECODE`。使用内网 IP 或域名访问时，在 `.env` 设置 `READER_CORS_ALLOWED_ORIGINS` 为浏览器中的完整 Origin（含协议和端口）；多个地址使用逗号分隔。修改访问端口时也需同步更新 Origin。Nginx 保留请求的 Host 和端口，防止浏览器 POST 请求被跨域校验拒绝。

`docker-compose.yml` 是主配置；`docker-compose.yaml` 是 Compose 2.20+ 的兼容 include 入口。[192.168.31.107 部署记录](lan-deployment.md)包含已部署实例的地址、维护命令及验证结果。

独立构建镜像：

```bash
docker build -f Dockerfile.source -t reader-api .
docker build -f web/Dockerfile -t reader-web web
docker build -f browser/Dockerfile -t reader-browser browser
# 可选 API 运行时
docker build -f Dockerfile.slim -t reader-api:slim .
docker build -f Dockerfile.openj9 -t reader-api:openj9 .
```

旧单体镜像不能提供新 React 首页，需同时部署 API 与 Web。已有数据目录挂载至 API `/app/storage`，日志挂载至 `/app/logs`；切换时先停旧进程，避免两个服务同时写入同一目录。

CI 分别验证 API、React 和浏览器链路；发布工作流打包 JAR 与网页归档，并向当前仓库对应的 GHCR API/Web/Browser 仓库发布独立镜像。

## WebDAV

地址为 `http://前端域名/reader3/webdav/`。多用户模式使用网页注册的用户名和密码；单用户模式使用 `default` / `123456`。

创建备份无需预先由 Android App 生成 ZIP。版本 2 归档包含文件大小和 SHA-256 清单，覆盖用户顶层 JSON、字体与背景资源、AI/TTS 配置、Skills、聊天、统计，以及用户书源缓存和浏览器登录状态；音频运行缓存可重新生成。备份使用复制，源文件继续可用。恢复兼容旧 Legado 实体 ZIP，先校验版本、文件完整性和路径，再替换文件，写入失败会回滚。书源登录缓存用当前服务密钥重新加密，不覆盖其他用户的加密密钥。

完整备份含用户自己的模型密钥和书源登录数据，应作为私人文件保存。它不导出系统用户数据库或其他用户的配置；常规配置 API 只返回密钥是否已配置。

## 书源迁移

本轮优先补齐 Cookie、登录缓存、`jsLib`、单文件 JavaScript 书源、导入验证、登录表单和实时调试。现有书架、阅读、搜索、发现、RSS、替换规则、TXT/EPUB/UMD/CBZ 导入、WebDAV 与用户管理继续使用现有 API。

在线文字书支持[全章缓存后下载 TXT / EPUB](book-download-plan.md)。整本缓存显示进度、失败章号并支持取消及重试；导出端独立拒绝缺章，生成文件在传输后清理。

[书源迁移](book-source-migration.md)记录协议及兼容边界。网页运行使用真实浏览器，服务端常规规则使用 Rhino；单文件书源的 QuickJS 桥由既有 Node 服务运行，部署时必须保留 `quickjs-runtime/` 中的模块、WASM 与许可文件。生产无需 Emscripten；引擎来源和验收见 [书源兼容验收](source-compatibility-verification.md)。Android 任意设备类仍需平台适配。
