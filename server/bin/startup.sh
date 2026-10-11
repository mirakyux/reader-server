#!/usr/bin/env bash
# Copyright 1999-2018 Alibaba Group Holding Ltd.; Apache License 2.0.
set -euo pipefail
server_directory=$(cd "$(dirname "$0")/.." && pwd)
java_executable=${JAVA_HOME:+$JAVA_HOME/bin/}java
java_version=$("$java_executable" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)
if [[ -z "$java_version" || "$java_version" -lt 21 ]]; then
    echo '需要 Java 21 或以上运行时。' >&2
    exit 1
fi
jar_name=reader
mode=''
invite_code=''
secure_key=''
while getopts ':m:s:i:k:' option; do
    case "$option" in
        m) mode=$OPTARG ;;
        s) jar_name=$OPTARG ;;
        i) invite_code=$OPTARG ;;
        k) secure_key=$OPTARG ;;
        *) echo '参数：-m single|multi -s JAR名称 -i 邀请码 -k 管理密码' >&2; exit 1 ;;
    esac
done
if [[ "$jar_name" == */* || "$jar_name" == *\\* ]]; then echo 'JAR 名称不能包含路径。' >&2; exit 1; fi
jar_file="$server_directory/target/$jar_name.jar"
if [[ ! -f "$jar_file" ]]; then echo "找不到 $jar_file，请复制构建的 API JAR。" >&2; exit 1; fi
mkdir -p "$server_directory/logs"
pid_file="$server_directory/logs/reader.pid"
if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then echo '已有记录的服务进程，先执行 shutdown.sh。' >&2; exit 1; fi
java_args=("-Dfile.encoding=UTF-8" "-Dreader.app.workDir=$server_directory" -jar "$jar_file" "--spring.config.additional-location=optional:file:$server_directory/conf/")
if [[ "$mode" == single ]]; then java_args+=(--reader.app.secure=false); elif [[ -n "$mode" ]]; then java_args+=(--reader.app.secure=true); fi
if [[ -n "$invite_code" ]]; then java_args+=("--reader.app.inviteCode=$invite_code"); fi
if [[ -n "$secure_key" ]]; then java_args+=("--reader.app.secureKey=$secure_key"); fi
cd "$server_directory"
nohup "$java_executable" "${java_args[@]}" >> "$server_directory/logs/start.out" 2>&1 &
echo "$!" > "$pid_file"
echo "API 正在启动：日志 $server_directory/logs/start.out；React 前端须独立部署。"
