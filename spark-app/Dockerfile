FROM maven:3.8.5-openjdk-11 AS build
WORKDIR /build
COPY src ./src
COPY pom.xml .
RUN mvn package

FROM bitnami/spark:3.3.2
USER root
WORKDIR /app
COPY --from=build /build/target/spark-app-1.0.0.jar /app/spark-app.jar
# ENTRYPOINT ["/opt/bitnami/spark/bin/spark-submit", "--class", "com.marketplace.spark.AnalyticsApp", "--master", "local[*]", "--deploy-mode", "client", "--packages", "org.apache.spark:spark-sql-kafka-0-10_2.12:3.3.2", "/app/spark-app.jar"]
ENTRYPOINT ["/opt/bitnami/spark/bin/spark-submit", "--class", "com.marketplace.spark.AnalyticsApp", "--master", "local[*]", "--deploy-mode", "client", "--conf", "spark.kafka.bootstrap.servers=kafka1:9093,kafka2:9093,kafka3:9093", "--conf", "spark.kafka.security.protocol=SASL_SSL", "--conf", "spark.kafka.sasl.mechanism=PLAIN", "--conf", "spark.kafka.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\"admin-secret\";", "--conf", "spark.kafka.ssl.truststore.location=/certs/kafka1.truststore.jks", "--conf", "spark.kafka.ssl.truststore.password=changeit", "--conf", "spark.kafka.ssl.keystore.location=/certs/kafka1.keystore.jks", "--conf", "spark.kafka.ssl.keystore.password=changeit", "--conf", "spark.kafka.ssl.key.password=changeit", "--packages", "org.apache.spark:spark-sql-kafka-0-10_2.12:3.3.2", "/app/spark-app.jar"]
