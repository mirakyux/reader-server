# 书源能力迁移

保留现有声明式书源与 `/reader3` 接口，补齐对照 `legado_FU` 的服务端书源功能。React 界面通过独立 API 使用书源解析、登录、发现筛选和交互动作。

实施顺序：

1. 添加旧源、缓存与登录状态、单文件 JavaScript 抓取的回归用例。
2. 修复字符串缓存，统一持久化与过期语义；按用户和书源隔离 Cookie、脚本缓存和文件。
3. 增加 `mainJs`、`jsLib`、登录表单和自动 Cookie 的导入、校验、执行。
4. 在现有 WebBook 五个业务入口分流单文件 JavaScript，继续复用 HTTP、目录缓存和 SSE 调试。
5. 提供导入预览、登录、Cookie 管理接口，并使用本地 HTTP fixture 验证完整链路。

单文件契约使用顶层 `config`（兼容旧 `source`）。文本、音频、图片和视频源要求 `search(key,page)`、`getChapters(book)`、`getContent(chapter,book,nextChapterUrl)`，`getBookInfo(book)` 可选；文件型源 `bookSourceType:3` 只要求 `search`、`getBookInfo`，详情必须返回非空 `downloadUrls:string[]`。发现和登录声明必须有对应函数。每次执行使用独立脚本作用域，配置不会在用户之间共享。

## 本次书源能力

### 第二轮实施范围与验证计划

先为现代源未知字段往返、文件型 JS 契约、目录购买状态和共享格式化变量、标题及副内容、图片解码、购买/事件、发现控件和段评请求添加回归，再逐项实现。复用 `AnalyzeRule`、`AnalyzeUrl`、隔离脚本运行时和已有用户存储，不引入新依赖。

书源动作统一放在新 `SourceActionController`，所有脚本读取当前用户已保存书源；客户端只能传业务数据、事件名和控件标识，不能传任意脚本。购买操作要求显式 `confirm:true`；阅读事件使用固定白名单。段评的 Legado 模型存在但解析入口被注释，因此本项目提供明确的请求/引用/解析协议，不宣称已有 Android 完整段评 UI。

动态发现沿用 Legado `ExploreKind` 的 `url/text/button/toggle/select`、`action/chars/default/viewName` 和 `infoMap` 契约。`exploreScreen` 在对照仓库仅保存而没有执行实现，本项目将它定义为与发现列表相同的筛选控件集合并公开接口。

浏览器/验证码由独立 Playwright sidecar 负责：后端桥请求携带 URL、HTML、已保存源脚本、请求头和源隔离 Cookie；响应返回页面内容、最终 URL 和更新后的 Cookie。用户交互会话按用户和书源隔离；React 通过截图与输入接口完成手动验证，再回传 Cookie 和网页状态。运行配置见 [deployment.md](deployment.md)。

对照目录为 `E:\workspace\Java\legado_FU`，下表中的参考路径均相对于该目录的 `app/src/main/java/io/legado/app/`。状态描述实际执行能力，而不是 JSON 中存在同名字段。

| 能力 | 本次结果 | Legado 对照实现 |
| --- | --- | --- |
| 旧版、声明式 JSON 书源导入 | 保留旧字段转换和嵌套搜索、详情、目录、正文规则；CSS、XPath、JSONPath、JS 均有回归 | `data/entities/BookSource.kt`、`help/source/SourceHelp.kt`、`ui/association/ImportBookSourceViewModel.kt` |
| 单文件 JS 书源 | 支持原始 `.js` 与 JSON `mainJs`，导入时检查名称、URL、函数及发现、登录配对 | `model/jsSource/JsSourceConfig.kt` |
| JS 搜索与发现 | 支持 `search(key,page)`、`explore(url,page)`；校验数组和书籍 URL，补全源信息及相对 URL | `model/jsSource/JsSourceBook.kt` |
| JS 详情、目录和正文 | 支持可选详情合并、目录顺序及索引、相对地址、下一章参数；正文要求非空字符串 | `model/jsSource/JsSourceBook.kt`、`model/jsSource/JsSourceMarshaller.kt` |
| 公共 JS 库 | `jsLib` 支持内联代码、HTTP URL 和名称到代码/URL 的 JSON 映射；与声明式规则共用 | `model/SharedJsScope.kt`、`model/jsSource/JsSourceEngine.kt` |
| 远程库与动态请求头 | 下载库时使用静态请求头、持久化登录头及该源 Cookie；库加载完成后正常请求头可以调用库函数；下载失败不缓存错误正文 | `data/entities/BaseSource.kt`、`model/SharedJsScope.kt` |
| CryptoJS | 内置共享资源，支持哈希、加解密库及基于 `SecureRandom` 的随机字节 | `model/SharedJsScope.kt`，资源位于 `app/src/main/assets/scripts/cryptojs.min.js` |
| 登录 UI | 支持静态表单，以及 `loginUi(state)` / `loginAction(action,state,form)` 动态表单；支持字段错误、状态更新、关闭和登录凭据保存 | `model/login/LoginUiV2.kt`、`model/login/BaseSourceLoginV2.kt` |
| Cookie 请求链路 | `ajax/connect`、Jsoup `get/post` 和远程库使用用户、书源隔离的 Cookie；支持重定向、domain/path/secure/expiry 和关闭自动保存 | `help/http/CookieStore.kt`、`help/JsExtensions.kt` |
| 缓存及登录状态 | 字符串缓存真实持久化；TTL 与永久缓存区分；用户和书源隔离；登录信息 AES-GCM 加密 | `help/CacheManager.kt`、`data/entities/BaseSource.kt` |
| 脚本文件桥 | 文本、二进制、下载及解压使用用户、书源缓存目录；拒绝路径越界和 ZIP 路径穿越；字体缓存也按源隔离 | `help/JsExtensions.kt` |
| 规则兼容修复 | XML 标签保持大小写；切片越界返回空、负步长取绝对值；排除索引不会删除原文节点或影响后续规则 | `model/analyzeRule/AnalyzeByJSoup.kt` |
| 调试 | 五个 `WebBook` 入口共用原有 SSE 调试，脚本日志进入现有调试通道 | `model/Debugger.kt`、`web/socket/BookSourceDebugWebSocket.kt` |
| 现代源字段往返 | 顶层及嵌套规则的未知字段在导入、存储和导出中保留；已知字段继续使用类型模型解析 | `data/entities/BookSource.kt`、`data/entities/rule/*.kt` |
| 搜索校验 | `checkBookSource` 优先采用 `ruleSearch.checkKeyWord`，非法 URL 和特殊组合表达式回退默认关键词 | `data/entities/BookSource.kt` 的 `getCheckKeyword` |
| 目录格式与收费状态 | `isVip/isPay` 独立保存；`formatJs` 在排序、去重后执行，共享 `gInt`，`index` 从 1 开始，更新最新章节标题 | `model/webBook/BookChapterList.kt` |
| 文件型源 | 声明式和 JS 详情均解析、补全及去重 `downloadUrls`；文件型源不伪造目录或正文 | `model/webBook/BookInfo.kt`、`model/jsSource/JsSourceBook.kt` |
| 标题与副内容 | 正文 `title` 覆盖章节标题；文本副内容追加正文；音频歌词、视频弹幕保留在章节数据和变量中 | `model/webBook/BookContent.kt` |
| 图片与封面解码 | 请求沿用源的 headers、Cookie 和 URL 规则；`coverDecodeJs/imageDecode` 处理字节数组，返回 base64 和识别的媒体类型 | `utils/ImageUtils.kt` |
| 购买和阅读事件 | 已保存 `payAction` 和 `callBackJs` 可执行；购买要求确认，URL 型购买返回链接；事件严格使用白名单 | `model/SourceCallBack.kt`、`model/webBook/BookContent.kt` |
| 发现筛选与按钮 | 动态 `url/text/button/toggle/select`、`action/viewName` 和源隔离 `infoMap`；客户端按分组、索引和合法选项操作 | `data/entities/rule/ExploreKind.kt`、`help/source/ExploreKindBehavior.kt` |
| 段评读取与引用 | `ruleReview.reviewUrl/reviewQuoteUrl` 请求后按内容、头像和时间规则解析；沿用源的认证状态 | `data/entities/rule/ReviewRule.kt`，对照仓库读取入口尚未启用 |
| WebView 规则 | `java.webView/webViewGetSource/webViewGetOverrideUrl`，以及 URL 选项 `webView/webJs/webViewDelayTime` 使用浏览器执行；保留 headers 和最终 URL | `help/JsExtensions.kt`、`model/analyzeRule/AnalyzeUrl.kt` |
| 浏览器与视频动作 | `startBrowser/openVideoPlayer` 写入隔离的交互队列；`startBrowserAwait` 返回最终 URL/DOM 并继续原脚本；`getVerificationCode` 展示图片、接收输入并返回验证码；React 消费动作 ID | `help/JsExtensions.kt` |

## 接口

全部接口沿用 `/reader3`、既有身份验证和 `{isSuccess,data,errorMsg}` 响应结构。以下为 POST 请求。

| 路径 | 请求体 | `data` |
| --- | --- | --- |
| `/parseBookSource` | `{content}`，内容为 JSON 或原始 JS | 规范化的书源数组，供导入前检查 |
| `/getBookSourceLoginUi` | `{bookSourceUrl,state?}` | `{version,rows,state}` |
| `/loginBookSource` | `{bookSourceUrl,form,action?,state?}` | `{success,close,state,errors,rows?}` |
| `/getBookSourceCookies` | `{bookSourceUrl}` | `{cookie}` |
| `/saveBookSourceCookies` | `{bookSourceUrl,cookie}` | `{cookie}` |
| `/clearBookSourceCookies` | `{bookSourceUrl}` | `{cookie:""}` |
| `/getSourceCapabilities` | `{bookSourceUrl}` | 源类型、校验关键词、解码、购买、段评、事件、发现控件的能力声明 |
| `/checkBookSource` | `{bookSourceUrl,key?}` | `{key,books,count}` |
| `/getSourceExploreUi` | `{bookSourceUrl}` | `{categories,filters,values,eventListener,customButton}` |
| `/applySourceExploreAction` | `{bookSourceUrl,group,index,value?}` | 最新发现 UI 加 `{result,actions}` |
| `/runSourceAction` | `{bookSourceUrl,action,book?,chapter?,event?,result?,confirm?}` | `{handled,result,url?,success?,actions}` |
| `/getSourceReviews` | `{bookSourceUrl,book,chapter?,quote?,review?,paragraph?,index?}` | `{url,body,reviews:[{content,avatar,postTime}],quote}` |
| `/decodeSourceImage` | `{bookSourceUrl,url,isCover?,book?}` | `{url,base64,contentType}` |
| `/drainSourceInteractions` | `{bookSourceUrl?}` | `{actions:[{id,type,url,title,isFloat,waiting,bookSourceUrl}]}` |

表单行使用 `{name,type,key,action?,placeholder?,value?,options?}`，选项为 `{label,value}`。支持 `text`、`password`、`label`、`select`、`toggle`、`button`；密码字段不把已保存的密码回填到前端。Cookie 清空作用于当前用户的当前书源。

发现控件行使用 `{index,title,label,type,url?,chars?,default?,value?,viewName?,style?}`；`group` 仅为 `categories` 或 `filters`，`select/toggle` 的值必须存在于 `chars`。`action` 仅为 `pay` 或 `event`；购买需提交书籍、章节及 `confirm:true`。客户端只提交参数，不提交可执行脚本。

交互动作的 `type` 为 `browser` 或 `video`。`drainSourceInteractions` 清除待投递队列，动作 ID 在 5 分钟内仍能从同一用户、同一书源解析；等待验证的动作完成或超时后立即回收。浏览器 HTML 留在后端，不随队列响应返回。传源 URL 只消费该源，省略 URL 消费当前用户已保存源的队列，适用于多源搜索；每个动作包含所属 `bookSourceUrl`，不会访问其他用户的队列或执行源脚本。前端在普通源请求和缓存、调试 SSE 未返回期间轮询队列，连接关闭时停止。`startBrowser` 支持 `(url,title)` 与 `(url,title,html)`，`openVideoPlayer` 支持 `(url,title)` 与 `(url,title,isFloat)`；浏览器会话使用 `interactionId` 打开该已保存动作。打开验证页使用静态和已保存登录头，避免重复执行正在等待验证的动态头脚本。

书源 SSE 在正常结束或服务端错误时，停止轮询并最后读取一次交互队列。首次轮询前结束的请求也能投递验证动作。用户主动取消时，前端抑制尚未返回的交互响应。

## 兼容边界

以下记录书源的执行与验证条件。手机亮度、音量和启动其他手机应用等设备操作不在服务器端需求范围，不计为书源兼容缺口。

- 常规 JS 使用官方 Rhino ES6 解释模式；请求使用独立子作用域，jsLib 使用按用户、源和库内容缓存的共享父作用域，跨线程保留库状态；无库时 CryptoJS 按线程缓存。单文件源的 `java.getQuickJsSandbox().evalString()` 运行与 FU 同版本的真正 QuickJS，支持顶层 await，不向 QuickJS 暴露 DOM、Node 或 Java。具体限制及验证见 [书源兼容验收](source-compatibility-verification.md)。
- 支持 FU 模板使用的 `org.jsoup`、JSONPath、Gson、安全集合与加密包导入及既有宿主接口，仍拒绝反射、进程、任意文件和其他用户宿主构造。脚本不能修改源 URL 或运行时用户身份；书源需要的网页、验证、文件与缓存能力通过相应 Web／服务端宿主接口提供。
- 注入的 `cache` 与 FU 一样在书源和 RSS 间共享，服务端按用户隔离；内存保留原始对象且不使用磁盘 TTL，普通磁盘缓存和文件缓存使用独立目录。Cookie、登录信息和书源文件继续使用用户、源私有目录；内部网页交互和页面缓存显式读取磁盘，以保留其有效期。
- DOM 脚本仅在浏览器 sidecar 执行；Rhino 规则脚本使用 Java 桥调用浏览器。浏览器服务需要独立启动并配置认证令牌。`startBrowserAwait` 支持 2、3、4 参数重载，等待最长 120 秒；关闭验证页后原脚本取得 `StrResponse`，人工等待不占用 30 秒脚本指令预算。`refetchAfterSuccess:true` 在完成时重新加载当前页面再返回 DOM，`false` 返回当前 DOM。等待超时后需重新操作。
- `getVerificationCode(imageUrl)` 复用同一等待链路，支持源地址解析的 HTTP 图片。用户输入后关闭窗口，原脚本得到非空验证码；没有输入时明确失败，不把空验证码当成功。
- 首次获取远程 `jsLib` 时，动态请求头依赖的函数尚不可执行。需要认证的库应使用静态 `header`、已保存的登录头或 Cookie；后续普通请求照常执行完整动态请求头。
- Cookie 遵循到期和 URL 匹配规则；会话 Cookie 仅保留在当前进程，持久 Cookie 写入源缓存。登录加密密钥位于缓存目录 `.source-secrets.key`，清除该密钥后需要重新登录。
- `enabledCookieJar:false` 同时关闭 HTTP 与浏览器的自动 Cookie 保存及回放；浏览器仍可保存当前源的 localStorage。显式 headers 中的 Cookie 仍属于源自己提供的请求头。
- 脚本有 30 秒指令执行上限；进入 HTTP/Java 桥的阻塞调用仍由相应网络超时控制。运行时限制不等同于独立进程沙箱。
- 段评发布、引用发布、点赞、点踩和删除 URL 字段保留，但没有写入执行接口；本次段评协议支持请求、引用请求和内容解析。对照仓库也没有启用完整段评执行入口。
- `exploreScreen` 采用本项目定义的筛选控件协议；对照仓库同名字段没有运行逻辑。不会根据任意未知字段自动执行脚本。
- URL 型 `payAction` 展开后交给用户打开；JS 型规则按返回值判断结果。收费和网站认证流程取决于书源与目标站点规则。

## 横向能力差异

| 能力 | reader-dev 现状与缺口 | Legado 对照实现 | 补齐方向 |
| --- | --- | --- | --- |
| AI 内容净化 | provider/model/prompt 配置、正文净化与原文恢复、关联缓存 | `help/ai/AiPurifyHelper.kt`、`AiManager.kt`、`AiModelRegistry.kt` | React → Java → 本地协议服务联测通过 |
| AI 助手、工具和 Skills | 会话、阅读上下文、SSE 聊天和工具/Skills 配置 | `help/ai/AiChatClient.kt`、`AiChatContextManager.kt`、`AiSkillRegistry.kt`、`AiSkillPackageRegistry.kt` | 流式回答、工具结果与会话持久化联测通过 |
| MCP | 新增 MCP 工具发现和调用适配层 | `web/mcp/McpServer.kt`、`McpToolExecutionContext.kt` | 工具复用当前用户数据和身份验证 |
| 多角色朗读 | 新增 TTS 配置、角色映射、远程合成和音频缓存 | `help/tts/ReadAloudTtsRouter.kt`、`ReadAloudAudioPreparation.kt`、`ReadAloudCacheManager.kt`、`model/ReadAloud.kt` | 保留浏览器朗读并提供远程播放器 |
| 替换规则作用范围 | 新增 `scopeTitle/scopeContent/excludeScope` 模型及匹配 | `data/entities/ReplaceRule.kt` | 分别应用标题和正文规则 |
| 范围书签与高亮 | 跨章选区、样式和颜色字段 | `data/entities/Bookmark.kt` | 普通正文与同源 EPUB/XHTML 的选区、跨章高亮和返回原文定位回归通过；真实上传 EPUB 联测通过 |
| 阅读统计 | 新增阅读会话、按书籍和日期汇总接口 | `data/entities/ReadRecord.kt`、`data/dao/ReadRecordDao.kt`、`ui/about/ReadRecordScreen.kt` | React 发送可见页面的活跃会话 |
| 备份和恢复 | 新增版本化完整数据包和恢复校验 | `help/storage/Backup.kt` | 覆盖用户实体及配置，拒绝不支持版本和路径越界 |

横向能力的实施与验收见 [gap-completion-plan.md](gap-completion-plan.md) 和 [verification.md](verification.md)，此文重点记录书源协议及其兼容边界。

## 验证范围

`BookSourceMigrationTests` 覆盖旧源和现代源、原始 JS/JSON 往返、函数契约、共享库、CryptoJS、缓存永久/到期、用户及源状态隔离、静态/动态登录、Cookie URL 规则、Jsoup GET/POST 桥、远程库动态请求头与下载失败、文件下载/解压越界、运行时身份不可修改，以及本地 HTTP fixture 的登录→302 Cookie→搜索→发现→详情→目录→正文和 GBK 解码。

`RuleCompatibilityTests` 另外覆盖 CSS、XPath、JSONPath、Java 桥及在同一原文上先排除节点再逆序读取，防止依赖升级改变规则结果。运行日志和最终结果以 Gradle 测试报告为准。

`SourceRuleCompletenessTests` 覆盖现代及未知字段往返、文件型 JS 与声明式下载列表、目录共享格式化变量与收费状态、正文标题及副内容。`SourceActionTests` 覆盖显式购买与事件权限、动态发现控件、段评/引用和图片解码。`SourceBridgeStateTests` 覆盖禁用浏览器 CookieJar 后不回放或保存 Cookie、保留 localStorage、URL 选项 headers，以及交互队列的用户隔离和动作 ID 在投递后仍可解析。

浏览器桥的 HTTP 集成验证及真实浏览器执行结果由主任务在 [verification.md](verification.md) 记录；纯规则测试不会把 sidecar fixture 等同于实际 DOM 与验证码验证。

`SourceVerificationTests` 覆盖验证码输入返回特殊字符、图片 URL 的安全序列化、非 HTTP 地址拒绝、空输入拒绝和身份隔离。`browser/check-java-stack.mjs` 验证六条 Java API → 实际 Edge 链路，包含实际图片加载、自动聚焦输入及关闭后原脚本继续。
