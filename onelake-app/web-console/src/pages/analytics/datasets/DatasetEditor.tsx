/**
 * 数据集编辑抽屉（创建/更新）。
 *
 * 来源类型分支：
 * - ASSET：填 asset_fqn（如 iceberg.dwd.dwd_user）+ 可选密级
 * - SQL：填 select_sql（Trino 方言）
 * - API：填 api_id（dataservice 已发布 API）
 * - NOTEBOOK：填 asset_fqn（由 publish() 创建）
 *
 * 表单提交时统一映射到 AnalyticsDatasetRequest。
 */
import { useEffect, useState } from 'react';
import {
  Drawer, Form, Input, Select, InputNumber, Button, Space, message,
} from 'antd';
import { AnalyticsAPI, type AnalyticsDataset, type AnalyticsSourceType } from '../../../api';
import { Nl2SqlAPI } from '../../../api';
import { Nl2SqlWizard } from './Nl2SqlWizard';

const { TextArea } = Input;
const { Option } = Select;

export interface DatasetEditorProps {
  open: boolean;
  dataset?: AnalyticsDataset | null;
  onClose: () => void;
  onSuccess: () => void;
}

export function DatasetEditor({ open, dataset, onClose, onSuccess }: DatasetEditorProps) {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const isEdit = !!dataset;

  useEffect(() => {
    if (open) {
      form.setFieldsValue({
        name: dataset?.name ?? '',
        sourceType: dataset?.sourceType ?? 'ASSET',
        assetFqn: dataset?.assetFqn ?? '',
        selectSql: dataset?.selectSql ?? '',
        apiId: dataset?.apiId ?? '',
        classification: dataset?.classification ?? 'L1',
        cacheTtlSec: dataset?.cacheTtlSec ?? 300,
        rowFilter: dataset?.rowFilter ?? '',
      });
    }
  }, [open, dataset]);

  const sourceType = Form.useWatch('sourceType', form) as AnalyticsSourceType;

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (isEdit && dataset) {
        await AnalyticsAPI.updateDataset(dataset.id, values);
        message.success('数据集已更新');
      } else {
        await AnalyticsAPI.createDataset(values);
        message.success('数据集已创建');
      }
      onSuccess();
    } catch (e) {
      if ((e as any)?.errorFields) return;  // form validation error
      message.error('保存失败：' + (e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Drawer
      title={isEdit ? '编辑数据集' : '新建数据集'}
      open={open}
      onClose={onClose}
      width={560}
      footer={
        <Space style={{ float: 'right' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={handleSubmit}>保存</Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical">
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input placeholder="如：ads_order_gmv_daily" />
        </Form.Item>
        <Form.Item name="sourceType" label="来源类型" rules={[{ required: true }]}>
          <Select>
            <Option value="ASSET">Iceberg 资产（FQN 句柄）</Option>
            <Option value="SQL">自定义 Trino SQL</Option>
            <Option value="API">数据服务 API（PostgREST）</Option>
            <Option value="NOTEBOOK">Notebook 产出</Option>
          </Select>
        </Form.Item>

        {sourceType === 'ASSET' && (
          <Form.Item name="assetFqn" label="资产 FQN" rules={[{ required: true }]}>
            <Input placeholder="iceberg.dwd.dwd_user_codex" />
          </Form.Item>
        )}
        {sourceType === 'SQL' && (
          <>
            <Form.Item label="AI 生成 SQL（NL2SQL）" tooltip="P5-C：自然语言生成 Trino SQL，可手动微调">
              <Nl2SqlWizard
                assetFqn={form.getFieldValue('assetFqn') || ''}
                onApply={(sql) => form.setFieldValue('selectSql', sql)}
              />
            </Form.Item>
            <Form.Item name="selectSql" label="Trino SQL" rules={[{ required: true }]}>
              <TextArea rows={8} placeholder="SELECT stat_date, gmv FROM iceberg.dws.ads_order_gmv_daily" />
            </Form.Item>
          </>
        )}
        {sourceType === 'API' && (
          <Form.Item name="apiId" label="API ID" rules={[{ required: true }]}>
            <Input placeholder="（dataservice 已发布的 API ID）" />
          </Form.Item>
        )}
        {sourceType === 'NOTEBOOK' && (
          <Form.Item name="assetFqn" label="Notebook 产出表 FQN" rules={[{ required: true }]}>
            <Input placeholder="iceberg.dwd.ads_user_rfm_seg（由 publish() 创建）" disabled />
          </Form.Item>
        )}

        <Form.Item name="classification" label="密级（继承自资产）">
          <Select>
            <Option value="L1">L1 公开</Option>
            <Option value="L2">L2 内部</Option>
            <Option value="L3">L3 敏感</Option>
            <Option value="L4">L4 高敏</Option>
          </Select>
        </Form.Item>

        <Form.Item name="cacheTtlSec" label="缓存 TTL（秒）">
          <InputNumber min={0} max={3600} style={{ width: '100%' }} />
        </Form.Item>

        <Form.Item
          name="rowFilter"
          label="行级过滤（注入 WHERE；用于公开分享时此字段必须为空）"
          tooltip="示例：region = '华东'。若需公开分享大屏，确保此处为空。"
        >
          <Input placeholder="（可选）" />
        </Form.Item>
      </Form>
    </Drawer>
  );
}
