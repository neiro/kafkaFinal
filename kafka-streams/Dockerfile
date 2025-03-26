FROM maven:3.8.5-openjdk-11 AS build
WORKDIR /build
COPY src ./src
COPY pom.xml .
RUN mvn package

FROM openjdk:11-jre-slim
WORKDIR /app
COPY --from=build /build/target/kafka-streams-app-1.0.0.jar /app/app.jar
# COPY --from=build /build/target/shop-api-1.0.0.jar /app/shop-api.jar
COPY config/banned.txt /app/config/banned.txt
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
