# 192.168.31.107 部署记录

2026-10-09 部署至 HOUZZkit 内网服务器。访问地址：<http://192.168.31.107:4396/>。按全新实例部署，验收结束后书架和书源均为 0。

## 部署结构

服务器使用 Ubuntu 24.04.2、ARM64、Docker 26.1.3。安装官方 Docker Compose v5.6.0，安装前核验官方 SHA-256。Compose 插件位于 `/root/.docker/cli-plugins/docker-compose`，通过 `sudo -i` 使用。

部署目录为 `/opt/reader-dev`，Compose 项目名称为 `reader-dev`。API 和 React 网页在本机完成构建，服务器只构建运行时镜像，降低构建时的内存占用。

| 服务 | 镜像 | 访问方式 |
| --- | --- | --- |
| API | `reader-dev-api:20261009` | 容器网络 8080 |
| Chromium / QuickJS | `reader-dev-browser:20261009` | 容器网络 8090 |
| React / Nginx | `reader-dev-web:20261009` | 主机 4396 |

三个服务均配置 `restart: unless-stopped`。服务器已有 Home Assistant 和 Tailscale 继续运行。Nginx 统一代理 API，内网 Origin 精确设置为 `http://192.168.31.107:4396`。浏览器服务使用随机内部令牌，令牌仅保存在服务器权限为 600 的 `.env` 中。

数据目录 `/opt/reader-dev/storage` 挂载到 API 的 `/app/storage`；日志目录 `/opt/reader-dev/logs` 挂载到 `/app/logs`。容器标准输出日志每份最多 10 MB，保留 3 份。

## 维护命令

```bash
ssh houzzkit@192.168.31.107
sudo -i
cd /opt/reader-dev

reader_compose() {
  docker compose -p reader-dev -f docker-compose.yml -f docker-compose.artifacts.yml "$@"
}

reader_compose ps
reader_compose logs --tail=100
reader_compose restart
curl --fail http://127.0.0.1:4396/health
```

健康接口正常返回 `"ok!"`。启动已有镜像使用 `reader_compose up -d --no-build --pull never`。本实例的 `docker-compose.artifacts.yml` 指定预构建 JAR 和网页产物；维护时继续同时加载两个 Compose 文件。

更新需要先在本机构建、检查并上传新的 JAR、`web/dist` 和相关配置，保留服务器 `.env`、`storage` 与 `logs`。然后在服务器运行 `bash deploy-server.sh`。脚本使用现有镜像作为基础构建，启动三个服务并等待健康接口；构建日志见 `tools/deploy.log`。

备份时保留 `storage` 和 `.env`。镜像或容器可以重新创建，数据目录无需删除。发布文件的哈希清单位于 `/opt/reader-dev/SHA256SUMS`，运行 `sha256sum --check SHA256SUMS` 可校验上传产物。

## 修复与验证

本次部署移除生产配置文件内无效的 `spring.profiles.active` 声明，改由 Compose 激活生产配置；修正 Nginx 的 Host 端口转发并配置完整内网 Origin；为 HTTP 下不可用的 `crypto.randomUUID` 增加 Web Crypto UUIDv4 兼容实现。

验证结果：

- 后端 235 项测试通过，生产配置回归和 JAR 构建通过。
- 前端 76 项单元测试、74 项浏览器测试、ESLint、TypeScript 和生产构建通过。
- 真实内网浏览器完成临时书源搜索、加入书架、读取三章正文、QuickJS 调用、书架右键缓存全章后下载 TXT 和 EPUB。验收不使用接口模拟。
- TXT 三章正文完整；EPUB 的三章正文、章节图片和 JPEG 封面完整。EPUBCheck 5.4.0 返回 0 错误、0 警告。
- Chromium DOM 执行和 QuickJS 现代语法通过，未授权桥接请求返回 401，结束后会话和运行中的 QuickJS 工作线程均为 0。
- 1440、390、320 像素页面无横向溢出，顶部导航及更多菜单可操作，无浏览器脚本错误。
- 重启三个容器后，临时书源仍然存在，证明数据持久化；随后删除临时数据，书架和书源恢复为空。

本机验证证据保存于 `logs/lan-deployment-*.log`、`logs/lan-deployment-*.json`，截图位于 `web/screenshots/`。测试书籍导出保存在本机日志目录，服务器书架没有保留验收书。

## 当前运行限制

主机内核不支持 Docker 内存和交换区限额，`mem_limit` 无法实际生效。API 另设 Java 堆上限 512 MB、直接内存上限 96 MB；Chromium 的内存使用仍需由主机资源约束。

当前地址使用内网 HTTP，在线阅读、书源和导出已经验证。浏览器 Service Worker 的离线冷启动需要 HTTPS 安全来源，当前地址不具备该能力。
