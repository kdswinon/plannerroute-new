#!/usr/bin/env sh
set -eu
# Shared workspace scanner needs group traversal/read on synthetic test artifacts.
umask 027
# Run from the repository root. Uses only project-local temporary files.
PR_JAVA_HOME="${PR_JAVA_HOME:-$PWD/.tooling/jdk-17.0.20.1+1}"
PR_ANDROID_JAR="${PR_ANDROID_JAR:-$PWD/.tooling/android-sdk/platforms/android-35/android.jar}"
PR_JSON_JAR="${PR_JSON_JAR:-$PWD/.tooling/json-tests.jar}"
if [ ! -f "$PR_JSON_JAR" ]; then
    mkdir -p .tooling
    curl -fsSL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$PR_JSON_JAR"
fi
mkdir -p .tooling/check-classes
"$PR_JAVA_HOME/bin/javac" -cp "$PR_JSON_JAR:$PR_ANDROID_JAR" -d .tooling/check-classes app/src/main/java/com/plannerroute/app/Data.java app/src/main/java/com/plannerroute/app/Routing.java tests/CoreChecks.java
"$PR_JAVA_HOME/bin/java" -cp ".tooling/check-classes:$PR_JSON_JAR:$PR_ANDROID_JAR" com.plannerroute.app.CoreChecks .tooling/test-runtime "$@"
