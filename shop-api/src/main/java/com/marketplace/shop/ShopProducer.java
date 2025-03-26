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
