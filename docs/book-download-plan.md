# 全章缓存与下载

React 客户端与 API 使用整本缓存后导出的流程。复用 `cacheBookSSE`、`exportBook` 与 EPUB 写入器，不引入新的书籍格式库。

## 操作

在书籍详情、阅读设置或设置中的书架缓存列表点击“缓存与导出”，选择 TXT 或 EPUB，点击“缓存全部并导出”。页面显示全部章节的进度；失败时显示章号，点击“重试并导出”继续。全部完成后浏览器下载文件。

## 行为

- 从书架或阅读页面选择 TXT / EPUB 后缓存全部目录章节。
- 逐章推送总数、成功数、失败数、缓存数；已有有效缓存直接复用。
- 失败后继续处理其他章节，列出失败章节；重试仅获取未成功章节。
- 关闭或取消下载中断后续抓取，保留已完成的缓存。
- 导出端独立检查全章缓存，普通章节缺失或正文为空时拒绝导出；空的卷标题章节仍保留标题并正确计入缓存。
- 本地书维持原始文件下载；在线文字书支持 TXT 与 EPUB，音频、视频、图片和文件型源不作为文字正文导出。
- 可选择应用替换规则，分别处理标题和正文的作用范围；章节原文缓存保持不变。
- 源脚本在整本缓存期间请求网页验证时，界面显示浏览器交互；验证完成后继续抓取，取消会同时停止交互轮询。
- EPUB 使用 UTF-8，转义标题和正文中的 XML 特殊字符，并保留缓存插图的相对路径；写入失败会终止导出。
- 生成的临时文件位于用户隔离的缓存目录，传输完成后删除，不放入公共静态资源目录。

## 接口

- `GET /reader3/cacheBookSSE?url=...&refresh=0&concurrentCount=4`。
- 常规 `data` 与最终 `event: end` 增加 `totalCount`、`failedChapters`、`complete`，保留 `cachedCount`、`successCount`、`failedCount`。
- `POST /reader3/exportBook`：`{url, isEpub: 0|1, useReplace?: boolean}`，完成时返回带 `Content-Disposition` 的二进制文件；失败返回已有 JSON envelope。不传 `useReplace` 时采用服务端 `exportUseReplace` 配置。
- 继续使用会话、`accessToken` 与命名空间规则，不把测试数据写到真实用户书架。

## 验证

使用本地 HTTP / JS 书源 fixture 验证搜索、入架、目录、全章缓存、TXT 与 EPUB 下载。验证缺章拒绝导出、失败重试、缓存复用、中文及 XML 特殊字符；解压 EPUB，检查 mimetype、OPF、目录和每章正文，不以扩展名作为成功依据。
