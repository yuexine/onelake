"""
相关性分析模板（参数化）。
输出指标之间的 Pearson 相关性矩阵。
"""
from onelake import dataset, publish
import pandas as pd

# Parameters
table_fqn = "iceberg.dwd.dwd_user_metrics"  # type: ignore
columns = "gmv,orders,login_count,dwell_minutes"  # type: ignore
method = "pearson"  # type: ignore  # pearson / spearman / kendall
output_table = "ads_correlation_matrix"  # type: ignore

cols = [c.strip() for c in columns.split(",")]

df = dataset(table_fqn).to_pandas()
corr = df[cols].corr(method=method).reset_index().rename(columns={"index": "metric"})
corr = corr.melt(id_vars="metric", var_name="metric_b", value_name="correlation")

publish(corr, output_table, classification="L1",
        description=f"{method} correlation matrix for {columns}")
