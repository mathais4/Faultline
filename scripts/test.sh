#!/usr/bin/env bash
# Run the JUnit 4 tests without Maven. Needs junit-4.13.2.jar and hamcrest-core-1.3.jar (JUNIT_DIR, default ~/.m2-free).
set -euo pipefail
cd "$(dirname "$0")/.."
JUNIT_DIR="${JUNIT_DIR:-/opt/gradle-8.14.3/lib}"
CP="build/classes:build/test-classes:$JUNIT_DIR/junit-4.13.2.jar:$JUNIT_DIR/hamcrest-core-1.3.jar"
mkdir -p build/classes build/test-classes
javac -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/
javac -cp "$CP" -d build/test-classes $(find src/test/java -name '*.java')
java -cp "$CP" org.junit.runner.JUnitCore $(cd src/test/java && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g')
