"""
Prophet 时序预测模板（参数化）。
预测下一周期 N 天的指标值。
"""
from onelake import dataset, publish
import pandas as pd
from prophet import Prophet

# Parameters
table_fqn = "iceberg.dws.ads_order_gmv_daily"  # type: ignore
date_col = "stat_date"                          # type: ignore
metric_col = "gmv"                              # type: ignore
forecast_days = 30                              # type: ignore
output_table = "ads_forecast_gmv_30d"           # type: ignore

# 1) 读历史数据
df = dataset(table_fqn).to_pandas()
df[date_col] = pd.to_datetime(df[date_col])
prophet_df = df[[date_col, metric_col]].rename(columns={date_col: "ds", metric_col: "y"})
print(f"loaded {len(prophet_df)} rows; range: {prophet_df['ds'].min()} → {prophet_df['ds'].max()}")

# 2) Prophet 训练
m = Prophet(daily_seasonality=True, weekly_seasonality=True, yearly_seasonality=True)
m.fit(prophet_df)

# 3) 预测未来 N 天
future = m.make_future_dataframe(periods=forecast_days)
forecast = m.predict(future)[["ds", "yhat", "yhat_lower", "yhat_upper"]]
forecast["ds"] = forecast["ds"].dt.strftime("%Y-%m-%d")
forecast = forecast.rename(columns={"ds": "stat_date", "yhat": "predicted"})

# 4) 发布
publish(forecast, output_table, classification="L2",
        description=f"Prophet forecast for {metric_col}, next {forecast_days} days")
