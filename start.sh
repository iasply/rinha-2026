#!/bin/sh
exec java \
  --add-modules jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  ${JAVA_OPTS} \
  -jar hello.jar
