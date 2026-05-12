#!/bin/sh
exec java \
  --add-modules jdk.incubator.vector \
  ${JAVA_OPTS} \
  -jar hello.jar
