# Reader Web

独立部署的 React 阅读前端，采用 TypeScript、Vite、shadcn/ui、Radix 和 Tailwind CSS。组件使用 MIT 开源许可，不依赖付费 UI 版本。shadcn/ui 的许可保留在 `LICENSE-shadcn.md`。

## 开发

需要 Node.js 22.12 或以上，推荐 Node.js 24。

```sh
npm ci
npm run dev
```

默认地址为 `http://localhost:5173`。开发服务器将 `/reader3`、`/assets`、`/epub`、`/health` 代理至 `http://localhost:8080`；可通过 `READER_API_PROXY` 改变后端地址。

复制 `.env.example` 为 `.env.local`，配置 `VITE_API_BASE_URL`。默认 `/reader3` 适合开发代理与生产同源反向代理；独立域名可使用完整后端地址，后端需要允许前端 Origin 和带凭据请求。也可在设置页切换 API 地址。

## 构建与验证

```sh
npm run typecheck
npm run lint
npm test
npm run test:e2e
npm run build
npm run preview
```

`dist` 为独立静态站点，前端构建资源位于 `/static`，与后端用户文件 `/assets` 分离。前端不写入后端 JAR。`Dockerfile` 与 `nginx.conf` 提供静态部署和对 `reader:8080` 的 API 代理，SSE 禁止代理缓冲。

浏览器回归在 Windows 使用已安装的 Edge，在其他系统使用 Playwright Chromium（首次执行前运行 `npx playwright install chromium`）。测试拦截后端请求，以验证完整交互而不修改真实书架。

模拟浏览器测试使用单独的 `15173` 端口，与正式开发页面 `5173` 分离；阅读计时由单元回归和隔离真实联测验证。

导航统一为顶部“书架、找书、更多”。找书内可切换搜索与发现；书源、书签、订阅、替换规则、本地书仓、备份和设置从“更多”进入。书架的更新、批量及分组操作位于“管理”菜单。

`scripts/check-browser.mjs` 保存桌面与移动端截图到 `screenshots`。真实联测脚本 `scripts/check-integration.mjs` 使用隔离的 `18080` API 和代理它的 `4173` Vite，验证书源导入、动态登录、搜索、阅读、整本缓存与 TXT / EPUB 下载；临时书源和书架数据在结束时清理。截图和下载结果均不进入 Git。

`node scripts/check-layout.mjs` 检查当前服务的桌面、手机和 320 像素窄屏导航与菜单，只访问书架并保存截图。可通过 `READER_LAYOUT_FRONTEND` 指定网页地址。

## 功能

- 书架、内置与自定义分组、批量分组与移除、分组排序、搜索和书源发现。
- 正文阅读、目录、换源、进度、主题、字体、字号、字重、行高、段间距、简繁转换、翻页、自动滚动、书签及浏览器语音朗读。
- 书源 JSON / 单文件 JavaScript 预览导入、远程导入、编辑、分组、批量启停、导出、SSE 调试、动态登录表单和 Cookie 管理。
- 书签、正文替换规则、RSS 分类与文章、服务器书仓、TXT / EPUB / UMD / CBZ 本地书导入、TXT 正则与 EPUB 目录重建、EPUB 原排版与音频播放。
- WebDAV 文件与备份、账户登录注册、用户管理、阅读配置备份。
- 用户空间隔离的 IndexedDB 正文缓存，服务器完整章节缓存后导出 TXT / EPUB；失败可重试，未完整缓存时禁止下载。
- 自定义字体文件、背景图片上传与 URL、资源管理、阅读方案切换与导入导出。可读取旧 Vue 配置，服务器备份合并保留旧字段。
- AI 服务与模型发现、可编辑净化提示词、正文净化与原文恢复、流式阅读助手、会话历史、Skills 和显式只读工具授权、MCP 连接验证。
- 在线角色语音：按角色配置音色、编辑朗读片段、长章节分批合成、取消与缓存复用，认证音频连续播放。
- 按日期与书籍查看阅读时长和字数估计；隐藏页面与五分钟后的闲置不计时，离线记录下次阅读时重试同步。
- 书源实际校验、发现控件与筛选动作、服务器浏览器交互登录、付费章节确认、段评与引用、图片解码、文件书源下载列表和脚本请求的浏览器/视频交互。
- 范围书签支持跨章节、颜色和背景/下划线/波浪线；替换规则支持标题/正文、精确作用与排除范围、执行顺序和 Worker 超时。导出可选择应用替换规则。

浏览器听书使用系统提供的语音库；可用语音取决于浏览器和操作系统。本地文件格式支持范围由后端决定。

AI 与在线语音需要在设置中填写自己的兼容服务。API 密钥留空时保留服务器已有值，勾选“清空服务器密钥”才会删除。MCP 复制配置包含当前账户认证，供本人的客户端连接使用。

`scripts/check-advanced-integration.mjs` 对隔离后端启动临时本地模型/语音服务，通过真实 React 页面验证模型发现、净化、流式工具调用与持久化会话、角色 MP3、阅读统计和 MCP；书源、书架、服务配置和会话会在结束时清理或恢复。该联测脚本需本机有 FFmpeg，以生成可播放的 MP3 样本；产品运行不依赖 FFmpeg。
