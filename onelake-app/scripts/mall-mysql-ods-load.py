import os

from pyspark.sql import SparkSession


MYSQL_URL = os.getenv(
    "MALL_MYSQL_JDBC_URL",
    "jdbc:mysql://host.docker.internal:3307/mall_test"
    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
)
MYSQL_USER = os.getenv("MALL_MYSQL_USER", "onelake")
MYSQL_PASSWORD = os.getenv("MALL_MYSQL_PASSWORD", "onelake123456")


def main():
    spark = (
        SparkSession.builder.appName("mall-mysql-ods-load")
        .config("spark.sql.catalog.onelake", "org.apache.iceberg.spark.SparkCatalog")
        .config("spark.sql.catalog.onelake.type", "hive")
        .config("spark.sql.catalog.onelake.uri", "thrift://hive-metastore:9083")
        .config("spark.sql.catalog.onelake.warehouse", "s3a://onelake/warehouse")
        .getOrCreate()
    )
    spark.sql("CREATE NAMESPACE IF NOT EXISTS onelake.ods LOCATION 's3a://onelake/warehouse/ods'")
    spark.sql("CREATE NAMESPACE IF NOT EXISTS onelake.dwd LOCATION 's3a://onelake/warehouse/dwd'")

    for table in ("users", "user_orders", "order_items"):
        df = (
            spark.read.format("jdbc")
            .option("url", MYSQL_URL)
            .option("dbtable", table)
            .option("user", MYSQL_USER)
            .option("password", MYSQL_PASSWORD)
            .option("driver", "com.mysql.cj.jdbc.Driver")
            .load()
        )
        target = f"onelake.ods.{table}"
        spark.sql(f"DROP TABLE IF EXISTS {target}")
        df.writeTo(target).using("iceberg").create()
        print(f"{target} rows={df.count()}")

    spark.stop()


if __name__ == "__main__":
    main()
