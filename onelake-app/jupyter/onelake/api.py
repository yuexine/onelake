"""
用户面向 API：dataset() / publish() / DatasetHandle。
"""
from typing import Optional
import os

from onelake._trino import query_trino_to_pandas
from onelake._spark import get_spark_session
from onelake._control_plane import ControlPlaneClient


class DatasetHandle:
    """数据集句柄：通过 FQN 或 dataset_id 直查 Trino。"""

    def __init__(self, fqn: Optional[str] = None, dataset_id: Optional[str] = None,
                 client: Optional[ControlPlaneClient] = None):
        if not fqn and not dataset_id:
            raise ValueError("dataset(fqn=...) 或 dataset_by_id(dataset_id=...) 必填一项")
        self.fqn = fqn
        self.dataset_id = dataset_id
        self._client = client or ControlPlaneClient.from_env()

    def to_pandas(self, limit: int = 1_000_000):
        """读取为 pandas DataFrame（轻量分析，直查 Trino）。"""
        if self.fqn:
            sql = f"SELECT * FROM {self.fqn} LIMIT {limit}"
        else:
            # 通过控制面拿 dataset.select_sql 或 asset_fqn
            ds = self._client.get_dataset(self.dataset_id)
            self.fqn = ds.get('asset_fqn')
            sql = ds.get('select_sql') or f"SELECT * FROM {self.fqn} LIMIT {limit}"
        return query_trino_to_pandas(sql)

    def to_spark(self):
        """读取为 Spark DataFrame（重计算场景，PySpark kernel 下使用）。"""
        spark = get_spark_session()
        if not self.fqn:
            ds = self._client.get_dataset(self.dataset_id)
            self.fqn = ds['asset_fqn']
        # Iceberg table identifier（如 iceberg.dwd.dwd_user_codex）
        return spark.table(self.fqn.replace(".", "_"))

    def __repr__(self) -> str:
        return f"<DatasetHandle fqn={self.fqn} dataset_id={self.dataset_id}>"


def dataset(fqn: str) -> DatasetHandle:
    """
    读路径：通过 Trino 直查 Iceberg 表（不经控制面 REST，控制面不挡查询热路径）。

    示例：
        df = onelake.dataset("iceberg.dwd.dwd_user_codex").to_pandas()
    """
    return DatasetHandle(fqn=fqn)


def dataset_by_id(dataset_id: str) -> DatasetHandle:
    """通过数据集 ID 拿句柄（先调控制面拿 FQN 再查 Trino）。"""
    return DatasetHandle(dataset_id=dataset_id)


def publish(df, name: str, classification: str = "L1",
            description: str = "", schema: str = "dwd") -> str:
    """
    写路径：
    1) Spark 写新 Iceberg 表（iceberg.{schema}.{name}）
    2) 调控制面 /api/v1/analytics/notebooks/artifact 注册到 catalog + 建 dataset 记录

    返回值：新表的 FQN（如 iceberg.dwd.ads_user_rfm_seg）

    示例：
        from onelake import dataset, publish
        df = dataset("iceberg.dwd.dwd_user").to_pandas()
        # ... RFM 计算后 ...
        publish(rfm_df, "ads_user_rfm_seg", classification="L2")
    """
    from onelake._spark import get_spark_session
    spark = get_spark_session()
    table_fqn = f"iceberg.{schema}.{name}"

    # 1) 写 Iceberg 表
    if hasattr(df, 'writeTo'):
        # PySpark 3.3+ iceberg v2 写入
        df.writeTo(table_fqn).createOrReplace()
    else:
        # pandas fallback：先转 spark df
        spark_df = spark.createDataFrame(df)
        spark_df.write.mode("overwrite").saveAsTable(table_fqn)

    # 2) 注册到 catalog
    client = ControlPlaneClient.from_env()
    client.register_artifact(
        fqn=table_fqn,
        classification=classification,
        description=description,
        produced_by_notebook=os.environ.get('ONELAKE_NOTEBOOK_ID'),
    )

    print(f"[onelake] published: {table_fqn} (classification={classification})")
    return table_fqn
