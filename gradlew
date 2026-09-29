#!/bin/sh
# Minimal Gradle wrapper launcher (unix). Works with setup-java on GitHub Actions.
# Delegates to gradle/wrapper/gradle-wrapper.jar
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
