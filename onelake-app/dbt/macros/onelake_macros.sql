{# 增量过滤宏：基于水位列增量抽取 - 对应功能清单 L1-1.2.2 #}
{% macro incremental_filter(watermark_col, lookback_minutes=5) %}
  WHERE {{ watermark_col }} > '{{ var("last_watermark") }}'
    AND {{ watermark_col }} <= now() - interval '{{ lookback_minutes }}' minute
{% endmacro %}

{# 脱敏宏：手机号 138****8888 - 对应 L4-3.1.3 #}
{% macro mask_phone(col) %}
  regexp_replace({{ col }}, '(\\d{3})\\d{4}(\\d{4})', '$1****$2')
{% endmacro %}

{# 身份证脱敏：110101********1234 #}
{% macro mask_id_card(col) %}
  regexp_replace({{ col }}, '(\\d{6})\\d{8}(\\d{4})', '$1********$2')
{% endmacro %}
