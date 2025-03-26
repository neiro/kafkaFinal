#!/bin/bash
set -e  # Прекращает выполнение скрипта при ошибке любой команды

# Переходим в директорию, где будут храниться сертификаты
cd /var/ssl/private

# Проверяем, установлена ли переменная CA_PASSWORD
if [ -z "$CA_PASSWORD" ]; then
  echo "Переменная CA_PASSWORD не установлена. Задайте её перед запуском."
  exit 1
fi

# Генерируем CA-сертификаты, если они отсутствуют
if [ ! -f ca-cert ] || [ ! -f ca-key ]; then
  echo "=== Создание корневого сертификата (CA) ==="
  openssl req -new -x509 -keyout ca-key -out ca-cert -days 365 -subj "/CN=Kafka-CA" -passout pass:${CA_PASSWORD}
else
  echo "CA-файлы уже существуют. Пропускаем генерацию CA."
fi

# Создаем файлы с креденшелами для keystore и truststore, если они отсутствуют
if [ ! -f keystore_creds ]; then
  echo "changeit" > keystore_creds
fi

if [ ! -f truststore_creds ]; then
  echo "changeit" > truststore_creds
fi

# Функция для генерации сертификатов для брокера
generate_broker_cert() {
  BROKER_NAME=$1
  if [ -f done.txt ]; then
    echo "Сертификаты для ${BROKER_NAME} уже сгенерированы. Пропускаем."
    return
  fi
  echo "=== Генерация сертификатов для ${BROKER_NAME} ==="
  
  # Генерация keystore для брокера
  keytool -genkey -alias ${BROKER_NAME} -keyalg RSA \
    -keystore ${BROKER_NAME}.keystore.jks \
    -dname "CN=${BROKER_NAME}" -storepass changeit -keypass changeit

  # Создание CSR (Certificate Signing Request) для брокера
  keytool -certreq -alias ${BROKER_NAME} -keystore ${BROKER_NAME}.keystore.jks -file ${BROKER_NAME}.csr -storepass changeit

  # Подписание CSR с использованием CA
  openssl x509 -req -CA ca-cert -CAkey ca-key -in ${BROKER_NAME}.csr -out ${BROKER_NAME}-signed.crt -days 365 -CAcreateserial -passin pass:${CA_PASSWORD}

  # Импорт CA в keystore брокера
  keytool -import -alias CARoot -file ca-cert -keystore ${BROKER_NAME}.keystore.jks -storepass changeit -noprompt

  # Импорт подписанного сертификата брокера
  keytool -import -alias ${BROKER_NAME} -file ${BROKER_NAME}-signed.crt -keystore ${BROKER_NAME}.keystore.jks -storepass changeit

  # Создание truststore и импорт CA
  keytool -import -alias CARoot -file ca-cert -keystore ${BROKER_NAME}.truststore.jks -storepass changeit -noprompt
  
  # Создаем файл-маркер, сигнализирующий о завершении генерации для данного брокера
  touch done.txt
}

# Генерируем сертификаты для каждого брокера (если они еще не созданы)
for broker in kafka1 kafka2 kafka3; do
  mkdir -p ${broker}
  cd ${broker}
  
  # Проверяем, был ли уже сгенерирован сертификат
  if [ -f done.txt ]; then
    echo "Сертификаты для ${broker} уже существуют, пропускаем."
  else
    cp ../ca-cert ../ca-key ../keystore_creds ../truststore_creds .
    generate_broker_cert ${broker}
  fi

  # Копируем конфигурационный файл аутентификации, если его нет
  if [ ! -f kafka_server_jaas.conf ]; then
    cp ../kafka_server_jaas.conf .
    echo "Добавлен kafka_server_jaas.conf в ${broker}"
  fi

  cd ..
done



echo "Сертификаты успешно сгенерированы."

# Контейнер остается в ожидании, чтобы файлы не удалялись после завершения
tail -f /dev/null
