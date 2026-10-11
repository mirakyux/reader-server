# 代码与交互修正验证

2026-10-09。完成 `quality-fixes-plan.md` 中的修正，保留 React／shadcn 和顶部「书架／找书／更多」导航，未增加依赖。

## 修正结果

| 问题 | 修改与行为 |
| --- | --- |
| 目录重排错用正文 | 服务端正文按章节 URL 与摘要校验；目录插入、重排、删除时迁移正文、metadata、编辑与原文备份。删除章节的用户编辑保存恢复副本。浏览器 IndexedDB 也在同一事务中按 URL 迁移正文。 |
| 并发保存覆盖数据 | 复用 `UserDataFiles` 的按文件锁与 `AdvancedStore.atomicWrite`；书源、书架、分组及候选书源读改写使用同一事务。网络调用结束后合并最新书架，保留最新进度。 |
| Cookie 账户缓存混用 | 实际用户名独立于访问令牌保存；先读取服务器账户身份，再加载或回退离线数据。书架、正文、资源、活动书籍、待同步进度和统计按 API 与账户隔离。切换空间后停止旧队列；阅读配置同步也按账户区分在途保存。 |
| 搜索条件与游标混用 | 翻页沿用已提交的关键词、书源、分组及并发参数。修改输入后提示当前结果仍属于原查询；新搜索重置游标。 |
| 发现页与阅读刷新竞态 | 发现请求校验当前来源、分类及操作身份，忽略过时响应，包括 A→B→A。阅读刷新立即取消旧正文与预读，等新目录就绪后加载。忙碌状态覆盖全部在途操作。 |
| 编辑器丢稿 | Escape、遮罩和关闭按钮关闭有修改的书源编辑器时均确认；继续编辑保留内容和焦点，成功保存直接关闭。 |
| 书源操作拥挤 | 桌面保留编辑，调试、能力校验、登录、删除收进菜单；手机使用卡片，启停、编辑、选择、筛选、批量操作和分页保持可达。 |
| 设置页面过长 | 按连接与账户、阅读、缓存与下载、同步、统计分组；复用已有 Tabs 和各功能组件。 |
| 存储根不一致 | 正文与图片、封面、失效记录、模板、导出及书籍删除遵守配置的存储根；旧文件按旧／新目录身份迁移，已有配置目录内容优先。本地归档的原有资源与锚点行为保留。 |

## 修改文件

- 后端控制器：`BaseController.kt`、`BookController.kt`、`BookSourceController.kt`。
- 后端服务与工具：`ChapterCache.kt`、`ChapterEdits.kt`、`ChapterMetadata.kt`、`UserDataFiles.kt`、`VertExt.kt`、`Book.kt`、`BookHelp.kt`。
- 前端：`App.tsx`、`Library.tsx`、`Common.tsx`、`Sources.tsx`、`Settings.tsx`、`Reader.tsx`；`api.ts`、`cache.ts`、`navigation.ts`、`readingStats.ts`；`styles.css`、`settings.css`。
- 回归：`ChapterCacheTests.kt`、`StorageConsistencyTests.kt`、`FuReadingIntegrationTests.java`、图片缓存测试隔离配置；`account_scope.test.ts`、`action.test.tsx`、`source_preferences.test.ts`、四个 `quality-*.spec.ts`。既有浏览器测试和实际联测脚本更新菜单／设置分组入口。

## 验证证据

先确认新增回归在旧实现失败，再修复。最终后端 234 项／40 个测试套件全部通过，无失败、错误或跳过。前端 ESLint、TypeScript、75 项单元测试／20 个文件、65 项 Microsoft Edge 浏览器测试及生产构建通过。生产 Service Worker 在移除模拟 API 后验证离线冷启动、两个缓存章节及图片资源，账户资源空间隔离通过。

1440、390、320 像素截图已检查，书源、搜索、发现和设置无页面横向溢出；菜单键盘、焦点恢复、草稿确认和设置分类访问有浏览器回归。视觉记录位于 `.omx/state/quality-fixes/`。

实际联测使用 API 18080／前端 4173／浏览器桥 18090 的独立数据目录。QuickJS 书源桥接、真实浏览器验证流程、书源导入登录、搜索、阅读、完整缓存、TXT 与 EPUB 下载已通过。WebDAV 与 FU 兼容联测完成结果见最终 `logs/quality-verification-summary.json`。

日常服务已使用新 JAR 重启，原有 1 本书、10 个书源的 URL 身份核对一致，页面无运行错误。完整测试输出为 `logs/quality-backend-complete.log`，结构化证据为 `logs/quality-verification-summary.json`。

## 验证范围

仍有既有 Kotlin 编译警告、JVM 类共享提示及阅读模块构建体积警告。文件事务覆盖单个服务进程；未模拟断电或多个服务进程同时写同一目录。磁盘迁移失败时保留恢复快照。Docker 环境未验证。FU 项目只读，没有修改。
