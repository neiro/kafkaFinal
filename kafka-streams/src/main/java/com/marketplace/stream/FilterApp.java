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
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.Consumed;


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
        KStream<String, String> products = builder.stream(
            inputTopic,
            Consumed.with(Serdes.String(), Serdes.String())
        );
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
