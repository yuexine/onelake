/**
 * 通知中心抽屉（对应原型 §1.5 / §8.1.2）。
 * 5 类分类 + 未读/已读 + 批量已读 + 静默。
 */
import { Drawer, Tabs, List, Tag, Typography, Button, Space, Popover, Switch } from 'antd';
import { BellOutlined, CheckOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores/app';
import { NotificationAPI } from '../api';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';
dayjs.extend(relativeTime);
dayjs.locale('zh-cn');

const { Text } = Typography;

const CATEGORIES = [
  { key: 'all', label: '全部' },
  { key: 'TASK', label: '任务' },
  { key: 'APPROVAL', label: '审批' },
  { key: 'ALERT', label: '告警' },
  { key: 'SECURITY', label: '安全' },
  { key: 'SYSTEM', label: '系统' },
];

const LEVEL_COLOR: Record<string, string> = {
  INFO: 'blue', WARN: 'orange', CRITICAL: 'red',
};

export function NotificationCenter() {
  const { notifyOpen, setNotifyOpen, notifications, markAllRead, markRead } = useAppStore();
  const navigate = useNavigate();
  const unread = notifications.filter((n) => !n.isRead).length;

  const handleMarkAllRead = async () => {
    if (unread <= 0) return;
    try {
      await NotificationAPI.markAllRead();
      markAllRead();
    } catch {
      // 通知读取失败不阻塞抽屉关闭，下一轮轮询会恢复真实状态。
    }
  };

  const handleView = async (id: string, link?: string) => {
    try {
      await NotificationAPI.markRead(id);
      markRead(id);
    } catch {
      // 保持跳转可用，真实已读状态由后续刷新纠正。
    }
    if (link) {
      navigate(link);
      setNotifyOpen(false);
    }
  };

  const handleClose = () => {
    setNotifyOpen(false);
    if (unread > 0) {
      void handleMarkAllRead();
    }
  };

  return (
    <Drawer
      open={notifyOpen}
      onClose={handleClose}
      title={<Space><BellOutlined />通知中心{unread > 0 && <Tag color="red">{unread} 未读</Tag>}</Space>}
      width={480}
      extra={
        <Space>
          <Button size="small" icon={<CheckOutlined />} onClick={() => void handleMarkAllRead()}>全部已读</Button>
          <Popover content={<div style={{ width: 200 }}><div style={{ marginBottom: 8 }}>静默规则</div><Space><Text>任务类</Text><Switch size="small" /></Space><br /><Space style={{ marginTop: 8 }}><Text>免打扰 02:00-04:00</Text><Switch size="small" defaultChecked /></Space></div>}>
            <Button size="small">静默设置</Button>
          </Popover>
        </Space>
      }
    >
      <Tabs
        defaultActiveKey="all"
        items={CATEGORIES.map((c) => ({
          key: c.key,
          label: c.label,
          children: (
            <List
              dataSource={c.key === 'all' ? notifications : notifications.filter((n) => n.category === c.key)}
              renderItem={(n) => (
                <List.Item
                  actions={[<a key="view" onClick={() => void handleView(n.id, n.link)}>查看</a>]}
                >
                  <List.Item.Meta
                    avatar={!n.isRead ? <Tag color="red">未读</Tag> : <Tag>已读</Tag>}
                    title={<Space><Tag color={LEVEL_COLOR[n.level || 'INFO']}>{n.level}</Tag><Text strong>{n.title}</Text></Space>}
                    description={<><div>{n.content}</div><Text type="secondary">{dayjs(n.createdAt).fromNow()}</Text></>}
                  />
                </List.Item>
              )}
            />
          ),
        }))}
      />
    </Drawer>
  );
}
