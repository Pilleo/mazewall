#!/bin/bash
echo "Gradle Java version:"
./gradlew -q --version | grep JVM
echo "JAVA_HOME is $JAVA_HOME"
