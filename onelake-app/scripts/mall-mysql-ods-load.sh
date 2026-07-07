#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="${SPARK_SUBMIT_CONTAINER:-onelake-dagster-user-code}"
REMOTE_SCRIPT="/tmp/mall-mysql-ods-load.py"

docker cp "${ROOT_DIR}/scripts/mall-mysql-ods-load.py" "${CONTAINER}:${REMOTE_SCRIPT}"
docker exec \
  -e MALL_MYSQL_JDBC_URL="${MALL_MYSQL_JDBC_URL:-jdbc:mysql://host.docker.internal:3307/mall_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}" \
  -e MALL_MYSQL_USER="${MALL_MYSQL_USER:-onelake}" \
  -e MALL_MYSQL_PASSWORD="${MALL_MYSQL_PASSWORD:-onelake123456}" \
  "${CONTAINER}" /usr/local/lib/python3.11/site-packages/pyspark/bin/spark-submit \
    --master local[2] \
    --driver-memory 1g \
    --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2,org.apache.iceberg:iceberg-aws-bundle:1.5.2,org.apache.hadoop:hadoop-aws:3.3.4,com.mysql:mysql-connector-j:8.4.0 \
    --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
    --conf spark.sql.defaultCatalog=onelake \
    --conf spark.sql.catalog.onelake=org.apache.iceberg.spark.SparkCatalog \
    --conf spark.sql.catalog.onelake.type=hive \
    --conf spark.sql.catalog.onelake.uri=thrift://hive-metastore:9083 \
    --conf spark.sql.catalog.onelake.warehouse=s3a://onelake/warehouse \
    --conf spark.sql.catalog.onelake.io-impl=org.apache.iceberg.aws.s3.S3FileIO \
    --conf spark.sql.catalog.onelake.s3.endpoint=http://minio:9000 \
    --conf spark.sql.catalog.onelake.s3.path-style-access=true \
    --conf spark.sql.catalog.onelake.s3.access-key-id=minio \
    --conf spark.sql.catalog.onelake.s3.secret-access-key=minio12345 \
    --conf spark.sql.catalog.onelake.client.region=us-east-1 \
    --conf spark.hadoop.fs.s3a.endpoint=http://minio:9000 \
    --conf spark.hadoop.fs.s3a.path.style.access=true \
    --conf spark.hadoop.fs.s3a.access.key=minio \
    --conf spark.hadoop.fs.s3a.secret.key=minio12345 \
    --conf spark.hadoop.fs.s3a.aws.credentials.provider=org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider \
    --conf spark.hadoop.fs.s3a.connection.ssl.enabled=false \
    "${REMOTE_SCRIPT}"
