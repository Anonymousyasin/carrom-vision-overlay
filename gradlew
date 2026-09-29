#!/bin/sh
# Minimal Gradle wrapper launcher (unix). Matches official gradlew behavior:
# java -cp wrapper.jar org.gradle.wrapper.GradleWrapperMain (not java -jar)
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
