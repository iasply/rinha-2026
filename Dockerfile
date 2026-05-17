FROM maven:3.9-eclipse-temurin-25-alpine AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:resolve -q
COPY src ./src
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:25-jre-alpine

WORKDIR /app
COPY --from=builder /app/target/hello.jar .
COPY fraud-index ./fraud-index
COPY start.sh .
RUN sed -i 's/\r//' start.sh && chmod +x start.sh

EXPOSE 8080
ENTRYPOINT ["./start.sh"]
