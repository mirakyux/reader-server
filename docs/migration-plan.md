# 前后端分离与书源迁移计划

本次目标是独立部署 React 前端和 JVM API，升级可验证兼容的依赖，并优先补齐 `E:/workspace/Java/legado_FU` 中可在服务端实现的书源能力。现有数据目录、书源 JSON、书架及 `/reader3` API 继续兼容。此前移除桌面运行时的改动一并保留。

## 实施顺序

1. 以现有 HTTP 测试和 CSS、XPath、JSONPath、JavaScript 规则回归保护旧行为。
2. 后端升级到 Java 21，统一 Gradle、Kotlin、Spring Boot、Vert.x 和解析依赖。使用官方 Rhino JSR223 代替本地定制 JAR；每项兼容性以编译和规则测试为准。
3. 前端迁移为 React、TypeScript、Vite、shadcn/ui + Tailwind CSS，使用原生 fetch 和 IndexedDB。保留书架、阅读、搜索、发现、管理、导入与同步的真实接口操作。
4. 书源优先：持久化缓存、隔离 Cookie、共享脚本库、单文件 JS、导入验证、登录表单、实时调试；复用现有搜索、目录、正文和 SSE 路径。
5. API JAR 排除前端资源，前端独立构建和发布。开发使用 Vite 代理，生产使用 Nginx 同源反向代理；同步更新启动脚本、Docker、Compose 和 CI。
6. 集成验证后重启本机 API 和 React 服务，验证页面、接口、导入与完整书源抓取。

## 写入边界

- 后端升级：构建配置、框架 API、服务配置、CORS、旧解析回归。
- 书源补齐：书源模型、脚本执行、Cookie、缓存、导入、登录和对应回归。
- React 前端：`web/`，包括独立前端镜像和 Nginx 配置。
- 集成：根部署文件、CI、启动工具和使用文档。

## 验证要求

- 后端：旧规则回归、HTTP 认证和 CORS、缓存 TTL、Cookie 用户和书源隔离、JSON/JS 导入、脚本返回值、搜索至正文的本地 fixture。
- 前端：API 错误处理与认证、类型检查、ESLint、单元测试、生产构建、浏览器书源和阅读流程。
- 部署：JAR 无 Web/桌面资源、独立前端输出、脚本语法、Compose/工作流 YAML、API 和前端进程跨调用存活。
- 测试使用独立临时数据，不向真实用户书架写入测试书籍。

## 能力边界

网页验证采用独立 Edge／Chromium，单文件书源的 QuickJS 桥采用与 FU 同引擎提交的独立 WASM 运行时；具体实测结果见 [书源兼容验收](source-compatibility-verification.md)。手机亮度、音量和启动其他手机应用等设备操作不在服务器端需求范围，不计为书源兼容缺口。动态场景、AI／Skills／MCP、听书、本地书扩展和远程导出按用户确定的范围排除。既有迁移记录见 [验收记录](verification.md) 和 [书源迁移矩阵](book-source-migration.md)。
