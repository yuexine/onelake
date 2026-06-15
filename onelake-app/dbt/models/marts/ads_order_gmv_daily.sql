-- =========================================================
-- ADS 应用层: 每日订单 GMV 指标（对应《技术初始化文档》§6.8）
-- =========================================================
{{ config(materialized='table', schema='ads', file_format='iceberg') }}

SELECT
  CAST(order_time AS DATE)      AS stat_date,
  SUM(amount)                   AS gmv,
  COUNT(DISTINCT order_id)      AS order_cnt,
  COUNT(DISTINCT user_id)       AS uv
FROM {{ ref('dwd_order_df') }}
WHERE status = 'PAID'
GROUP BY CAST(order_time AS DATE)
