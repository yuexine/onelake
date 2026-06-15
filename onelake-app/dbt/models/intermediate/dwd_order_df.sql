-- =========================================================
-- DWD 明细层: 订单明细宽表 (来自 ODS orders)
-- =========================================================
{{ config(materialized='view', schema='dwd') }}

SELECT
  order_id,
  user_id,
  CAST(amount AS DECIMAL(18,2))  AS amount,
  status,
  CAST(order_time AS TIMESTAMP)  AS order_time,
  CAST(order_time AS DATE)       AS stat_date
FROM {{ source('ods', 'orders') }}
WHERE status IS NOT NULL
