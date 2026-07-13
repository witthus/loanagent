#!/bin/sh
set -eu
cd /workspace/ops-web
if [ ! -x node_modules/.bin/vite ]; then
  npm ci
fi
exec "$@"
