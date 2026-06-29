/**
 * NL2SQL 向导组件（P5-C）。
 *
 * 用户输入自然语言问题 → 调后端 /api/v1/analytics/nl2sql → 拿到 SQL → 一键应用到 Trino SQL 输入框。
 *
 * 后端默认走 OpenAI（gpt-4o-mini）；key 未配置时返回 50020，前端给友好提示。
 */
import { useState } from 'react';
import { Input, Button, Space, Alert, message } from 'antd';
import { RobotOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { Nl2SqlAPI } from '../../../api';

const { TextArea } = Input;

export interface Nl2SqlWizardProps {
  assetFqn: string;
  onApply: (sql: string) => void;
}

export function Nl2SqlWizard({ assetFqn, onApply }: Nl2SqlWizardProps) {
  const [question, setQuestion] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleGenerate = async () => {
    if (!question.trim()) {
      message.warning('请输入自然语言问题');
      return;
    }
    if (!assetFqn) {
      message.warning('请先填写资产 FQN（NL2SQL 需要目标表）');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const { sql } = await Nl2SqlAPI.generate({
        asset_fqn: assetFqn,
        question: question.trim(),
      });
      setResult(sql);
      message.success('SQL 已生成');
    } catch (e) {
      const msg = (e as Error).message;
      setError(msg);
      // code 50020 = LLM 不可用
      if (msg.includes('50020') || msg.includes('OPENAI_API_KEY')) {
        setError('LLM 不可用。请联系管理员配置 OPENAI_API_KEY 环境变量。');
      }
    } finally {
      setLoading(false);
    }
  };

  const handleApply = () => {
    if (result) {
      onApply(result);
      message.success('已应用到 SQL 编辑器');
    }
  };

  return (
    <div style={{ padding: 8, background: 'rgba(31,111,235,0.04)', border: '1px dashed #1f6feb', borderRadius: 4 }}>
      <Space direction="vertical" style={{ width: '100%' }} size="small">
        <Alert
          type="info"
          showIcon
          icon={<RobotOutlined />}
          message="用自然语言描述你要查询的内容"
          description="例如：华东最近 30 天每天的 GMV 趋势"
          style={{ padding: '4px 12px' }}
        />
        <TextArea
          rows={2}
          placeholder="例如：华东最近 30 天 GMV 趋势"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
        />
        <Space>
          <Button
            type="primary"
            icon={<ThunderboltOutlined />}
            loading={loading}
            onClick={handleGenerate}
          >
            生成 SQL
          </Button>
          {result && (
            <Button type="link" onClick={handleApply}>
              应用到 SQL 编辑器
            </Button>
          )}
        </Space>

        {error && (
          <Alert type="error" showIcon message="生成失败" description={error} style={{ padding: '4px 12px' }} />
        )}
        {result && (
          <pre style={{
            background: '#f5f5f5', padding: 8, margin: 0,
            fontSize: 12, fontFamily: 'monospace', maxHeight: 200, overflow: 'auto',
          }}>
            {result}
          </pre>
        )}
      </Space>
    </div>
  );
}
