# 书架下载入口

2026-10-09：书架可以直接右键书籍，选择“下载 TXT”或“下载 EPUB”，再点击“缓存全部并导出”。手机及触屏设备可以点击书名旁的“…”打开同一菜单。

## 修改文件

- `web/src/components/Library.tsx`：书架卡片增加右键菜单及可点击的操作菜单，网格和列表共用操作项；支持 Shift+F10 和菜单键。普通点击封面仍进入阅读，点击书名仍打开详情，搜索结果不增加书架下载操作。
- `web/src/components/BookDownload.tsx`：下载弹窗支持菜单选择的初始格式，并将焦点恢复目标传给弹窗控件。
- `web/src/components/Controls.tsx`：弹窗增加可选的关闭后焦点恢复目标，使书架下载弹窗关闭后能继续操作原书籍。
- `web/src/styles.css`：为书名与菜单按钮、列表布局及右键菜单增加样式。
- `web/tests/browser/shelf-download.spec.ts`：增加 9 项浏览器回归测试，覆盖两种格式、完整缓存、失败重试、三种屏幕宽度、两种布局、键盘与焦点、点击行为、类型限制及搜索结果。

## 复用与范围

右键菜单和“…”菜单共用操作项，下载继续调用既有 `BookDownload`、SSE 缓存及 `exportBook` 流程，没有增加依赖、后端接口或另一套导出逻辑。缓存全部完成后才导出；缓存失败显示失败章节并支持重试。

本地书及音频等类型沿用原有限制：文本下载项不可用，菜单说明原因。本次没有扩展本地书导出、远程导出或 EPUB 文件格式。

## 验证

新增测试在修改前复现了“书架右键没有下载菜单”。修改后完成以下验证：

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| ESLint | 通过 | `logs/shelf-download-lint.log` |
| TypeScript 类型检查 | 通过 | `logs/shelf-download-typecheck.log` |
| 前端单元测试 | 20 个文件、75 项通过 | `logs/shelf-download-unit.log` |
| Edge 浏览器回归 | 74 项通过，包含新增 9 项 | `logs/shelf-download-browser.log` |
| 新增测试页面错误断言 | 修正替换规则模拟接口类型后，9 项再次通过，页面错误为空；测试文件 lint、整体类型检查再次通过 | `logs/shelf-download-targeted.log`、`logs/shelf-download-vite.log` |
| Vite 生产构建 | 通过 | `logs/shelf-download-build.log` |
| 当前运行页面 | 右键菜单、EPUB 初始格式、弹窗关闭焦点通过，无页面错误 | `logs/shelf-download-live.json` |
| 视觉检查 | 1440、390、320 像素的网格及列表菜单在屏幕范围内，保留顶部紧凑导航 | `web/screenshots/shelf-download-*-grid.png`、`web/screenshots/shelf-download-*-list.png` |

当前页面验证使用 `http://localhost:5173/#/`，只发送读取请求。验证前后的 1 本书、10 个书源身份一致，没有触发真实整本缓存或导出。完整缓存后 TXT/EPUB 文件下载及缓存失败后的重试由浏览器接口夹具验证。

真实页面截图：`web/screenshots/shelf-download-live-menu.png`、`web/screenshots/shelf-download-live-dialog.png`。

## 剩余限制

正文能否缓存仍取决于书源网络和站点可用性；失败时保留缓存并阻止缺章导出。构建仍报告原有 Reader 分包约 1.39 MB 的体积警告。本次没有重新运行后端测试或外部 EPUB 阅读器兼容性校验，因为后端和文件生成逻辑未改动。

工作区已有 `BookSourceController.kt` 的四处行尾空白，整体 `git diff --check` 会报告这些既有问题；本次未修改该文件。
