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
