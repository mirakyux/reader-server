#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

task=${1:-help}
if [[ $# -gt 0 ]]; then shift; fi
case "$task" in
    build|backend)
        exec ./gradlew bootJar "$@"
        ;;
    run|serve)
        port=8080
        if [[ $# -gt 0 && "$1" != -* ]]; then port=$1; shift; fi
        ./gradlew bootJar
        exec java -jar build/backend/libs/reader.jar "--reader.server.port=$port" "$@"
        ;;
    cli)
        exec ./gradlew "$@"
        ;;
    npm)
        cd web
        exec npm "$@"
        ;;
    web)
        cd web
        exec npm run dev -- "$@"
        ;;
    frontend)
        cd web
        npm ci
        exec npm run build -- "$@"
        ;;
    browser)
        cd browser
        npm ci
        exec npm start -- "$@"
        ;;
    check)
        ./gradlew test bootJar
        cd web
        npm ci
        npm run lint
        npm run typecheck
        npm test
        npm run build
        cd ../browser
        npm ci
        npm test
        ;;
    *)
        cat <<'USAGE'
USAGE: ./build.sh build|run|serve|cli|npm|web|frontend|browser|check

build     构建独立 API JAR（Java 21）
run       构建并运行 API，可指定端口（默认 8080）
cli       透传 Gradle 参数
npm       在 web 目录运行 npm
web       运行 React 开发服务（默认 5173）
frontend  安装锁定依赖并构建独立前端
browser   安装并启动浏览器服务，需设置 READER_BROWSER_TOKEN
check     后端、前端和浏览器服务检查

前端不再复制到 JAR；分别运行 run 和 web，或使用 docker compose。
USAGE
        ;;
esac
