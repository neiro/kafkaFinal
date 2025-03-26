# Stage 1: Build the Shop API producer application
FROM maven:3.8.5-openjdk-11 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package

# Stage 2: Create a lightweight image to run the app
FROM openjdk:11-jre-slim
WORKDIR /app
# Copy the built jar from the Maven image
COPY --from=build /build/target/shop-api-1.0.0.jar /app/shop-api.jar
COPY data/products.json /app/data/products.json
# Run the ShopProducer when the container starts
ENTRYPOINT ["java", "-jar", "/app/shop-api.jar"]
