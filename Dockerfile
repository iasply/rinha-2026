FROM maven:3.9-eclipse-temurin-25-alpine AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:resolve -q
COPY src ./src
RUN mvn clean package -DskipTests -q

FROM ghcr.io/graalvm/jdk-community:25 AS runtime-builder

RUN jlink \
    --add-modules java.base,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.xml,jdk.crypto.ec,jdk.incubator.vector,jdk.unsupported,jdk.graal.compiler \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /graal-jre

FROM debian:bookworm-slim

COPY --from=runtime-builder /graal-jre /graal-jre
ENV JAVA_HOME=/graal-jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app
COPY --from=builder /app/target/hello.jar .
COPY fraud-index ./fraud-index
COPY start.sh .
RUN sed -i 's/\r//' start.sh && chmod +x start.sh

EXPOSE 8080
ENTRYPOINT ["./start.sh"]
