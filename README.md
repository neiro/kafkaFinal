# Project Structure

```
analytics-platform/
├── docker-compose.yml
├── README.md
├── shop-api/
│   ├── Dockerfile
│   ├── data/
│   │   └── products.json
│   └── src/ShopProducer.java
├── client-api/
│   ├── Dockerfile
│   └── client.sh
├── kafka/
│   ├── config/
│   │   ├── server.properties
│   │   ├── kafka_server_jaas.conf
│   │   ├── mm-source.properties
│   │   └── mm-target.properties
│   ├── certs/
│   │   ├── generate-certs.sh
│   │   ├── ca.crt
│   │   ├── kafka1.keystore.jks
│   │   ├── kafka1.truststore.jks
│   │   ├── kafka2.keystore.jks
│   │   ├── kafka2.truststore.jks
│   │   ├── kafka3.keystore.jks
│   │   └── kafka3.truststore.jks
│   └── Dockerfile
├── kafka-streams/
│   ├── Dockerfile
│   └── src/FilterApp.java
├── kafka-connect/
│   ├── config/elastic-sink.json
│   └── Dockerfile
├── spark-app/
│   ├── Dockerfile
│   └── src/AnalyticsApp.java
├── prometheus/
│   ├── prometheus.yml
│   ├── alerts.yml
│   └── kafka_jmx.yml
├── grafana/
│   └── provisioning/
│       └── datasources/prometheus.yml
└── alertmanager/
    └── alertmanager.yml
```

## docker-compose.yml
```yaml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.4.0
    container_name: zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000

  kafka1: &kafka_broker
    build: ./kafka
    container_name: kafka1
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: SASL_SSL:SASL_SSL
      KAFKA_LISTENERS: SASL_SSL://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: SASL_SSL://kafka1:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: SASL_SSL
      KAFKA_SSL_KEYSTORE_LOCATION: /certs/kafka1.keystore.jks
      KAFKA_SSL_KEYSTORE_PASSWORD: password
      KAFKA_SSL_KEY_PASSWORD: password
      KAFKA_SSL_TRUSTSTORE_LOCATION: /certs/kafka1.truststore.jks
      KAFKA_SSL_TRUSTSTORE_PASSWORD: password
      KAFKA_SASL_ENABLED_MECHANISMS: PLAIN
      KAFKA_SASL_MECHANISM_INTER_BROKER_PROTOCOL: PLAIN
      # Kafka broker JAAS config for SASL/PLAIN with two users: admin and client
      KAFKA_OPTS: -Djava.security.auth.login.config=/etc/kafka/kafka_server_jaas.conf -javaagent:/usr/share/jmx_exporter/jmx_prometheus_javaagent.jar=7071:/etc/kafka/jmx-kafka.yml
      KAFKA_AUTHORIZER_CLASS_NAME: kafka.security.auth.SimpleAclAuthorizer
      KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND: "true"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3
      KAFKA_DEFAULT_REPLICATION_FACTOR: 3
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2
    volumes:
      - ./kafka/config/kafka_server_jaas.conf:/etc/kafka/kafka_server_jaas.conf
      - ./kafka/certs:/certs:ro
      - ./prometheus/kafka_jmx.yml:/etc/kafka/jmx-kafka.yml:ro
      - ./prometheus/jmx_prometheus_javaagent.jar:/usr/share/jmx_exporter/jmx_prometheus_javaagent.jar:ro
    depends_on:
      - zookeeper

  kafka2:
    <<: *kafka_broker
    container_name: kafka2
    environment:
      KAFKA_BROKER_ID: 2
      KAFKA_ADVERTISED_LISTENERS: SASL_SSL://kafka2:9093
      KAFKA_SSL_KEYSTORE_LOCATION: /certs/kafka2.keystore.jks
      KAFKA_SSL_TRUSTSTORE_LOCATION: /certs/kafka2.truststore.jks

  kafka3:
    <<: *kafka_broker
    container_name: kafka3
    environment:
      KAFKA_BROKER_ID: 3
      KAFKA_ADVERTISED_LISTENERS: SASL_SSL://kafka3:9093
      KAFKA_SSL_KEYSTORE_LOCATION: /certs/kafka3.keystore.jks
      KAFKA_SSL_TRUSTSTORE_LOCATION: /certs/kafka3.truststore.jks

  kafka-connect:
    build: ./kafka-connect
    container_name: kafka-connect
    environment:
      CONNECT_BOOTSTRAP_SERVERS: kafka1:9093,kafka2:9093,kafka3:9093
      CONNECT_GROUP_ID: "connect-cluster"
      CONNECT_CONFIG_STORAGE_TOPIC: connect-configs
      CONNECT_OFFSET_STORAGE_TOPIC: connect-offsets
      CONNECT_STATUS_STORAGE_TOPIC: connect-statuses
      CONNECT_SECURITY_PROTOCOL: SASL_SSL
      CONNECT_SASL_MECHANISM: PLAIN
      CONNECT_SASL_JAAS_CONFIG: org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin-secret";
      CONNECT_SSL_TRUSTSTORE_LOCATION: /certs/kafka1.truststore.jks
      CONNECT_SSL_TRUSTSTORE_PASSWORD: password
      CONNECT_SSL_KEYSTORE_LOCATION: /certs/kafka1.keystore.jks
      CONNECT_SSL_KEYSTORE_PASSWORD: password
      CONNECT_KEY_CONVERTER: org.apache.kafka.connect.storage.StringConverter
      CONNECT_VALUE_CONVERTER: org.apache.kafka.connect.storage.StringConverter
      CONNECT_REST_ADVERTISED_HOST_NAME: kafka-connect
      CONNECT_PLUGIN_PATH: /usr/share/java
    volumes:
      - ./kafka/certs:/certs:ro
      - ./kafka-connect/config/elastic-sink.json:/etc/kafka-connect/elastic-sink.json:ro
    depends_on:
      - kafka1
      - kafka2
      - kafka3

  mirror-maker:
    image: confluentinc/cp-kafka:7.4.0
    container_name: kafka-mirror-maker
    depends_on:
      - kafka1
      - kafka2
      - kafka3
    volumes:
      - ./kafka/config/mm-source.properties:/etc/kafka/mm-source.properties:ro
      - ./kafka/config/mm-target.properties:/etc/kafka/mm-target.properties:ro
    command: >
      bash -c "kafka-mirror-maker --consumer.config /etc/kafka/mm-source.properties 
              --producer.config /etc/kafka/mm-target.properties 
              --whitelist 'products,orders'"

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:7.17.10
    container_name: elasticsearch
    environment:
      - xpack.security.enabled=false
      - discovery.type=single-node
      - bootstrap.memory_lock=false
      - ES_JAVA_OPTS=-Xms512m -Xmx512m
    ports:
      - "9200:9200"

  shop-api:
    build: ./shop-api
    container_name: shop-api
    depends_on:
      - kafka1
    environment:
      BOOTSTRAP_SERVERS: kafka1:9093,kafka2:9093,kafka3:9093
      SECURITY_PROTOCOL: SASL_SSL
      SASL_MECHANISM: PLAIN
      SASL_JAAS_CONFIG: org.apache.kafka.common.security.plain.PlainLoginModule required username="client" password="client-secret";
      SSL_TRUSTSTORE: /certs/kafka1.truststore.jks
      SSL_TRUSTSTORE_PASSWORD: password
      TOPIC: products_raw
      DATA_FILE: /app/data/products.json
    volumes:
      - ./kafka/certs:/certs:ro
      - ./shop-api/data/products.json:/app/data/products.json:ro

  client-api:
    build: ./client-api
    container_name: client-api
    depends_on:
      - kafka1
      - elasticsearch
    volumes:
      - ./kafka/certs/ca.crt:/certs/ca.crt:ro
    stdin_open: true
    tty: true

  kafka-streams:
    build: ./kafka-streams
    container_name: kafka-streams-app
    depends_on:
      - kafka1
    environment:
      BOOTSTRAP_SERVERS: kafka1:9093,kafka2:9093,kafka3:9093
      APPLICATION_ID: filter-app
      INPUT_TOPIC: products_raw
      OUTPUT_TOPIC: products
      BANNED_FILE: /app/config/banned.txt
      SECURITY_PROTOCOL: SASL_SSL
      SASL_MECHANISM: PLAIN
      SASL_JAAS_CONFIG: org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin-secret";
      SSL_TRUSTSTORE: /certs/kafka1.truststore.jks
      SSL_TRUSTSTORE_PASSWORD: password
    volumes:
      - ./kafka/certs:/certs:ro
      - ./kafka-streams/config/banned.txt:/app/config/banned.txt:ro

  spark-app:
    build: ./spark-app
    container_name: spark-app
    depends_on:
      - kafka1
      - kafka2
      - kafka3
      - namenode
    environment:
      BOOTSTRAP_SERVERS: kafka1:9093,kafka2:9093,kafka3:9093
      CHECKPOINT_DIR: hdfs://namenode:9000/spark-checkpoints
      SECURITY_PROTOCOL: SASL_SSL
      SASL_JAAS_CONFIG: org.apache.kafka.common.security.plain.PlainLoginModule required username="client" password="client-secret";
      SASL_MECHANISM: PLAIN
      TOPIC_IN: orders
      TOPIC_OUT: order_stats
    volumes:
      - ./kafka/certs:/certs:ro

  namenode:
    image: bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8
    container_name: namenode
    environment:
      - CLUSTER_NAME=test
      - CORE_CONF_fs_defaultFS=hdfs://namenode:9000
      - NAMENODE_CONF_dfs_namenode_name_dir=file:///hadoop/dfs/name
      - DFS_REPLICATION=1
    ports:
      - "9000:9000"
    volumes:
      - hadoop_namenode:/hadoop/dfs/name

  datanode:
    image: bde2020/hadoop-datanode:2.0.0-hadoop3.2.1-java8
    container_name: datanode
    environment:
      - CORE_CONF_fs_defaultFS=hdfs://namenode:9000
      - DATANODE_CONF_dfs_datanode_data_dir=file:///hadoop/dfs/data
      - DFS_REPLICATION=1
    depends_on:
      - namenode
    volumes:
      - hadoop_datanode:/hadoop/dfs/data

  prometheus:
    image: prom/prometheus:latest
    container_name: prometheus
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro
    depends_on:
      - kafka1
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:latest
    container_name: grafana
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
    ports:
      - "3000:3000"
    depends_on:
      - prometheus

  alertmanager:
    image: prom/alertmanager:latest
    container_name: alertmanager
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
    ports:
      - "9093:9093"
    depends_on:
      - prometheus

volumes:
  hadoop_namenode:
  hadoop_datanode:
```

## shop-api/Dockerfile
```Dockerfile
# Stage 1: Build the Shop API producer application
FROM maven:3.8.5-openjdk-11 AS build
WORKDIR /build
COPY src ./src
COPY pom.xml .
RUN mvn package

# Stage 2: Create a lightweight image to run the app
FROM openjdk:11-jre-slim
WORKDIR /app
# Copy the built jar from the Maven image
COPY --from=build /build/target/shop-api.jar /app/shop-api.jar
COPY data/products.json /app/data/products.json
# Run the ShopProducer when the container starts
ENTRYPOINT ["java", "-jar", "/app/shop-api.jar"]
```

## shop-api/data/products.json
```json
{"id": "p1001", "name": "Smartphone XYZ", "category": "Electronics", "price": 299.99, "description": "Новейший смартфон XYZ с отличной камерой"}
{"id": "p1002", "name": "Laptop Pro 15", "category": "Computers", "price": 899.00, "description": "Мощный ноутбук для работы и игр."}
{"id": "p1003", "name": "Wine Red 2015", "category": "Alcohol", "price": 19.99, "description": "Красное вино урожая 2015 года."}
```

## shop-api/src/ShopProducer.java
```java
package com.marketplace.shop;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Properties;

/**
 * ShopProducer reads products from a JSON file and sends them to a Kafka topic.
 */
public class ShopProducer {
    public static void main(String[] args) {
        String bootstrapServers = System.getenv("BOOTSTRAP_SERVERS");
        String topic = System.getenv("TOPIC");
        String dataFile = System.getenv("DATA_FILE");
        // Set up Kafka producer properties with SASL_SSL
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put("security.protocol", System.getenv("SECURITY_PROTOCOL"));
        props.put("sasl.mechanism", System.getenv("SASL_MECHANISM"));
        props.put("sasl.jaas.config", System.getenv("SASL_JAAS_CONFIG"));
        props.put("ssl.truststore.location", System.getenv("SSL_TRUSTSTORE"));
        props.put("ssl.truststore.password", System.getenv("SSL_TRUSTSTORE_PASSWORD"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        Producer<String, String> producer = new KafkaProducer<>(props);
        try (BufferedReader br = new BufferedReader(new FileReader(dataFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Each line is a JSON product record
                producer.send(new ProducerRecord<String, String>(topic, null, line));
            }
            System.out.println("All products sent to topic: " + topic);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            producer.close();
        }
    }
}
```

## client-api/Dockerfile
```Dockerfile
FROM alpine:3.17
# Install curl and kafkacat (kcat) for interacting with Kafka and Elasticsearch
RUN apk add --no-cache bash curl kafkacat jq
WORKDIR /app
COPY client.sh /app/client.sh
RUN chmod +x /app/client.sh
ENTRYPOINT ["/bin/bash"]
```

## client-api/client.sh
```bash
#!/bin/bash
# Simple CLI for marketplace: search product, request recommendations, or place orders.
# Usage:
#  ./client.sh search <name>
#  ./client.sh recommend <user_id>
#  ./client.sh order <product_id> <quantity>
# Kafka and ES endpoints
KAFKA_SERVERS="kafka1:9093,kafka2:9093,kafka3:9093"
ES_ENDPOINT="http://elasticsearch:9200"
# SASL/SSL configs for kafkacat
KAFKA_OPTS="-X security.protocol=SASL_SSL -X sasl.mechanisms=PLAIN -X sasl.username=client -X sasl.password=client-secret -X ssl.ca.location=/certs/ca.crt"
if [ "$1" == "search" ]; then
    query=$2
    echo "Searching for products with name matching '$query'..."
    curl -s "${ES_ENDPOINT}/products/_search?q=name:${query}" | jq '.hits.hits[]._source'
elif [ "$1" == "recommend" ]; then
    userId=$2
    echo "Requesting recommendations for user ${userId}..."
    # Send a recommendation request event to Kafka (topic: recommendations)
    echo "{\"user\":\"${userId}\",\"action\":\"recommendation_request\"}" | kafkacat -b ${KAFKA_SERVERS} ${KAFKA_OPTS} -t recommendations -P
    echo "Recommendation request sent. (In a real system, a recommendation service would consume this and respond.)"
elif [ "$1" == "order" ]; then
    productId=$2
    quantity=$3
    echo "Placing order: product ${productId}, quantity ${quantity}..."
    orderJson="{\"productId\":\"${productId}\",\"quantity\":${quantity},\"timestamp\":$(date +%s)}"
    echo $orderJson | kafkacat -b ${KAFKA_SERVERS} ${KAFKA_OPTS} -t orders -P
    echo "Order placed (record sent to 'orders' topic)."
else
    echo "Usage: $0 [search <name> | recommend <user_id> | order <product_id> <quantity>]"
fi
```

## kafka/Dockerfile
```Dockerfile
FROM confluentinc/cp-kafka:7.4.0
# (No custom actions needed since config is supplied via environment and volumes)
```

## kafka/config/server.properties
```properties
# Example Kafka broker configuration (most settings provided via environment variables in docker-compose).
broker.id=1
listeners=SASL_SSL://:9093
advertised.listeners=SASL_SSL://kafka1:9093
listener.security.protocol.map=SASL_SSL:SASL_SSL
inter.broker.listener.name=SASL_SSL
ssl.keystore.location=/certs/kafka1.keystore.jks
ssl.keystore.password=password
ssl.key.password=password
ssl.truststore.location=/certs/kafka1.truststore.jks
ssl.truststore.password=password
sasl.enabled.mechanisms=PLAIN
sasl.mechanism.inter.broker.protocol=PLAIN
authorizer.class.name=kafka.security.auth.SimpleAclAuthorizer
allow.everyone.if.no.acl.found=true
super.users=User:admin
```

## kafka/config/kafka_server_jaas.conf
```properties
KafkaServer {
   org.apache.kafka.common.security.plain.PlainLoginModule required
   username="admin"
   password="admin-secret"
   user_admin="admin-secret"
   user_client="client-secret";
};
```

## kafka/config/mm-source.properties
```properties
# MirrorMaker source cluster consumer config (Cluster A)
bootstrap.servers=kafka1:9093,kafka2:9093,kafka3:9093
group.id=mirrorMakerGroup
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin-secret";
ssl.truststore.location=/certs/kafka1.truststore.jks
ssl.truststore.password=password
```

## kafka/config/mm-target.properties
```properties
# MirrorMaker target cluster producer config (Cluster B)
bootstrap.servers=<TARGET_CLUSTER_BOOTSTRAP>
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="admin-secret";
ssl.truststore.location=/certs/kafka1.truststore.jks
ssl.truststore.password=password
```

## kafka/certs/generate-certs.sh
```bash
#!/bin/bash
# This script generates a self-signed Certificate Authority and broker certificates for Kafka TLS.
# It creates keystore and truststore for each broker.
set -e
PASSWORD="password"
# 1. Generate a CA (Certificate Authority)
openssl req -new -x509 -days 365 -nodes -subj "/CN=KafkaCA" -keyout ca.key -out ca.crt
# 2. Loop through brokers to create keystores and sign certificates
for i in 1 2 3; do
  # Generate broker keystore and key
  keytool -genkeypair -alias kafka$i -keystore kafka$i.keystore.jks \
          -keyalg RSA -storepass $PASSWORD -keypass $PASSWORD -dname "CN=kafka$i"
  # Create certificate signing request (CSR) for broker
  keytool -keystore kafka$i.keystore.jks -alias kafka$i -certreq -file kafka$i.csr -storepass $PASSWORD -keypass $PASSWORD
  # Sign the CSR with the CA
  openssl x509 -req -CA ca.crt -CAkey ca.key -CAcreateserial -in kafka$i.csr -out kafka$i.crt -days 365 -CAserial ca.srl -passin pass:$PASSWORD
  # Import CA certificate into broker keystore
  keytool -keystore kafka$i.keystore.jks -alias CARoot -import -file ca.crt -noprompt -storepass $PASSWORD -keypass $PASSWORD
  # Import signed certificate into broker keystore
  keytool -keystore kafka$i.keystore.jks -alias kafka$i -import -file kafka$i.crt -noprompt -storepass $PASSWORD -keypass $PASSWORD
  # Create truststore and import CA (for client trust)
  keytool -keystore kafka$i.truststore.jks -alias CARoot -import -file ca.crt -noprompt -storepass $PASSWORD -keypass $PASSWORD
done
echo "Certificates generated. Keystores and truststores are ready."
```

## kafka-streams/Dockerfile
```Dockerfile
FROM maven:3.8.5-openjdk-11 AS build
WORKDIR /build
COPY src ./src
COPY pom.xml .
RUN mvn package

FROM openjdk:11-jre-slim
WORKDIR /app
COPY --from=build /build/target/kafka-streams-app.jar /app/app.jar
COPY config/banned.txt /app/config/banned.txt
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

## kafka-streams/src/FilterApp.java
```java
package com.marketplace.stream;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Kafka Streams application that filters out banned products.
 * It reads from an input topic, drops records containing banned terms, and writes to an output topic.
 */
public class FilterApp {
    private static Set<String> bannedWords = Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) throws Exception {
        // Load initial banned list from file
        String bannedFile = System.getenv("BANNED_FILE");
        loadBannedWords(bannedFile);

        // Set up Kafka Streams configuration
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, System.getenv("APPLICATION_ID"));
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, System.getenv("BOOTSTRAP_SERVERS"));
        props.put("security.protocol", System.getenv("SECURITY_PROTOCOL"));
        props.put("sasl.mechanism", "PLAIN");
        props.put("sasl.jaas.config", System.getenv("SASL_JAAS_CONFIG"));
        props.put("ssl.truststore.location", System.getenv("SSL_TRUSTSTORE"));
        props.put("ssl.truststore.password", System.getenv("SSL_TRUSTSTORE_PASSWORD"));
        // Build topology: filter messages
        StreamsBuilder builder = new StreamsBuilder();
        String inputTopic = System.getenv("INPUT_TOPIC");
        String outputTopic = System.getenv("OUTPUT_TOPIC");
        KStream<String, String> products = builder.stream(inputTopic);
        KStream<String, String> allowedProducts = products.filter((key, value) -> !isBanned(value));
        allowedProducts.to(outputTopic);
        Topology topology = builder.build();
        KafkaStreams streams = new KafkaStreams(topology, props);
        // Start a thread to watch for updates to banned words file (simulate CLI update)
        if (bannedFile != null) {
            Thread watcher = new Thread(() -> watchBannedFile(bannedFile));
            watcher.setDaemon(true);
            watcher.start();
        }
        // Start the streams application
        streams.start();
        // Add shutdown hook to stop streams
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    // Checks if a product JSON contains any banned word (in name or description)
    private static boolean isBanned(String productJson) {
        if (productJson == null) return false;
        String lowerJson = productJson.toLowerCase();
        for (String banned : bannedWords) {
            if (lowerJson.contains(banned.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // Load banned words from a file (each line contains one banned term or product ID)
    private static void loadBannedWords(String filePath) {
        if (filePath == null) return;
        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
            bannedWords.clear();
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    bannedWords.add(line.trim());
                }
            }
            System.out.println("Loaded banned words: " + bannedWords);
        } catch (IOException e) {
            System.err.println("Failed to load banned words file: " + e.getMessage());
        }
    }

    // Watch the banned words file for modifications and reload if changed
    private static void watchBannedFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            WatchService watchService = FileSystems.getDefault().newWatchService();
            path.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            while (true) {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.context().toString().equals(path.getFileName().toString())) {
                            System.out.println("Banned list file changed. Reloading...");
                            loadBannedWords(filePath);
                        }
                    }
                    key.reset();
                }
            }
        } catch (Exception e) {
            System.err.println("Watcher thread error: " + e.getMessage());
        }
    }
}
```

## kafka-streams/config/banned.txt
```
weapon
alcohol
```

## kafka-connect/Dockerfile
```Dockerfile
FROM confluentinc/cp-kafka-connect:7.4.0
# The Confluent Kafka Connect image already includes the Elasticsearch connector via Confluent Hub.
# If not, you could install it with:
# RUN confluent-hub install --no-prompt confluentinc/kafka-connect-elasticsearch:latest
```

## kafka-connect/config/elastic-sink.json
```json
{
  "name": "ElasticsearchSinkConnector",
  "config": {
    "connector.class": "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector",
    "tasks.max": "1",
    "topics": "products,orders",
    "connection.url": "http://elasticsearch:9200",
    "key.ignore": "true",
    "schema.ignore": "true",
    "behavior.on.null.values": "ignore",
    "type.name": "_doc",
    "name": "ElasticsearchSinkConnector"
  }
}
```

## spark-app/Dockerfile
```Dockerfile
FROM maven:3.8.5-openjdk-11 AS build
WORKDIR /build
COPY src ./src
COPY pom.xml .
RUN mvn package

FROM bitnami/spark:3.3.2
USER root
WORKDIR /app
COPY --from=build /build/target/spark-app.jar /app/spark-app.jar
ENTRYPOINT ["/opt/bitnami/spark/bin/spark-submit", "--class", "com.marketplace.spark.AnalyticsApp", "--master", "local[*]", "--deploy-mode", "client", "--packages", "org.apache.spark:spark-sql-kafka-0-10_2.12:3.3.2", "/app/spark-app.jar"]
```

## spark-app/src/AnalyticsApp.java
```java
package com.marketplace.spark;

import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.types.*;
import static org.apache.spark.sql.functions.*;

public class AnalyticsApp {
    public static void main(String[] args) throws Exception {
        String kafkaBootstrap = System.getenv("BOOTSTRAP_SERVERS");
        String checkpointDir = System.getenv("CHECKPOINT_DIR");
        String inputTopic = System.getenv("TOPIC_IN");
        String outputTopic = System.getenv("TOPIC_OUT");
        String securityProtocol = System.getenv("SECURITY_PROTOCOL");
        String saslJaasConfig = System.getenv("SASL_JAAS_CONFIG");
        String saslMechanism = System.getenv("SASL_MECHANISM");

        SparkSession spark = SparkSession.builder()
                .appName("OrderAnalyticsApp")
                .config("spark.sql.shuffle.partitions", "1")
                .getOrCreate();

        // Define schema for order JSON
        StructType orderSchema = new StructType()
            .add("productId", DataTypes.StringType)
            .add("quantity", DataTypes.IntegerType)
            .add("timestamp", DataTypes.LongType);

        // Read streaming data from Kafka topic 'orders'
        Dataset<Row> ordersStream = spark
            .readStream()
            .format("kafka")
            .option("kafka.bootstrap.servers", kafkaBootstrap)
            .option("subscribe", inputTopic)
            .option("startingOffsets", "latest")
            .option("kafka.security.protocol", securityProtocol)
            .option("kafka.sasl.mechanism", saslMechanism)
            .option("kafka.sasl.jaas.config", saslJaasConfig)
            .option("kafka.ssl.truststore.location", "/certs/kafka1.truststore.jks")
            .option("kafka.ssl.truststore.password", "password")
            .load();

        // Extract the value as String and parse JSON
        Dataset<Row> orders = ordersStream.selectExpr("CAST(value AS STRING) as json")
            .select(from_json(col("json"), orderSchema).as("order"))
            .select("order.productId", "order.quantity");

        // Compute total quantity ordered per product
        Dataset<Row> orderStats = orders.groupBy("productId").agg(sum("quantity").alias("totalQuantity"));

        // Write the aggregation back to another Kafka topic (as JSON)
        Dataset<Row> output = orderStats.select(
            col("productId").cast("string").alias("key"),
            to_json(struct("productId", "totalQuantity")).alias("value")
        );

        StreamingQuery query = output.writeStream()
            .format("kafka")
            .outputMode("complete")
            .option("kafka.bootstrap.servers", kafkaBootstrap)
            .option("topic", outputTopic)
            .option("checkpointLocation", checkpointDir + "/order_stats")
            .option("kafka.security.protocol", securityProtocol)
            .option("kafka.sasl.mechanism", saslMechanism)
            .option("kafka.sasl.jaas.config", saslJaasConfig)
            .option("kafka.ssl.truststore.location", "/certs/kafka1.truststore.jks")
            .option("kafka.ssl.truststore.password", "password")
            .start();

        query.awaitTermination();
    }
}
```

## prometheus/prometheus.yml
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'kafka_brokers'
    static_configs:
      - targets: ['kafka1:7071', 'kafka2:7071', 'kafka3:7071']
  - job_name: 'kafka_connect'
    static_configs:
      - targets: ['kafka-connect:8083']
  - job_name: 'zookeeper'
    static_configs:
      - targets: ['zookeeper:2181']
    metrics_path: /metrics
    scheme: http

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

rule_files:
  - /etc/prometheus/alerts.yml
```

## prometheus/alerts.yml
```yaml
groups:
- name: KafkaAlerts
  rules:
  - alert: KafkaBrokerDown
    expr: up{job="kafka_brokers"} == 0
    for: 1m
    labels:
      severity: critical
    annotations:
      summary: "All Kafka brokers are down"
      description: "Prometheus has detected that no Kafka brokers are up for over 1 minute."
```

## prometheus/kafka_jmx.yml
```yaml
rules:
- pattern: "kafka.server<type=(.+), name=(.+)>(\\[(.+)\\])?Value"
  name: "kafka_server_$1_$2"
  labels:
    clientId: "$4"
```

## grafana/provisioning/datasources/prometheus.yml
```yaml
apiVersion: 1
datasources:
- name: Prometheus
  type: prometheus
  access: proxy
  url: http://prometheus:9090
  isDefault: true
```

## alertmanager/alertmanager.yml
```yaml
global:
  resolve_timeout: 5m

route:
  group_by: ['alertname']
  receiver: 'telegram'
  repeat_interval: 30m

receivers:
- name: 'telegram'
  webhook_configs:
  - url: 'https://api.telegram.org/bot<YOUR_BOT_TOKEN>/sendMessage?chat_id=<YOUR_CHAT_ID>'
    send_resolved: true

# Optionally, another receiver for email alerts:
- name: 'email'
  email_configs:
  - to: 'admin@example.com'
    from: 'kafka-alerts@example.com'
    smarthost: 'smtp.example.com:587'
    auth_username: 'user'
    auth_password: 'pass'
```

## README.md

# Покупай выгодно – Аналитическая платформа

Этот проект представляет собой полнофункциональную аналитическую платформу для маркетплейса **"Покупай выгодно"**, включающую интеграцию множества компонентов: **Apache Kafka** (с защитой TLS/SASL, кластер из 3 брокеров), **Kafka Streams** для потоковой обработки, **Kafka Connect** для загрузки данных в **Elasticsearch**, хранилище данных **HDFS**, обработку больших данных с помощью **Apache Spark**, а также систему мониторинга на базе **Prometheus**, **Grafana** и **Alertmanager**. Все компоненты запускаются через Docker Compose для упрощения развертывания.

## Компоненты и структура проекта

- **SHOP API** (`shop-api/`): Java-приложение, эмулирующее магазин. Оно читает список товаров из JSON-файла и отправляет их в Kafka (топик `products_raw`). Это симулирует загрузку товаров на платформу.
- **CLIENT API** (`client-api/`): CLI-клиент (bash-скрипт), позволяющий искать товары по имени, отправлять запросы на рекомендации, а также оформлять заказы. Поиск выполняется через Elasticsearch, запросы на рекомендации и заказы отправляются в соответствующие топики Kafka (`recommendations`, `orders`).
- **Apache Kafka** (`kafka/`): Кластер Kafka из 3 брокеров (`kafka1`, `kafka2`, `kafka3`) с поддержкой шифрования TLS и аутентификации SASL/PLAIN. Используется ZooKeeper (контейнер `zookeeper`) для координации. Настроены ACL (списки доступа) для разграничения прав пользователей Kafka:
  - **admin** – административный пользователь Kafka (используется внутренними сервисами, Kafka Connect, MirrorMaker).
  - **client** – пользователь для клиентского приложения (CLI), имеющий доступ на запись/чтение нужных топиков.
- **Kafka MirrorMaker** (`mirror-maker`): Компонент для репликации данных Kafka во второй кластер. В данном проекте показана конфигурация MirrorMaker 2 (в составе Kafka Connect) для зеркалирования топиков `products` и `orders` во внешний кластер (для простоты сам второй кластер не развернут полностью в этом Compose-файле, но при необходимости можно поднять аналогичный второй кластер и указать его адреса в `mm-target.properties`).
- **Kafka Streams приложение** (`kafka-streams/`): Java-приложение, реализующее потоковую фильтрацию. Оно потребляет поток товаров из топика `products_raw`, фильтрует товары, запрещённые к продаже (список запрещённых слов хранится в файле `banned.txt` и может динамически обновляться), и публикует "очищенные" данные в топик `products`. Поддерживается обновление списка через изменение файла `banned.txt` (приложение отслеживает изменения).
- **Kafka Connect** (`kafka-connect/`): Коннектор Elasticsearch Sink, выгружающий данные товаров и заказов из Kafka в Elasticsearch. Конфигурация `elastic-sink.json` задаёт подключение к Elasticsearch и топики (`products`, `orders`) для синхронизации. 
- **Elasticsearch** (`elasticsearch/`): Хранилище данных для товаров и заказов. Настраивается в режим single-node для упрощённого запуска. Данные из топика `products` записываются в индекс `products`, а из `orders` – в индекс `orders` (создаются автоматически коннектором при поступлении данных).
- **Apache Spark** (`spark-app/` + HDFS): Контейнеры HDFS (`namenode`, `datanode`) обеспечивают хранилище для чекпоинтов Spark Streaming. Spark-приложение `AnalyticsApp` выполняет потоковую аналитику: читает поток заказов из топика `orders`, подсчитывает суммарное количество заказанных единиц каждого товара и записывает агрегированные результаты обратно в Kafka (топик `order_stats`). Это демонстрирует обработку и агрегацию данных в реальном времени.
- **Prometheus & Grafana** (`prometheus/`, `grafana/`): Мониторинг ключевых метрик Kafka. В Kafka-брокеры встроен JMX Exporter, раскрывающий метрики на порту 7071, которые собираются Prometheus-ом. Настроены job-ы для сбора метрик брокеров Kafka, Kafka Connect и ZooKeeper. Grafana использует Prometheus как источник данных (предустановлен datasource), можно импортировать готовые дашборды Kafka для визуализации (например, официальный Grafana Labs dashboard ID 12579 для Kafka). 
- **Alertmanager** (`alertmanager/`): Компонент оповещений для Prometheus. В конфигурации задан пример оповещения **KafkaBrokerDown**, срабатывающего при недоступности всех брокеров Kafka. Alertmanager настроен с webhook-примером для интеграции с Telegram (необходимо подставить свой токен бота и ID чата) и шаблоном для отправки на email.

## Инструкция по запуску

1. **Предварительная настройка TLS**: Перед первым запуском необходимо сгенерировать сертификаты для Kafka. В каталоге `kafka/certs/` присутствует скрипт `generate-certs.sh`, который сгенерирует корневой сертификат (CA) и самоподписанные сертификаты для каждого из 3 брокеров, создаст keystore и truststore. Запустите этот скрипт (Linux/Mac: `bash generate-certs.sh`) для подготовки файлов сертификатов. В результате в папке `kafka/certs/` появятся файлы `kafka1.keystore.jks`, `kafka1.truststore.jks` (и аналогичные для kafka2, kafka3) и файл CA `ca.crt`. *(Примечание: в данном проекте используется упрощённый self-signed сертификат для демонстрации.)*
2. **Запуск Docker Compose**: Убедитесь, что Docker и Docker Compose установлены. Выполните команду:  
   > **Примечание:** Для работы мониторинга необходимо скачать Java-агент [Prometheus JMX Exporter](https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.17.2/jmx_prometheus_javaagent-0.17.2.jar) и поместить его в каталог `prometheus/` под именем `jmx_prometheus_javaagent.jar` (или изменить путь в Docker Compose). В репозитории этот jar-файл не включён.  
   ```bash
   docker-compose up -d --build
   ```  
   Это соберёт Docker-образы для Java-приложений и запустит все контейнеры платформы. Загрузка и запуск всех сервисов может занять несколько минут (Elasticsearch может требовать немного времени для инициализации).
3. **Проверка работы компонентов**:
   - После запуска, Kafka-брокеры слушают на порту 9093 (SASL_SSL). Топики `products_raw`, `products`, `orders`, `recommendations`, `order_stats` будут созданы автоматически (включён auto-create-topics и приложения-производители создают топики при отправке сообщений). 
   - Elasticsearch доступен на порту 9200 (http://localhost:9200). Индексы `products` и `orders` создаются коннектором при поступлении данных.
   - Grafana UI доступна на порту 3000 (http://localhost:3000, логин *admin*, пароль *admin*), Prometheus — на порту 9090.
   - Приложение Shop API (`shop-api`) при старте отправит все товары из файла `products.json` в Kafka (топик `products_raw`). Приложение Kafka Streams (`kafka-streams-app`) прочитает `products_raw`, отфильтрует запрещённые товары (по умолчанию фильтруются примеры из `banned.txt`: "weapon", "alcohol") и запишет разрешённые товары в топик `products`. Коннектор Elasticsearch начнёт перенос данных из `products` в индекс `products`.
   - Spark-приложение (`spark-app`) запустится и будет ожидать поступления заказов в топике `orders` (изначально там нет данных, пока вы не выполните команду заказа через Client API).
4. **Использование Client API**:  
   Контейнер `client-api` содержит CLI-скрипт для взаимодействия с платформой. Откройте терминал внутри контейнера командой:  
   ```bash
   docker-compose exec client-api bash
   ```  
   Находясь внутри контейнера, можно выполнять следующие команды:
   - **Поиск товара по имени**:  
     ```bash
     ./client.sh search телефон
     ```  
     Это выполнит запрос к Elasticsearch (индекс `products`) и выведет список товаров, название которых содержит слово "телефон". Данные товаров оказались в Elasticsearch благодаря Kafka Connect.
   - **Запрос рекомендаций**:  
     ```bash
     ./client.sh recommend user123
     ```  
     Отправляет событие-запрос на рекомендации для пользователя `user123` в Kafka (топик `recommendations`). В реальной системе на этот топик подписывался бы сервис рекомендаций, который обработал бы запрос и отправил ответ. В нашем случае мы просто фиксируем отправку – сообщение будет доступно в топике (можно проверить через Kafka consumer или логи).
   - **Оформление заказа**:  
     ```bash
     ./client.sh order p1001 2
     ```  
     Имитирует оформление заказа на товар с ID `p1001` в количестве 2 единиц. Событие заказа отправляется в топик `orders`. Наше Spark-приложение (AnalyticsApp) получит это событие, обновит агрегированные данные и запишет новую сумму по данному товару в топик `order_stats`. (Коннектор Elasticsearch при необходимости можно настроить и на `order_stats`, чтобы хранить результаты аналитики, либо на `orders` для хранения сырых заказов.)
   - При необходимости, можно отредактировать на хосте файл `kafka-streams/config/banned.txt` (например, добавить новое запрещённое слово) и перезапустить контейнер `kafka-streams-app`. Приложение при старте загрузит обновлённый список запрещённых слов. (В текущей реализации также предусмотрено слежение за изменениями файла, поэтому приложение должно автоматически подхватить изменения списка на лету.)
5. **Мониторинг**:
   - **Prometheus** автоматически собирает метрики. Откройте [http://localhost:9090](http://localhost:9090) и убедитесь, что в разделе **Targets** цели `kafka_brokers`, `kafka_connect`, `zookeeper` находятся в состоянии UP.
   - **Grafana**: зайдите на [http://localhost:3000](http://localhost:3000) под логином/паролем *admin/admin*. Data source Prometheus уже настроен. Можно импортировать дашборд для Kafka (например, Grafana Labs dashboard ID 12579) чтобы увидеть метрики брокеров: количество сообщений, задержки, размер очередей, использование памяти и т.д. Аналогично можно визуализировать метрики Kafka Connect (job `kafka_connect`) или других компонентов.
   - **Alertmanager**: настроен на порт 9093. В файле `prometheus/alerts.yml` определено правило оповещения на случай, если все брокеры Kafka не отвечают в течение 1 минуты. Для тестирования можно остановить все три контейнера Kafka (`docker-compose stop kafka1 kafka2 kafka3`) и спустя минуту проверить в веб-интерфейсе Prometheus (раздел **Alerts**), что сработал алерт **KafkaBrokerDown**. Alertmanager при соответствующей настройке отправит уведомление (в проекте указаны примеры конфигурации для Telegram и email — необходимо вставить реальные данные, чтобы заработало).

## Структура топиков и данных

- **products_raw** – исходные данные товаров (JSON), поступающие от Shop API.
- **products** – "очищенные" данные товаров (исключены запрещённые позиции). Эти данные индексируются в Elasticsearch (index `products`).
- **orders** – события заказов (JSON с полями `productId`, `quantity`, `timestamp`), формируемые через Client API.
- **order_stats** – агрегированные статистики заказов по товарам (JSON с `productId` и `totalQuantity`), формируемые Spark-приложением. (Эти данные можно при необходимости также выгружать в Elasticsearch, настроив дополнительный коннектор или расширив существующий.)
- **recommendations** – запросы на рекомендации (пока используются только для демонстрации отправки события от клиента).

## Примечания

- **ACL и безопасность**: Kafka запущен с включёнными SSL/TLS и SASL. Файлы keystore/truststore для брокеров созданы самоподписанным ЦС. Для упрощения, все клиентские подключения используют пользователя `client` (пароль `client-secret`). ACL настроены параметром `allow.everyone.if.no.acl.found=true` (разрешает доступ при отсутствии явных записей ACL), поэтому минимально система будет работать сразу. Для повышенной безопасности в боевом окружении следует явно задать ACL для пользователей (например, запретить любые операции, кроме разрешённых). В конфигурации `kafka_server_jaas.conf` определены два пользователя: `admin` и `client`. `admin` указан как супер-пользователь (`super.users=User:admin`), его используют сервисы Kafka (Connect, MirrorMaker).
- **MirrorMaker**: В данном Compose-файле MirrorMaker демонстрирует настройку репликации топиков в другой кластер. Для полноты можно развернуть второй Kafka-кластер (аналогичный первому) и указать его bootstrap.servers в `mm-target.properties`, тогда MirrorMaker будет копировать сообщения из основных топиков в резервный кластер (для отказоустойчивости или гео-репликации).
- **Spark**: Приложение настроено запускаться в `local[*]` режиме (т.е. использует все ядра контейнера, без отдельного Spark-кластера). HDFS развернут для хранения checkpoint-данных потока (путь `hdfs://namenode:9000/spark-checkpoints`), что необходимо для сохранения состояния между запусками при агрегировании в режиме `complete`. В простых сценариях можно было бы использовать локальную файловую систему, но для демонстрации включён HDFS. 
- **Elasticsearch**: При первом запуске коннектор сам создаст индексы `products` и `orders`. При желании можно добавить шаблоны индексов с заданной схемой (mapping) для этих индексов. В нашем случае, так как мы используем конвертеры `StringConverter` (без схемы), все поля документов будут динамически определяться Elasticsearch. Для продакшна рекомендуется использовать схему (например, формат Avro/Schema Registry и соответствующие коннекторы).
- **Ресурсы**: Если контейнеры Kafka/Elasticsearch не запускаются из-за нехватки памяти, убедитесь, что Docker выделено достаточно ресурсов. В настройках Elasticsearch установлено ограничение памяти JVM 512MB (`ES_JAVA_OPTS`), что подходит для демонстрации на умеренных ресурсах.
- **Дальнейшее развитие**: Данный проект можно расширять: например, реализовать сервис рекомендаций, который будет обрабатывать запросы из топика `recommendations` и формировать ответы, добавить REST API сервис вместо CLI-скрипта, интегрировать панель управления и т.д. Также можно настроить дополнительные алерты и уведомления (Grafana Alerting, Alertmanager интеграции с Email/Slack/Telegram и пр.) для более полного мониторинга.
