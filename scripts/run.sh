#!/usr/bin/env bash
# Build with plain javac (no Maven needed) and run Faultline.
#   scripts/run.sh run --quick
#   FAULTLINE_JDBC_JAR=~/postgresql-42.7.4.jar FAULTLINE_DB_URL=jdbc:postgresql://localhost/faultline scripts/run.sh run
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
javac -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/
CP="build/classes${FAULTLINE_JDBC_JAR:+:$FAULTLINE_JDBC_JAR}"
exec java -cp "$CP" io.faultline.Main "$@"
