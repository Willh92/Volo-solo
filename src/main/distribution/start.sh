#!/bin/sh
set -eu
cd "$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; fi
exec "$JAVA" -Dfile.encoding=UTF-8 -cp 'WEB-INF/classes:WEB-INF/lib/*:.' org.b3log.solo.Starter "$@"
