package com.marketplace.spark;

import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.types.*;
import static org.apache.spark.sql.functions.*;

public class AnalyticsApp {
    public static void main(String[] args) throws Exception {
        // Читаем настройки подключения из переменных окружения.
        // Если переменные окружения отсутствуют, используются тестовые значения из docker-compose.
        String kafkaBootstrap = System.getenv("BOOTSTRAP_SERVERS") != null ?
            System.getenv("BOOTSTRAP_SERVERS") : "kafka1:9093,kafka2:9093,kafka3:9093";
        String checkpointDir = System.getenv("CHECKPOINT_DIR") != null ?
            System.getenv("CHECKPOINT_DIR") : "hdfs://namenode:9000/spark-checkpoints";
        String inputTopic = System.getenv("TOPIC_IN") != null ?
            System.getenv("TOPIC_IN") : "orders";
        String outputTopic = System.getenv("TOPIC_OUT") != null ?
            System.getenv("TOPIC_OUT") : "order_stats";
        String securityProtocol = System.getenv("SECURITY_PROTOCOL") != null ?
            System.getenv("SECURITY_PROTOCOL") : "SASL_SSL";
        String saslJaasConfig = System.getenv("SASL_JAAS_CONFIG") != null ?
            System.getenv("SASL_JAAS_CONFIG") : "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\"admin-secret\";";
        String saslMechanism = System.getenv("SASL_MECHANISM") != null ?
            System.getenv("SASL_MECHANISM") : "PLAIN";
        String sslTruststore = System.getenv("SSL_TRUSTSTORE_LOCATION") != null ?
            System.getenv("SSL_TRUSTSTORE_LOCATION") : "/certs/kafka1.truststore.jks";
        String sslTruststorePassword = System.getenv("SSL_TRUSTSTORE_PASSWORD") != null ?
            System.getenv("SSL_TRUSTSTORE_PASSWORD") : "changeit";
        String sslKeystore = System.getenv("SSL_KEYSTORE_LOCATION") != null ?
            System.getenv("SSL_KEYSTORE_LOCATION") : "/certs/kafka1.keystore.jks";
        String sslKeystorePassword = System.getenv("SSL_KEYSTORE_PASSWORD") != null ?
            System.getenv("SSL_KEYSTORE_PASSWORD") : "changeit";
        String sslKeyPassword = System.getenv("SSL_KEY_PASSWORD") != null ?
            System.getenv("SSL_KEY_PASSWORD") : "changeit";

        // Временное логгирование для проверки значений параметров
        System.out.println("BOOTSTRAP_SERVERS: " + kafkaBootstrap);
        System.out.println("CHECKPOINT_DIR: " + checkpointDir);
        System.out.println("TOPIC_IN: " + inputTopic);
        System.out.println("TOPIC_OUT: " + outputTopic);
        System.out.println("SECURITY_PROTOCOL: " + securityProtocol);
        System.out.println("SASL_JAAS_CONFIG: " + saslJaasConfig);
        System.out.println("SASL_MECHANISM: " + saslMechanism);
        System.out.println("SSL_TRUSTSTORE_LOCATION: " + sslTruststore);
        System.out.println("SSL_TRUSTSTORE_PASSWORD: " + sslTruststorePassword);
        System.out.println("SSL_KEYSTORE_LOCATION: " + sslKeystore);
        System.out.println("SSL_KEYSTORE_PASSWORD: " + sslKeystorePassword);
        System.out.println("SSL_KEY_PASSWORD: " + sslKeyPassword);

        // Создаем SparkSession с базовыми настройками
        SparkSession spark = SparkSession.builder()
                .appName("OrderAnalyticsApp")
                .config("spark.sql.shuffle.partitions", "1") // Устанавливаем число shuffle-партиций в 1 для упрощения (подходит для тестов)
                .getOrCreate();

        // Определяем схему входящего JSON-сообщения заказа.
        StructType orderSchema = new StructType()
            .add("productId", DataTypes.StringType)
            .add("quantity", DataTypes.IntegerType)
            .add("timestamp", DataTypes.LongType);

        // Читаем поток данных из Kafka.
        Dataset<Row> ordersStream = spark
            .readStream()
            .format("kafka")
            .option("kafka.bootstrap.servers", kafkaBootstrap)
            .option("subscribe", inputTopic)
            .option("startingOffsets", "latest")
            .option("kafka.security.protocol", securityProtocol)
            .option("kafka.sasl.mechanism", saslMechanism)
            .option("kafka.sasl.jaas.config", saslJaasConfig)
            .option("kafka.ssl.truststore.location", sslTruststore)
            .option("kafka.ssl.truststore.password", sslTruststorePassword)
            .option("kafka.ssl.keystore.location", sslKeystore)
            .option("kafka.ssl.keystore.password", sslKeystorePassword)
            .option("kafka.ssl.key.password", sslKeyPassword) // Указываем пароль для ключа, если требуется
            .load();

        // Преобразуем бинарное поле "value" в строку и парсим JSON согласно схеме orderSchema.
        Dataset<Row> orders = ordersStream.selectExpr("CAST(value AS STRING) as json")
            .select(from_json(col("json"), orderSchema).as("order"))
            .select("order.productId", "order.quantity");

        // Агрегируем общее количество заказов по продуктам
        Dataset<Row> orderStats = orders.groupBy("productId")
            .agg(sum("quantity").alias("totalQuantity"));

        // Преобразуем агрегированные данные в формат JSON для отправки обратно в Kafka.
        Dataset<Row> output = orderStats.select(
            col("productId").cast("string").alias("key"),
            to_json(struct("productId", "totalQuantity")).alias("value")
        );

        // Записываем результат обратно в Kafka в режиме complete.
        StreamingQuery query = output.writeStream()
            .format("kafka")
            .outputMode("complete")
            .option("kafka.bootstrap.servers", kafkaBootstrap)
            .option("topic", outputTopic)
            .option("checkpointLocation", checkpointDir + "/order_stats")
            .option("kafka.security.protocol", securityProtocol)
            .option("kafka.sasl.mechanism", saslMechanism)
            .option("kafka.sasl.jaas.config", saslJaasConfig)
            .option("kafka.ssl.truststore.location", sslTruststore)
            .option("kafka.ssl.truststore.password", sslTruststorePassword)
            .option("kafka.ssl.keystore.location", sslKeystore)
            .option("kafka.ssl.keystore.password", sslKeystorePassword)
            .option("kafka.ssl.key.password", sslKeyPassword)
            .start();

        // Ожидаем завершения стриминга.
        query.awaitTermination();
    }
}
