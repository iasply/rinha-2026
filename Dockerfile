# ── Stage 1: Build nativo ─────────────────────────────────────────────────────
FROM vegardit/graalvm-maven:latest-java24 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:resolve -q
COPY src ./src
RUN MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" mvn clean package -Pnative -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM debian:bookworm-slim
WORKDIR /app
COPY --from=builder /app/target/rinha-api .
COPY fraud-index ./fraud-index
EXPOSE 8080
ENTRYPOINT ["./rinha-api"]
