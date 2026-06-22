{{ config(materialized='table', schema='dwd') }}

select
  order_id as order_id,
  user_id as user_id,
  amount as amount,
  status as status,
  order_time as order_time,
  updated_at as updated_at
from {{ source('ods', 'ods_codex_orders') }}
