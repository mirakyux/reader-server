# reader

阅读服务端与独立 React 客户端。前端采用 React、TypeScript、Vite、shadcn/ui + Tailwind CSS；后端采用 Kotlin、Spring Boot、Vert.x，运行于 Java 21。

Windows 使用 `./dev.ps1 start`，Linux/macOS 分别运行 `./build.sh run` 和 `cd web && npm ci && npm run dev`。访问 `http://localhost:5173/`。Docker 使用 `docker compose up -d --build`，访问 `http://localhost:4396/`。

前端输出 `web/dist/`；API 输出 `build/backend/libs/reader.jar`。两者独立构建、部署，不再把网页放进 JAR。旧数据目录和 API 保持兼容。

已对照 Legado NG 补齐书源执行、登录与校验、精确阅读位置、设备离线、RSS、查词、外观和 WebDAV 数据同步。AI／Skills／MCP 和听书入口默认隐藏。本地书解析暂不扩展，已有 TXT、EPUB、UMD、CBZ 功能保留。

单文件 JS 书源支持与 FU 同版本引擎的 QuickJS 桥；常规规则沿用 Rhino，安全 Java 包导入、共享缓存、jsLib、加密及字体桥按 FU 契约执行。动态场景不在本轮范围。

在线文字书可缓存全部章节后导出 TXT 或 EPUB；支持进度、取消及失败章节重试，缺章时拒绝导出。

- [部署、启动与配置](docs/deployment.md)
- [依赖升级与兼容说明](docs/dependency-upgrade.md)
- [迁移计划与验证要求](docs/migration-plan.md)
- [书源能力迁移](docs/book-source-migration.md)
- [全章缓存与 TXT/EPUB 下载](docs/book-download-plan.md)
- [迁移验收与剩余限制](docs/verification.md)
- [FU 非 AI 功能补齐验收](docs/fu-parity-verification.md)
- [FU 书源兼容补齐与验收](docs/source-compatibility-verification.md)
- [数据目录及已有 API](doc.md)

Fork 自 [hectorqin/reader](https://github.com/hectorqin/reader)，规则兼容参考 [Legado](https://github.com/gedoor/legado) 及本地 Legado NG。
