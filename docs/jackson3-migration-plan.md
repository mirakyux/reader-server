# Jackson 3 迁移

2026-10-09。Spring Boot 4 默认使用 Jackson 3，Jackson 2 仅为迁移兼容保留，支持已弃用。当前 Vert.x 5.2.1 的 Java 21 实现也支持 Jackson 3，因此不能以 Vert.x 为由停留在 Jackson 2。

1. 先用 Vert.x 公共 JSON API 锁定旧书源扩展字段、运行时身份隐藏、书籍章节进度、嵌套 JSON 和中文数据格式。
2. 将运行库和 Kotlin 模块切换为 Jackson 3；移除 Jackson 2 的 core／databind／Kotlin 模块。直接 Kotlin 模块使用已核查的稳定版 3.2.3，Core／Databind 复用该模块的上游传递版本，无需另加 Jackson BOM，见[当前依赖管理](dependency-management.md)。共享注解继续使用 Jackson 官方保留的 `com.fasterxml.jackson.annotation` 包。
3. 用官方 `JsonFactory` SPI 配置不可变 Jackson 3 mapper，复用 Vert.x 原有模块和工厂，加入 Kotlin 模块，保留未知字段兼容、primitive null 的旧转换、尾随 JSON 拒绝、二进制及日期格式；删除原有启动时修改 Jackson 2 mapper 的代码。
4. 运行完整后端测试及 bootJar，检查运行产物中实际依赖。隔离实例验证书源导入、搜索、详情、目录、正文、登录、缓存、导出与备份恢复，不向主用户数据写入 fixture。
5. 更新依赖清单，重启主服务，核对健康状态与原有书架／书源身份。当前 React 和 UI 不变，不增加新的运行功能或替换 HTTP 路由框架。

官方依据：

- [Spring Boot JSON 支持](https://docs.spring.io/spring-boot/reference/features/json.html)
- [Vert.x 5.2.1 Jackson 3 mapper](https://github.com/eclipse-vertx/vert.x/blob/5.2.1/vertx-core/src/main/java21/io/vertx/core/json/jackson/v3/DatabindCodec.java)
- [Vert.x JsonFactory SPI](https://github.com/eclipse-vertx/vert.x/blob/5.2.1/vertx-core/src/main/java/io/vertx/core/spi/JsonFactory.java)
