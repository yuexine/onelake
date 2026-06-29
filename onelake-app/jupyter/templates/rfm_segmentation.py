"""
RFM 客户分群模板（参数化）。
基于最近购买时间(R)、购买频率(F)、消费金额(M) 三维分群。
"""
from onelake import dataset, publish
import pandas as pd

# Parameters
table_fqn = "iceberg.dwd.dwd_order"  # type: ignore
user_col = "user_id"                 # type: ignore
amount_col = "amount"                # type: ignore
ts_col = "created_at"                # type: ignore
r_bins = 5                           # type: ignore
f_bins = 5                           # type: ignore
m_bins = 5                           # type: ignore
output_table = "ads_user_rfm_seg"    # type: ignore

df = dataset(table_fqn).to_pandas()
df[ts_col] = pd.to_datetime(df[ts_col])
reference_date = df[ts_col].max() + pd.Timedelta(days=1)

# 1) 计算 R/F/M
rfm = df.groupby(user_col).agg(
    recency=(ts_col, lambda x: (reference_date - x.max()).days),
    frequency=(user_col, "count"),
    monetary=(amount_col, "sum"),
).reset_index()

# 2) 等频分箱打分（1-5）
rfm["r_score"] = pd.qcut(rfm["recency"], r_bins, labels=range(r_bins, 0, -1))
rfm["f_score"] = pd.qcut(rfm["frequency"].rank(method="first"), f_bins, labels=range(1, f_bins + 1))
rfm["m_score"] = pd.qcut(rfm["monetary"], m_bins, labels=range(1, m_bins + 1))

# 3) 拼成 RFM cell（如 "545"）+ 分类标签
rfm["rfm_cell"] = rfm["r_score"].astype(str) + rfm["f_score"].astype(str) + rfm["m_score"].astype(str)
def label_segment(row):
    r, f, m = int(row["r_score"]), int(row["f_score"]), int(row["m_score"])
    if r >= 4 and f >= 4 and m >= 4: return "Champions"
    if r >= 4 and f >= 4: return "Loyal"
    if r >= 4 and m >= 4: return "Big Spenders"
    if r >= 4: return "Recent"
    if f >= 4 and m >= 4: return "At Risk (top)"
    if f >= 4: return "Can't Lose Them"
    if m >= 4: return "Lost High-Value"
    return "Lost"
rfm["segment"] = rfm.apply(label_segment, axis=1)

print(rfm["segment"].value_counts())

# 4) 发布
publish(rfm, output_table, classification="L2",
        description=f"RFM segmentation with R={r_bins} F={f_bins} M={m_bins} bins")
