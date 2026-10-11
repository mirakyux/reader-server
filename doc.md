# 前后端独立部署

React 客户端使用 TypeScript、Vite、shadcn/ui + Tailwind CSS；API 使用 Kotlin、Spring Boot、Vert.x，运行于 Java 21。前端产物为 `web/dist/`，后端产物为 `build/backend/libs/reader.jar`。JAR 不再提供首页。

## 本机运行

需要 Java 21 JDK 和 Node.js 24。Windows 使用：

```powershell
.\dev.ps1 start
.\dev.ps1 status
.\dev.ps1 stop
```

脚本默认使用 nvm 目录 `D:/programs/scoop/persist/nvm/nodejs/nodejs`，不修改全局 nvm 版本。可用 `-NodeDirectory` 和 `-JavaDirectory` 指定环境。首次启动执行构建与检查；已验证的产物可用 `-SkipBuild`。前端地址为 `http://localhost:5173/`，API 为 `http://localhost:8080/`。

Linux/macOS 在两个终端分别运行：

```bash
./build.sh run
```

```bash
cd web
npm ci
npm run dev
```

## 构建与检查

```bash
./gradlew test bootJar
cd web
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

`./build.sh check` 执行全部检查。API 可单独通过 `java -jar build/backend/libs/reader.jar` 运行，网页无需复制到 JVM 资源目录。

## 代理与跨域

Vite 的 API 目标默认为 `http://127.0.0.1:8080`，可通过环境变量 `READER_API_PROXY` 调整。前端可通过 `VITE_API_BASE_URL` 指定 API 地址；它是构建变量，变更后需要重建。

生产推荐同源部署：Nginx 托管前端，将 `/reader3/`、`/assets/`、`/epub/`、`/health` 转发给 API。`web/nginx.conf` 配置上传限制，并关闭 SSE 响应缓冲。WebDAV、本地 EPUB、封面与导出仍由 API 提供。

若直接跨域访问 API，将实际前端 Origin 加入配置：

```yaml
reader:
  app:
    storagePath: storage
    secure: false
    secureKey: ""
    inviteCode: ""
    cacheChapterContent: true
    userLimit: 50
    userBookLimit: 200
  server:
    port: 8080
    contextPath: ""
    cors:
      allowedOrigins:
        - http://localhost:5173
        - http://127.0.0.1:5173
        - http://localhost:4173
        - http://127.0.0.1:4173
      allowCredentials: true
```

所有常规接口继续返回 `{isSuccess, errorMsg, data}`，路径仍为 `/reader3`。原书源和书架 JSON 无需手动转换。

## Docker 部署

```bash
docker compose up -d --build
```

访问 `http://localhost:4396/`。Compose 分别运行 `reader` API 和 `web`；API 端口仅暴露给容器网络。数据和日志保存于本地 `storage/`、`logs/`。

通过环境变量配置 `READER_WEB_PORT`、`READER_APP_SECURE`、`READER_APP_SECUREKEY`、`READER_APP_INVITECODE`。`docker-compose.yml` 是主配置；`docker-compose.yaml` 是 Compose 2.20+ 的兼容 include 入口。

独立构建镜像：

```bash
docker build -f Dockerfile.source -t reader-api .
docker build -f web/Dockerfile -t reader-web web
# 可选 API 运行时
docker build -f Dockerfile.slim -t reader-api:slim .
docker build -f Dockerfile.openj9 -t reader-api:openj9 .
```

旧单体镜像不能提供新 React 首页，需同时部署 API 与 Web。已有数据目录挂载至 API `/app/storage`，日志挂载至 `/app/logs`；切换时先停旧进程，避免两个服务同时写入同一目录。

CI 分别验证 API 和 React；发布工作流分别打包 JAR 与网页归档，并向当前仓库对应的 GHCR API/Web 仓库发布独立镜像。

## WebDAV

地址为 `http://前端域名/reader3/webdav/`。多用户模式使用网页注册的用户名和密码；单用户模式使用 `default` / `123456`。备份、恢复和阅读进度仍由后端处理。

## 书源迁移

本轮优先补齐 Cookie、登录缓存、`jsLib`、单文件 JavaScript 书源、导入验证、登录表单和实时调试。现有书架、阅读、搜索、发现、RSS、替换规则、TXT/EPUB/UMD/CBZ 导入、WebDAV 与用户管理继续使用现有 API。

[书源迁移](docs/book-source-migration.md)记录协议及兼容边界；Android WebView、QuickJS 和设备能力需要独立适配。完整 Android 功能对齐不作为本轮已完成声明。

## 数据存储

接口服务使用文件存储书源及目录等信息，默认存储位置为工作目录下的 `storage` 目录。可通过 `java -Dreader.app.storagePath=/path/to/storage -jar reader.jar` 指定存储路径，也可以在配置文件中设置 `reader.app.storagePath`。

> macOS 保留原有存储路径兼容规则：工作目录不以 `/Users/` 开头时，默认使用 `~/.reader/storage`。绝对存储路径不受此规则影响。

数据存储目录结构如下：

```bash
storage
├── assets                                        # 静态资源
│   ├── hector                                    # 用户 hector 的资源目录
│   |   |── covers                                # 本地 epub 书籍的封面图片目录
│   │   ├── background                            # 自定义阅读背景图片保存目录
│   │   │   └── 6.jpg
│   └── reader.css                                # 自定义CSS样式文件
├── cache                                         # 缓存目录
│   ├── 6190ac40068e74c2c82624e91a5f8a0c.jpg      # 书籍封面缓存
│   ├── bookInfoCache                             # 书籍搜索缓存 ACache 目录
│   └── ea11967236129bdae6133c3c9ff8c2dd.jpg
├── data                                          # 数据目录
│   ├── default                                   # 系统默认用户的数据目录 (reader.app.secure为false时)
│   │   ├── bookSource.json                       # 书源列表
│   │   ├── bookshelf.json                        # 书架书籍列表
│   │   ├── 斗罗大陆_唐家三少                        # 书籍缓存目录
│   │   │   ├── 5d01bc88d6b19ebbe974acaac1675811         # A书源章节缓存目录
│   │   │   ├── 5d01bc88d6b19ebbe974acaac1675811.json    # A书源目录列表
│   │   │   ├── 7e5ca1cc2a1ea2e09fdec4ee2e150f02         # B书源章节缓存目录
│   │   │   ├── 7e5ca1cc2a1ea2e09fdec4ee2e150f02.json    # B书源目录列表
│   │   │   └── bookSource.json                          # 书籍书源列表
│   ├── hector                                    # 用户 hector 的数据目录 (reader.app.secure为true时的用户目录)
│   │   ├── bookSource.json                       # 书源列表
│   │   ├── bookshelf.json                        # 书架书籍列表
│   │   ├── webdav                                # webdav 存储目录 可能会存在 legado 子目录
│   │   │   ├── backup2021-09-15.zip              # 阅读3备份文件
│   │   │   └── bookProgress                      # 阅读3书籍进度备份目录
│   │   │       └── 斗罗大陆_唐家三少.json           # 阅读3书籍进度
│   │   └── 斗罗大陆_唐家三少                        # 书籍缓存目录
│   │       |── 2d44d0ec2397b6c1d4010b97d914031e       # A书源章节缓存目录
│   │       └── 2d44d0ec2397b6c1d4010b97d914031e.json  # A书源目录列表
│   └── users.json                                # 用户列表
└── localStore                                    # 本地书仓，所有用户共享(用户需要开启书仓权限，才能访问)
    |── 斗破苍穹.txt                               # 本地书仓书籍
    └── 斗罗大陆.txt                               # 本地书仓书籍
```

### 本地书仓

在 `storage/localStore` 中可以集中存放管理本地书籍，开启访问权限的用户可以在 `页面-浏览书仓` 中选择批量导入到自己的书架进行阅读。

## 免责声明（Disclaimer）

阅读是一款提供网络文学搜索的工具，为广大网络文学爱好者提供一种方便、快捷舒适的试读体验。

当您搜索一本书的时，阅读会将该书的书名以关键词的形式提交到各个第三方网络文学网站。各第三方网站返回的内容与阅读无关，阅读对其概不负责，亦不承担任何法律责任。任何通过使用阅读而链接到的第三方网页均系他人制作或提供，您可能从第三方网页上获得其他服务，阅读对其合法性概不负责，亦不承担任何法律责任。第三方搜索引擎结果根据您提交的书名自动搜索获得并提供试读，不代表阅读赞成或被搜索链接到的第三方网页上的内容或立场。您应该对使用搜索引擎的结果自行承担风险。

阅读不做任何形式的保证：不保证第三方搜索引擎的搜索结果满足您的要求，不保证搜索服务不中断，不保证搜索结果的安全性、正确性、及时性、合法性。因网络状况、通讯线路、第三方网站等任何原因而导致您不能正常使用阅读，阅读不承担任何法律责任。阅读尊重并保护所有使用阅读用户的个人隐私权，您注册的用户名、电子邮件地址等个人资料，非经您亲自许可或根据相关法律、法规的强制性规定，阅读不会主动地泄露给第三方。

阅读致力于最大程度地减少网络文学阅读者在自行搜寻过程中的无意义的时间浪费，通过专业搜索展示不同网站中网络文学的最新章节。阅读在为广大小说爱好者提供方便、快捷舒适的试读体验的同时，也使优秀网络文学得以迅速、更广泛的传播，从而达到了在一定程度促进网络文学充分繁荣发展之目的。阅读鼓励广大小说爱好者通过阅读发现优秀网络小说及其提供商，并建议阅读正版图书。任何单位或个人认为通过阅读搜索链接到的第三方网页内容可能涉嫌侵犯其信息网络传播权，应该及时向阅读提出书面权力通知，并提供身份证明、权属证明及详细侵权情况证明。阅读在收到上述法律文件后，将会依法尽快断开相关链接内容。

## 接口文档

与 [阅读3Web接口](https://github.com/gedoor/legado/blob/master/api.md) 基本一致，只是多了接口前缀 `/reader3/`

### 新增接口

#### 加入书架

- URL `http://localhost:8080/reader3/saveBook`
- Method `POST`
- Body `json 格式`

```JSON
{
    "infoHtml": "",
    "tocHtml": "",
    "bookUrl": "https://www.damixs.com/book/dmfz.html",
    "origin": "https://www.damixs.com",
    "originName": "🎉大米小说",
    "type": 0,
    "name": "道门法则",
    "author": "八宝饭",
    "kind": "02-14",
    "intro": "在道门掌控的天下，应该怎么修炼？符箓、丹药、道士、灵妖、斋醮科仪......想要修仙，很好，请从扫厕所开始做起！符诏到来的时候，你需要站在什么位置？Q群：1701556（需验证订阅截图）、954782460“盟主群”",
    "wordCount": "",
    "latestChapterTitle": "番外四（贺消脱止-M荣升盟主）",
    "tocUrl": "",
    "time": 1628756214810,
    "originOrder": 16
}
```

- Response Body

[Book字段参考](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/Book.kt)

```JSON
{
    "isSuccess": true,
    "errorMsg": "",
    "data": Book
}
```

#### 获取书籍书源

- URL `http://localhost:8080/reader3/getBookSource?url=xxx`
- Method `GET`

获取指定URL对应的书源信息, 和 `阅读3Web接口` 的 `getSource` 接口相同

- Response Body

[SearchBook字段参考](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/SearchBook.kt)

```JSON
{
    "isSuccess": true,
    "errorMsg": "",
    "data": [SearchBook]
}
```

#### 搜索书籍更多书源

- URL `http://localhost:8080/reader3/searchBookSource?name=xxx&lastIndex=0`
- Method `GET`

搜索指定name对应的书源列表信息

lastIndex 是上次搜索结果中返回的字段，默认为 0，可以传入 `getBookSource` 接口返回的SearchBook列表长度

- Response Body

[SearchBook字段参考](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/SearchBook.kt)

```JSON
{
    "isSuccess": true,
    "errorMsg": "",
    "data": [SearchBook]
}
```

#### 书籍换源

- URL `http://localhost:8080/reader3/setBookSource`
- Method `POST`
- Body `json 格式`

```JSON
{
    "newUrl": "新源书籍链接",
    "name": "书籍名称",
    "bookSourceUrl": "书源链接"
}
```

- Response Body

[Book字段参考](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/data/entities/Book.kt)

```JSON
{
    "isSuccess": true,
    "errorMsg": "",
    "data": Book
}
```
