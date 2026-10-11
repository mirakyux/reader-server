#!/usr/bin/env bash
# Copyright 1999-2018 Alibaba Group Holding Ltd.; Apache License 2.0.
set -euo pipefail
server_directory=$(cd "$(dirname "$0")/.." && pwd)
pid_file="$server_directory/logs/reader.pid"
if [[ ! -f "$pid_file" ]]; then echo 'No recorded API process.'; exit 0; fi
reader_pid=$(cat "$pid_file")
if [[ ! "$reader_pid" =~ ^[0-9]+$ ]]; then echo 'Invalid PID file.' >&2; exit 1; fi
command_line=$(ps -p "$reader_pid" -o args= || true)
if [[ "$command_line" == *"$server_directory/target/"* && "$command_line" == *"java"* ]]; then
    kill "$reader_pid"
    rm "$pid_file"
    echo "Sent shutdown to API PID=$reader_pid"
elif [[ -z "$command_line" ]]; then
    rm "$pid_file"
    echo 'API already stopped.'
else
    echo 'PID belongs to another process; left it running.' >&2
    exit 1
fi
