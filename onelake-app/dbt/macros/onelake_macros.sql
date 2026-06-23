{# 增量过滤宏：基于水位列增量抽取 - 对应功能清单 L1-1.2.2 #}
{% macro generate_schema_name(custom_schema_name, node) -%}
  {%- if custom_schema_name is none -%}
    {{ target.schema }}
  {%- else -%}
    {{ custom_schema_name | trim }}
  {%- endif -%}
{%- endmacro %}

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

{# 通用质量门禁：数值范围测试。返回违规记录，dbt test 非空即失败。 #}
{% test onelake_range(model, column_name, min_value=none, max_value=none) %}
  select *
  from {{ model }}
  where {{ column_name }} is not null
    {% if min_value is not none and max_value is not none %}
      and ({{ column_name }} < {{ min_value }} or {{ column_name }} > {{ max_value }})
    {% elif min_value is not none %}
      and {{ column_name }} < {{ min_value }}
    {% elif max_value is not none %}
      and {{ column_name }} > {{ max_value }}
    {% else %}
      and false
    {% endif %}
{% endtest %}

{# 通用质量门禁：正则测试。返回未匹配 pattern 的记录。 #}
{% test onelake_regex(model, column_name, pattern) %}
  select *
  from {{ model }}
  where {{ column_name }} is not null
    and not regexp_like(cast({{ column_name }} as varchar), '{{ pattern | replace("'", "''") }}')
{% endtest %}

{# 通用质量门禁：模型行数范围测试。返回违规计数行。 #}
{% test onelake_row_count(model, min_value=none, max_value=none) %}
  with row_count_check as (
    select count(*) as row_count
    from {{ model }}
  )
  select *
  from row_count_check
  where
    {% if min_value is not none and max_value is not none %}
      row_count < {{ min_value }} or row_count > {{ max_value }}
    {% elif min_value is not none %}
      row_count < {{ min_value }}
    {% elif max_value is not none %}
      row_count > {{ max_value }}
    {% else %}
      false
    {% endif %}
{% endtest %}

{# 通用质量门禁：自定义只读 SQL 断言。assertion_sql 必须返回违规记录。 #}
{% test onelake_custom_sql(model, assertion_sql) %}
  {% set rendered_sql = assertion_sql | replace('__ONELAKE_MODEL__', model | string) %}
  {{ rendered_sql }}
{% endtest %}
