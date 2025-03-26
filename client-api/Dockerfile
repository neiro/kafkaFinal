FROM alpine:3.17
# Install curl and kafkacat (kcat) for interacting with Kafka and Elasticsearch
RUN apk add --no-cache bash curl kafkacat jq
WORKDIR /app
COPY client.sh /app/client.sh
RUN chmod +x /app/client.sh
ENTRYPOINT ["/bin/bash"]
