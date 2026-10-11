# QuickJS 书源兼容补齐

本轮按用户要求补齐 FU 的 `java.getQuickJsSandbox().evalString(script)`，动态场景不在范围内。FU 仓库只读，保留当前工作区迁移和已有用户数据。

1. 在现有 Node 辅助服务中接入真正的 QuickJS WebAssembly 运行时。只安装发布版同步引擎及其绑定，不加入调试版、Asyncify 或浏览器客户端依赖。它是本轮补齐 QuickJS 接口所需的运行时组件。
2. 每次调用使用独立 worker 和运行时，沿用 FU 的 384000 字符输入、65536 字符输出、64 MiB 引擎内存、512 KiB 栈和 15 秒截止时间；不暴露文件、网络、Node、Java 或外部模块。执行失败不回退 Rhino。
3. Kotlin 桥沿用已有辅助服务地址和认证，仅向单文件 JS 书源的 `java` 宿主暴露接口，与 FU 保持一致；常规声明式规则不新增该方法，不占用用户自建的 `isolatedJs` 全局。锁定字符串及 Promise 的实际 FU 语义。
4. 先验证真实引擎的现代语法、字符串返回、异步任务、环境隔离、资源限制和失败后恢复；再验证 Java 桥和真实书源搜索、详情、目录、正文流程。运行后端测试和构建、辅助服务回归及已有前端检查。
5. 更新兼容矩阵和部署说明，重启最终服务并复核健康状态。验收只操作隔离实例和测试书源，不修改主用户书架及书源。

继续对照 FU 书源契约补齐已有接口，不引入另一套书源模型：允许 FU 模板使用的安全 Java 包导入；共享缓存按用户隔离并供书源和订阅源复用；jsLib 采用独立共享父作用域和请求子作用域，刷新时同时失效磁盘库与作用域。编码、简繁转换、加密、压缩包读取、字体映射、动态并发和发现刷新沿用现有服务能力，补充真实输入与隔离回归。当前阅读配置在调用书源前保存到服务端，避免配置桥读取旧备份。

QuickJS Promise 拒绝追踪通过原生 `JS_SetHostPromiseRejectionTracker` 接入，保留 Promise 行为；构建产物随辅助服务发布，生产环境不需要 Emscripten。各项差异完成后统一运行后端、前端、辅助服务与隔离端到端检查。

参考：FU `QuickJsSandboxBridge.kt`、`QuickJsSandboxService.kt`、`QuickJsSandboxProtocol.kt`；[QuickJS WebAssembly 官方绑定](https://github.com/justjake/quickjs-emscripten)。
