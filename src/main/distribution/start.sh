#!/bin/sh
set -eu
cd "$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
JAVA=java
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; fi
DEFAULT_JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication -XX:MaxDirectMemorySize=96m -Djava.awt.headless=true -Dio.netty.allocator.maxOrder=9 -Dio.netty.allocator.numDirectArenas=2 -Dio.netty.allocator.numHeapArenas=2"
# shellcheck disable=SC2086
exec "$JAVA" ${JAVA_OPTS:-$DEFAULT_JAVA_OPTS} -Dfile.encoding=UTF-8 -cp 'WEB-INF/classes:WEB-INF/lib/*:.' org.b3log.solo.Starter "$@"
