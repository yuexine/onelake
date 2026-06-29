"""
KMeans 聚类模板（参数化）。
通过 papermill 参数化执行：
    papermill kmeans_clustering.ipynb out.ipynb -p table_fqn iceberg.dwd.dwd_user \
                                              -p features "age,purchase_amount,login_count" \
                                              -p k 4 -p output_table ads_user_kmeans
"""
from onelake import dataset, publish
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.preprocessing import StandardScaler

# Parameters (papermill 注入)
table_fqn = "iceberg.dwd.dwd_user"            # type: ignore
features = "age,purchase_amount,login_count"  # type: ignore
k = 4                                          # type: ignore
output_table = "ads_user_kmeans"               # type: ignore

feature_list = [f.strip() for f in features.split(",")]

# 1) 读数据
df = dataset(table_fqn).to_pandas()
print(f"loaded {len(df)} rows from {table_fqn}")

# 2) 特征工程
X = df[feature_list].dropna()
scaler = StandardScaler()
X_scaled = scaler.fit_transform(X)

# 3) KMeans 聚类
model = KMeans(n_clusters=k, random_state=42, n_init=10)
clusters = model.fit_predict(X_scaled)
df.loc[X.index, "cluster"] = clusters

# 4) 输出（带分群标签的新表）
result_df = df[["user_id"] + feature_list + ["cluster"]].copy()
print(f"cluster distribution:\n{result_df['cluster'].value_counts()}")

# 5) 发布为资产
publish(result_df, output_table, classification="L2",
        description=f"KMeans k={k} on {features}")
