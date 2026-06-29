"""
Spark session 获取（PySpark kernel 下可用）。
"""
import os
from typing import Optional


_spark = None


def get_spark_session():
    """
    获取或创建 SparkSession（已配置 Iceberg catalog）。

    在 JupyterHub pyspark kernel 下，SparkSession 已由 kernel 启动时初始化；
    本函数返回 singleton。
    """
    global _spark
    if _spark is not None:
        return _spark

    try:
        from pyspark.sql import SparkSession
    except ImportError as e:
        raise RuntimeError(
            "PySpark 未安装。请在 pyspark kernel 下运行，或 pip install pyspark"
        ) from e

    builder = (
        SparkSession.builder
        .appName(f"onelake-notebook-{os.environ.get('ONELAKE_NOTEBOOK_ID', 'adhoc')}")
        .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
        .config("spark.sql.catalog.iceberg", "org.apache.iceberg.spark.SparkCatalog")
        .config("spark.sql.catalog.iceberg.type", "rest")
        .config("spark.sql.catalog.iceberg.uri",
                os.environ.get('ICEBERG_REST_URI', 'http://spark-master:8080'))
        .config("spark.sql.catalog.iceberg.warehouse",
                os.environ.get('ICEBERG_WAREHOUSE', 's3://onelake/warehouse'))
    )

    # 若已有活跃 session（kernel 启动时创建），直接复用
    _spark = builder.getOrCreate()
    return _spark
