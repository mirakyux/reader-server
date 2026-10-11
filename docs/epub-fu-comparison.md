# EPUB 导出与 FU 对照

2026-10-09 对照 `E:/workspace/Java/legado_FU` 源码并补齐以下差异。FU 仓库只读，未执行 Android 构建；具体实施和验证见 [补齐记录](epub-fu-completion-plan.md)。

## 已对齐或基本一致

| 项目 | FU | reader-dev 当前状态 |
| --- | --- | --- |
| EPUB 版本 | 2.0 | 2.0 |
| ZIP 入口 | 首条 mimetype，STORED，设置 CRC | 一致 |
| 容器入口 | META-INF/container.xml 指向 OEBPS/content.opf | 一致 |
| 章节文件 | Text/chapter_N.html，声明 application/xhtml+xml | 一致；html 扩展名不是当前独有问题 |
| 正文段落 | 按行输出 p，图片单独处理 | 同样输出 p，额外进行 XML 转义；普通中文正文不会因这个差异变空 |
| 章标题 | includeTitle=false，不把标题再加入正文 | 上次已修正；title 和 heading 显示标题，正文不重复 |
| 标题边距 | h2.head 使用正边距 1em | 上次已改为正边距 |
| NCX 生成 | epublib NCXDocumentV2 | 输出结构基本一致；主要差异为 Android 日志适配 |

## 本轮补齐的差异

| 项目 | FU 行为 | 当前行为及影响 | 依据 |
| --- | --- | --- | --- |
| 封面编码 | 图片解码后实际压缩成 JPEG；透明底铺白色 | 已接入书源封面解码、真实 JPEG 转换和白底；网络失败可用模板／默认封面 | FU ExportBookService.kt setCover/toJpegBytes；BookController.kt setCover、EpubImages.kt |
| 卷目录 | 卷作为 parentSection，后续章节加入其子目录 | 已使用相同父子目录结构，保留正文顺序 | FU setEpubContent collectIndexed；当前 setEpubContent |
| 同一行多张图 | flowImages 遍历全文所有匹配 | 已遍历全文、去重并缓存全部图片；并发去重及原子写入经过回归 | FU help/book/BookHelp.kt flowImages；当前 help/BookHelp.kt saveImages |
| 图片解码 | 缓存前执行 ImageUtils.decode，支持书源图片解密 | 已复用 SourceActions 图片解码，提供 book、chapter、src、result 等上下文；支持 data 图片 | 两边 BookHelp.kt saveImage；当前 SourceActions.kt |
| WebP 资源 | MediaTypes 包含 image/webp；Android 可解码封面 | 已补媒体类型；EPUB 2 正文 WebP 转 PNG、封面转 JPEG，避免只声明类型而不兼容 | MediaTypes.java、EpubImages.kt、browser/server.mjs |
| 净化开关 | config.useReplace 与 book.getUseReplaceRule 同时为真才应用 | TXT／EPUB 同时尊重请求开关与单书开关，保持原始缓存 | FU setEpubContent；当前 exportToTxt、exportToEpub |
| 交互图片过滤 | 可移除带 click 的交互图片 | EPUB 默认过滤，可在导出样式中关闭；TXT 移除图片标签 | FU ExportContentImageProcessor.kt；当前 EpubImages.kt、exportToTxt |
| 自定义导出模板 | 读取 Asset 中的正文模板和附加资源 | 已支持按用户上传 ZIP、状态查询、恢复内置及临时禁用，接入 React；兼容 FU 布局、W3C XHTML DTD、{ori_title} 与中文资源路径 | FU setAssetsExternal；当前 EpubTemplateStore.kt、BookDownload.tsx |

## 共同问题与有意保留的差异

FU 的 fixPic 也会在缓存图片不存在时执行包内路径替换，旧 reader-dev 有同样问题。当前已删除旧 fixPic，导出时自动补齐遗漏图片、重新获取未解码或损坏的旧缓存；正文必需图片失败会中止导出，避免悬空引用。

上次修复前，当前内置 chapter.html 与 fonts.css 和 FU 基本相同。删除设备专属字体路径、删除强制 DK-SONGTI metadata、使用 XHTML 1.1 声明、注册封面 guide、补 alt 以及省略空 guide，是面向通用阅读器的规范修复，并不是逐字复制 FU 后消除的独有差异。

当前正文和元数据保留 XML 转义，并显式使用 UTF-8；FU 的 ResourceUtil 直接拼接部分 HTML 并使用默认编码。当前常规书源正文已转换成文本和图片标签，直接复制 FU 的未转义实现会使 &、< 等普通正文符号破坏 XHTML，因此应保留当前转义行为。

当前 EpubWriter 会传播章节资源读取失败并正确关闭流；FU 多处捕获异常后记录日志。本项目的失败处理应继续保留，防止返回章节缺失的导出文件。

## 对正文空白问题的结论

源码对比没有发现普通中文段落被直接丢弃的明确差异。之前实际书籍的全部 326 章、1,276,128 字正文已经逐章与缓存核对，并通过 EPUBCheck 和独立 MuPDF EPUB 引擎检查。因此不能把“有上述差异”直接等同于“已经找到正文空白的唯一原因”。

对图片构成的正文，同一行漏图、缺少解码和资源类型是已补齐的内容缺口，新增真实 WebP 正文／封面联测在 EPUBCheck、Edge 及 React 导入阅读链路中通过。封面、卷目录、净化开关及模板也已单独回归。

完整后端 114 项测试、前端 46 项单元测试和 34 项浏览器测试、浏览器服务 4 项测试均通过。7 份标准／图片／模板／真实书样本经 EPUBCheck 为 0 错误、0 警告；中文且含空格文件名样本为 0 错误、5 条文件名兼容警告。真实书正文再次逐章与缓存核对，独立 EPUB 引擎可读取全部 326 章。WebP 转换依赖现有浏览器服务；自定义模板仍需符合 EPUB 2 格式约束。既有修复证据见 [兼容修复记录](epub-compatibility-plan.md)，新增证据见 [补齐记录](epub-fu-completion-plan.md)。
