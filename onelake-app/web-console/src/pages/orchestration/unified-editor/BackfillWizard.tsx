import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Col,
  DatePicker,
  Descriptions,
  Form,
  InputNumber,
  Modal,
  Row,
  Segmented,
  Space,
  Steps,
  Tag,
  Typography,
} from 'antd';
import type { Dayjs } from 'dayjs';
import { BackfillAPI } from '../../../api';
import { BizError } from '../../../api/http';
import type { Backfill, BackfillGrain } from '../../../types';

const { Text } = Typography;
const MAX_BACKFILL_RUNS = 10_000;

interface BackfillWizardValues {
  rangeStartDate: Dayjs;
  rangeEndDate: Dayjs;
  grain: BackfillGrain;
  maxParallel: number;
}

type OptionalDateRange = [Dayjs | undefined, Dayjs | undefined];

interface BackfillWizardProps {
  dagId: string;
  open: boolean;
  onCancel: () => void;
  onCreated: (backfill: Backfill) => void;
}

const steps = [
  { title: '回填区间' },
  { title: '时间粒度' },
  { title: '派发设置' },
];

const grainLabels: Record<BackfillGrain, string> = {
  DAY: '按天',
  HOUR: '按小时',
  MONTH: '按月',
};

function normalizedUtcRange(dateRange: [Dayjs, Dayjs], grain: BackfillGrain) {
  const [rawStart, rawEnd] = dateRange;
  const start = new Date(Date.UTC(
    rawStart.year(),
    rawStart.month(),
    grain === 'MONTH' ? 1 : rawStart.date(),
    0,
  ));
  const end = new Date(Date.UTC(
    rawEnd.year(),
    rawEnd.month(),
    grain === 'MONTH' ? 1 : rawEnd.date(),
    grain === 'HOUR' ? 23 : 0,
  ));
  return [start, end] as const;
}

function plannedRunCount(dateRange?: OptionalDateRange, grain: BackfillGrain = 'DAY') {
  const rawStart = dateRange?.[0];
  const rawEnd = dateRange?.[1];
  if (!rawStart || !rawEnd) return 0;
  const [start, end] = normalizedUtcRange([rawStart, rawEnd], grain);
  if (grain === 'MONTH') {
    return (end.getUTCFullYear() - start.getUTCFullYear()) * 12
      + end.getUTCMonth() - start.getUTCMonth() + 1;
  }
  const unitMs = grain === 'HOUR' ? 60 * 60 * 1000 : 24 * 60 * 60 * 1000;
  return Math.floor((end.getTime() - start.getTime()) / unitMs) + 1;
}

export function BackfillWizard({ dagId, open, onCancel, onCreated }: BackfillWizardProps) {
  const [form] = Form.useForm<BackfillWizardValues>();
  const [currentStep, setCurrentStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<{ message: string; noPermission: boolean } | null>(null);
  const grain = Form.useWatch('grain', form) ?? 'DAY';
  const maxParallel = Form.useWatch('maxParallel', form) ?? 1;
  const dateRange: OptionalDateRange | undefined = currentStep > 0 ? [
    form.getFieldValue('rangeStartDate') as Dayjs | undefined,
    form.getFieldValue('rangeEndDate') as Dayjs | undefined,
  ] : undefined;
  const total = plannedRunCount(dateRange, grain);

  useEffect(() => {
    if (!open) return;
    setCurrentStep(0);
    setError(null);
    form.resetFields();
  }, [form, open]);

  const handleNext = async () => {
    setError(null);
    if (currentStep === 0) {
      await form.validateFields(['rangeStartDate', 'rangeEndDate']);
      setCurrentStep(1);
      return;
    }
    if (currentStep === 1) {
      await form.validateFields(['grain']);
      if (total > MAX_BACKFILL_RUNS) return;
      setCurrentStep(2);
      return;
    }

    const values = await form.validateFields();
    const [rangeStart, rangeEnd] = normalizedUtcRange(
      [values.rangeStartDate, values.rangeEndDate],
      values.grain,
    );
    setSubmitting(true);
    try {
      const created = await BackfillAPI.create(dagId, {
        rangeStart: rangeStart.toISOString(),
        rangeEnd: rangeEnd.toISOString(),
        grain: values.grain,
        maxParallel: values.maxParallel,
      });
      onCreated(created);
    } catch (requestError) {
      const code = requestError instanceof BizError ? requestError.code : undefined;
      setError({
        message: requestError instanceof Error ? requestError.message : '回填创建失败，请稍后重试',
        noPermission: code === 403 || code === 40300,
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="创建回填"
      width={620}
      destroyOnHidden
      maskClosable={!submitting}
      closable={!submitting}
      onCancel={onCancel}
      footer={(
        <Space>
          <Button onClick={onCancel} disabled={submitting}>取消</Button>
          {currentStep > 0 && (
            <Button onClick={() => setCurrentStep((step) => step - 1)} disabled={submitting}>上一步</Button>
          )}
          <Button
            type="primary"
            onClick={handleNext}
            loading={submitting}
            disabled={currentStep === 1 && total > MAX_BACKFILL_RUNS}
          >
            {currentStep === steps.length - 1 ? '创建并启动' : '下一步'}
          </Button>
        </Space>
      )}
    >
      <Steps current={currentStep} items={steps} size="small" style={{ marginBottom: 24 }} />

      {error && (
        <Alert
          type={error.noPermission ? 'warning' : 'error'}
          showIcon
          message={error.noPermission ? '无权创建回填' : '回填创建失败'}
          description={error.message}
          style={{ marginBottom: 16 }}
        />
      )}

      <Form
        form={form}
        layout="vertical"
        initialValues={{ grain: 'DAY' as BackfillGrain, maxParallel: 1 }}
        preserve
      >
        <div style={{ display: currentStep === 0 ? 'block' : 'none' }}>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item
                name="rangeStartDate"
                label="开始日期"
                rules={[{ required: true, message: '请选择开始日期' }]}
              >
                <DatePicker allowClear style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item
                name="rangeEndDate"
                label="结束日期"
                dependencies={['rangeStartDate']}
                rules={[
                  { required: true, message: '请选择结束日期' },
                  ({ getFieldValue }) => ({
                    validator(_, value?: Dayjs) {
                      const start = getFieldValue('rangeStartDate') as Dayjs | undefined;
                      if (!start || !value || !value.isBefore(start, 'day')) return Promise.resolve();
                      return Promise.reject(new Error('结束日期不能早于开始日期'));
                    },
                  }),
                ]}
              >
                <DatePicker allowClear style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </div>

        <div style={{ display: currentStep === 1 ? 'block' : 'none' }}>
          <Form.Item name="grain" label="回填粒度" rules={[{ required: true }]}>
            <Segmented
              block
              options={([
                { label: '按天', value: 'DAY' },
                { label: '按小时', value: 'HOUR' },
                { label: '按月', value: 'MONTH' },
              ] satisfies Array<{ label: string; value: BackfillGrain }>)}
            />
          </Form.Item>
          <Alert
            type={total > MAX_BACKFILL_RUNS ? 'error' : 'info'}
            showIcon
            message={total > MAX_BACKFILL_RUNS
              ? `当前区间将展开为 ${total.toLocaleString()} 个子 Run，超过 10,000 上限`
              : `当前区间将展开为 ${total.toLocaleString()} 个子 Run`}
          />
        </div>

        <div style={{ display: currentStep === 2 ? 'block' : 'none' }}>
          <Form.Item
            name="maxParallel"
            label="最大并发"
            rules={[{ required: true, type: 'number', min: 1, message: '并发数必须大于 0' }]}
          >
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>

          <Descriptions size="small" bordered column={1}>
            <Descriptions.Item label="日期范围">
              {dateRange?.[0]?.format('YYYY-MM-DD')} 至 {dateRange?.[1]?.format('YYYY-MM-DD')}
            </Descriptions.Item>
            <Descriptions.Item label="粒度">
              <Tag color="blue" style={{ margin: 0 }}>{grainLabels[grain]}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="子 Run 数">{total.toLocaleString()}</Descriptions.Item>
            <Descriptions.Item label="最大并发">{maxParallel}</Descriptions.Item>
          </Descriptions>
          <Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
            创建后立即进入派发队列，可在进度页取消尚未完成的回填。
          </Text>
        </div>
      </Form>
    </Modal>
  );
}
