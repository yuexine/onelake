/**
 * 大屏发布对话框（P3）。
 *
 * 关键：
 * - isPublic=true 时校验所有数据集 row_filter 为空（后端硬校验，前端给提前提示）
 * - 生成 shareToken 后显示链接 + 复制按钮
 */
import { useEffect, useState } from 'react';
import { Modal, Form, Switch, DatePicker, Alert, Input, Button, message, Space, Tag } from 'antd';
import { CopyOutlined, LinkOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { ScreenSpec } from './types';
import { AnalyticsAPI } from '../../../api';
import type { AnalyticsPublication } from '../../../api';

const { RangePicker } = DatePicker;

export interface PublishDialogProps {
  open: boolean;
  dashboardId?: string;
  spec: ScreenSpec;
  onClose: () => void;
  onPublished?: (pub: AnalyticsPublication) => void;
}

export function PublishDialog({ open, dashboardId, spec, onClose, onPublished }: PublishDialogProps) {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<AnalyticsPublication | null>(null);

  useEffect(() => {
    if (open) {
      form.resetFields();
      form.setFieldsValue({ isPublic: false });
      setResult(null);
    }
  }, [open]);

  // 预检查：是否有带 row_filter 的数据集（导致 isPublic 失败）
  const hasRowFilterDatasets = spec.widgets.some((w) => w.data?.datasetId);

  const handleSubmit = async () => {
    if (!dashboardId) return;
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const expireAt = values.expireAt ? (values.expireAt as dayjs.Dayjs).toISOString() : undefined;
      const pub = await AnalyticsAPI.publishDashboard(dashboardId, !!values.isPublic, expireAt);
      setResult(pub);
      message.success(`已发布 v${pub.version}`);
      onPublished?.(pub);
    } catch (e) {
      if ((e as any)?.errorFields) return;
      message.error('发布失败：' + (e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const shareUrl = result?.shareToken
    ? `${window.location.origin}/share/screen/${result.shareToken}`
    : '';

  const copyLink = () => {
    navigator.clipboard.writeText(shareUrl).then(() => message.success('已复制'));
  };

  return (
    <Modal
      title="发布大屏"
      open={open}
      onCancel={onClose}
      width={560}
      footer={
        result ? (
          <Button type="primary" onClick={onClose}>完成</Button>
        ) : (
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" loading={submitting} onClick={handleSubmit}>发布</Button>
          </Space>
        )
      }
    >
      {result ? (
        <div>
          <Alert
            type="success"
            showIcon
            message={`已发布 v${result.version}`}
            description={result.isPublic ? '已生成公开分享链接，可发送给无登录身份的用户。' : '已发布为内部版本，仅 OneLake 登录用户可见。'}
            style={{ marginBottom: 16 }}
          />
          {result.isPublic && result.shareToken && (
            <>
              <div style={{ marginBottom: 8 }}>
                <strong>分享链接：</strong>
                <Tag color="green">公开</Tag>
                {result.expireAt && <Tag color="orange">过期 {dayjs(result.expireAt).format('YYYY-MM-DD HH:mm')}</Tag>}
              </div>
              <Input.Group compact>
                <Input
                  style={{ width: 'calc(100% - 88px)' }}
                  value={shareUrl}
                  prefix={<LinkOutlined />}
                  readOnly
                />
                <Button type="primary" icon={<CopyOutlined />} onClick={copyLink} style={{ width: 88 }}>
                  复制
                </Button>
              </Input.Group>
            </>
          )}
        </div>
      ) : (
        <Form form={form} layout="vertical">
          <Form.Item name="isPublic" label="开启公开分享（无登录可访问）" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="expireAt" label="链接过期时间（可选）">
            <DatePicker
              showTime
              style={{ width: '100%' }}
              disabledDate={(d) => d && d.isBefore(dayjs())}
            />
          </Form.Item>
          <Form.Item shouldUpdate={(p) => p.isPublic}>
            {({ getFieldValue }) =>
              getFieldValue('isPublic') ? (
                <Alert
                  type="warning"
                  showIcon
                  message="公开分享的安全约束"
                  description="所有绑定数据集必须 row_filter 为空（已聚合或公共指标）。后端会再次硬校验，违规会被拒绝发布。"
                />
              ) : (
                <Alert
                  type="info"
                  showIcon
                  message="内部发布"
                  description="已发布的大屏对 OneLake 登录用户可见（按 ROLE 控制）。"
                />
              )
            }
          </Form.Item>
        </Form>
      )}
    </Modal>
  );
}
