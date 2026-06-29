/**
 * 业务术语表（台账化重构版）。
 */
import {
  App as AntdApp,
  Alert,
  Button,
  Col,
  Descriptions,
  Drawer,
  Dropdown,
  Form,
  Grid,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import type { MenuProps, TabsProps } from "antd";
import {
  ApiOutlined,
  BookOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  EditOutlined,
  LinkOutlined,
  MoreOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SendOutlined,
  StopOutlined,
} from "@ant-design/icons";
import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { CatalogAPI, GlossaryAPI, ModelingAPI } from "../../api";
import { PageHeader, SectionCard, StateView } from "../../components";
import type {
  Asset,
  BusinessTerm,
  BusinessTermBinding,
  BusinessTermImpact,
  BusinessTermRequest,
  BusinessTermVersion,
  BusinessTermVersionDiff,
  SubjectDomain,
} from "../../types";

const { Text, Paragraph } = Typography;
const { useBreakpoint } = Grid;

const STATUS_LABEL: Record<string, string> = {
  DRAFT: "草稿",
  REVIEWING: "待审定",
  APPROVED: "已审定",
  REJECTED: "已退回",
  DEPRECATED: "已废弃",
  ARCHIVED: "已归档",
};

const STATUS_COLOR: Record<string, string> = {
  DRAFT: "default",
  REVIEWING: "processing",
  APPROVED: "success",
  REJECTED: "warning",
  DEPRECATED: "error",
  ARCHIVED: "default",
};

const STATUS_HINT: Record<string, string> = {
  DRAFT: "还未启用，补齐定义和口径后可提交确认",
  REVIEWING: "等待负责人确认，确认后可被其他模块正式继承",
  APPROVED: "已启用，可被目录、建模、质量、安全和 API 使用",
  REJECTED: "已退回，修改后可重新提交",
  DEPRECATED: "已停用，保留历史影响面，避免继续使用",
  ARCHIVED: "已归档，仅用于历史追溯",
};

const SOURCE_LABEL: Record<string, string> = {
  MANUAL: "人工绑定",
  MODELING: "建模继承",
  CATALOG: "目录同步",
  QUALITY: "质量引用",
  DATASERVICE: "API 继承",
  IMPORT: "导入",
  SUGGESTED: "系统建议",
};

const RELATION_LABEL: Record<string, string> = {
  DEFINES: "定义字段",
  USES: "使用字段",
  DERIVES: "派生字段",
};

const SENSITIVITY_LABEL: Record<string, string> = {
  L1: "L1 公开",
  L2: "L2 内部",
  L3: "L3 敏感",
  L4: "L4 机密",
};

const STATUS_FILTERS = [
  { label: "全部状态", value: "ALL" },
  { label: "草稿", value: "DRAFT" },
  { label: "待审定", value: "REVIEWING" },
  { label: "已审定", value: "APPROVED" },
  { label: "已退回", value: "REJECTED" },
  { label: "已废弃", value: "DEPRECATED" },
];

function statusText(status?: string) {
  return STATUS_LABEL[status || ""] || status || "-";
}

function statusColor(status?: string) {
  return STATUS_COLOR[status || ""] || "default";
}

function statusHint(status?: string) {
  return (
    STATUS_HINT[status || ""] || "当前状态会影响术语是否可被其他模块正式引用"
  );
}

function relationText(value?: string) {
  return RELATION_LABEL[value || ""] || value || "-";
}

function sourceText(value?: string) {
  return SOURCE_LABEL[value || ""] || value || "-";
}

function sensitivityText(value?: string) {
  return value ? SENSITIVITY_LABEL[value] || value : "未设密级";
}

function splitTags(value?: string) {
  if (!value) return [];
  return value
    .split(/[,\n，、]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function formatChangeValue(value: unknown) {
  if (value == null || value === "") return "-";
  if (Array.isArray(value)) return value.join("、") || "-";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function formatTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function termBindingCount(item: BusinessTerm) {
  return item.bindingCount ?? item.bindings?.length ?? 0;
}

type TermQueryFilters = {
  keyword?: string;
  statusFilter?: string;
  domainFilter?: string;
};

function SummaryPill({
  label,
  value,
  tone,
}: {
  label: string;
  value: number | string;
  tone?: "brand" | "success" | "warning";
}) {
  const palette =
    tone === "success"
      ? {
          bg: "var(--ol-success-soft)",
          border: "#BBF7D0",
          fg: "var(--ol-success)",
        }
      : tone === "warning"
        ? {
            bg: "var(--ol-warning-soft)",
            border: "#FDE68A",
            fg: "#B45309",
          }
        : {
            bg: "var(--ol-fill-soft)",
            border: "var(--ol-line-soft)",
            fg: "var(--ol-brand)",
          };
  return (
    <div
      style={{
        minWidth: 112,
        padding: "8px 10px",
        borderRadius: 8,
        border: `1px solid ${palette.border}`,
        background: palette.bg,
      }}
    >
      <div style={{ fontSize: 11, color: "var(--ol-ink-3)" }}>{label}</div>
      <div
        style={{
          marginTop: 3,
          fontSize: 18,
          lineHeight: 1.1,
          fontWeight: 700,
          color: palette.fg,
        }}
      >
        {value}
      </div>
    </div>
  );
}

function MiniMetric({
  icon,
  label,
  value,
  tone,
}: {
  icon: ReactNode;
  label: string;
  value: number;
  tone?: "warning";
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        minHeight: 58,
        padding: "10px 12px",
        border: "1px solid var(--ol-line-soft)",
        borderRadius: 8,
        background: tone === "warning" ? "var(--ol-warning-soft)" : "#fff",
      }}
    >
      <span
        style={{
          width: 28,
          height: 28,
          borderRadius: 6,
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
          color: tone === "warning" ? "#B45309" : "var(--ol-brand)",
          background: "var(--ol-fill-soft)",
          flexShrink: 0,
        }}
      >
        {icon}
      </span>
      <div>
        <div style={{ fontSize: 12, color: "var(--ol-ink-3)" }}>{label}</div>
        <div
          style={{
            marginTop: 2,
            fontSize: 18,
            fontWeight: 700,
            lineHeight: 1.1,
            color: "var(--ol-ink)",
          }}
        >
          {value}
        </div>
      </div>
    </div>
  );
}

export default function Glossary() {
  const { message } = AntdApp.useApp();
  const screens = useBreakpoint();
  const compactLayout = !screens.md;

  const [terms, setTerms] = useState<BusinessTerm[]>([]);
  const [selectedTerm, setSelectedTerm] = useState<BusinessTerm>();
  const [impact, setImpact] = useState<BusinessTermImpact>();
  const [versions, setVersions] = useState<BusinessTermVersion[]>([]);
  const [versionDiff, setVersionDiff] = useState<BusinessTermVersionDiff>();
  const [domains, setDomains] = useState<SubjectDomain[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [keyword, setKeyword] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [domainFilter, setDomainFilter] = useState("ALL");
  const [ownerFilter, setOwnerFilter] = useState("ALL");
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [termModalOpen, setTermModalOpen] = useState(false);
  const [bindingModalOpen, setBindingModalOpen] = useState(false);
  const [editingTerm, setEditingTerm] = useState<BusinessTerm>();
  const [saving, setSaving] = useState(false);
  const [selectedAssetFqn, setSelectedAssetFqn] = useState<string>();
  const [termForm] = Form.useForm();
  const [bindingForm] = Form.useForm();

  const activeBindings = (selectedTerm?.bindings || []).filter(
    (item) => item.status === "ACTIVE",
  );
  const canSubmit =
    selectedTerm?.status === "DRAFT" || selectedTerm?.status === "REJECTED";
  const canApprove = selectedTerm?.status === "REVIEWING";
  const canDeprecate =
    !!selectedTerm &&
    selectedTerm.status !== "DEPRECATED" &&
    selectedTerm.status !== "ARCHIVED";

  const loadTermContext = async (id: string, openDrawer = false) => {
    setDetailLoading(true);
    try {
      const [detail, nextImpact, nextVersions, nextDiff] = await Promise.all([
        GlossaryAPI.getTerm(id),
        GlossaryAPI.termImpact(id),
        GlossaryAPI.termVersions(id),
        GlossaryAPI.termVersionDiff(id),
      ]);
      setTerms((current) =>
        current.map((item) => (item.id === detail.id ? detail : item)),
      );
      setSelectedTerm(detail);
      setImpact(nextImpact);
      setVersions(nextVersions);
      setVersionDiff(nextDiff);
      if (openDrawer) setDrawerOpen(true);
    } finally {
      setDetailLoading(false);
    }
  };

  const loadData = async (
    preferredId?: string,
    overrides: TermQueryFilters = {},
  ) => {
    setLoading(true);
    setLoadError(undefined);
    try {
      const nextKeyword = overrides.keyword ?? keyword;
      const nextStatus = overrides.statusFilter ?? statusFilter;
      const nextDomain = overrides.domainFilter ?? domainFilter;
      const params = {
        ...(nextKeyword.trim() ? { keyword: nextKeyword.trim() } : {}),
        ...(nextStatus !== "ALL" ? { status: nextStatus } : {}),
        ...(nextDomain !== "ALL" ? { domainId: nextDomain } : {}),
      };
      const [nextTerms, nextDomains, nextAssets] = await Promise.all([
        GlossaryAPI.listTerms(params),
        ModelingAPI.listDomains(),
        CatalogAPI.listAssets(),
      ]);
      setTerms(nextTerms);
      setDomains(nextDomains);
      setAssets(nextAssets);
      const nextId =
        preferredId ||
        (selectedTerm && nextTerms.some((item) => item.id === selectedTerm.id)
          ? selectedTerm.id
          : nextTerms[0]?.id);
      if (nextId) {
        await loadTermContext(nextId);
      } else {
        setSelectedTerm(undefined);
        setImpact(undefined);
        setVersions([]);
        setVersionDiff(undefined);
        setDrawerOpen(false);
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : "业务术语加载失败";
      setLoadError(msg);
      message.error(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const ownerOptions = useMemo(() => {
    const names = Array.from(
      new Set(terms.map((item) => item.ownerName).filter(Boolean)),
    ) as string[];
    return [
      { label: "全部负责人", value: "ALL" },
      ...names.map((name) => ({ label: name, value: name })),
    ];
  }, [terms]);

  const filteredTerms = useMemo(() => {
    if (ownerFilter === "ALL") return terms;
    return terms.filter((item) => item.ownerName === ownerFilter);
  }, [ownerFilter, terms]);

  const selectedAsset = assets.find((asset) => asset.fqn === selectedAssetFqn);

  const openCreate = () => {
    setEditingTerm(undefined);
    termForm.resetFields();
    termForm.setFieldsValue({ status: "DRAFT" });
    setTermModalOpen(true);
  };

  const openEdit = (term = selectedTerm) => {
    if (!term) return;
    setEditingTerm(term);
    termForm.setFieldsValue({
      code: term.code,
      name: term.name,
      domainId: term.domainId,
      definition: term.definition,
      caliberSql: term.caliberSql,
      synonymsText: term.synonyms?.join("、"),
      ownerName: term.ownerName,
      sensitivityLevel: term.sensitivityLevel,
      tagsText: term.tags?.join("、"),
    });
    setTermModalOpen(true);
  };

  const saveTerm = async () => {
    const values = await termForm.validateFields();
    const payload: BusinessTermRequest = {
      code: values.code,
      name: values.name,
      domainId: values.domainId,
      definition: values.definition,
      caliberSql: values.caliberSql,
      synonyms: splitTags(values.synonymsText),
      ownerName: values.ownerName,
      sensitivityLevel: values.sensitivityLevel,
      tags: splitTags(values.tagsText),
    };
    setSaving(true);
    try {
      const saved = editingTerm
        ? await GlossaryAPI.updateTerm(editingTerm.id, payload)
        : await GlossaryAPI.createTerm(payload);
      message.success(editingTerm ? "术语已更新" : "术语已创建");
      setTermModalOpen(false);
      await loadData(saved.id);
      await loadTermContext(saved.id, true);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "术语保存失败");
    } finally {
      setSaving(false);
    }
  };

  const runAction = async (
    action: "submit" | "approve" | "deprecate" | "reject",
    term = selectedTerm,
  ) => {
    if (!term) return;
    setSaving(true);
    try {
      const next =
        action === "submit"
          ? await GlossaryAPI.submitTerm(term.id)
          : action === "approve"
            ? await GlossaryAPI.approveTerm(term.id)
            : action === "reject"
              ? await GlossaryAPI.rejectTerm(term.id)
              : await GlossaryAPI.deprecateTerm(term.id);
      await loadData(next.id);
      await loadTermContext(next.id, drawerOpen);
      message.success(
        action === "submit"
          ? "已提交启用"
          : action === "approve"
            ? "已确认启用"
            : action === "reject"
              ? "已退回"
              : "已停用",
      );
    } catch (error) {
      message.error(error instanceof Error ? error.message : "操作失败");
    } finally {
      setSaving(false);
    }
  };

  const saveBinding = async () => {
    if (!selectedTerm) return;
    const values = await bindingForm.validateFields();
    const asset = assets.find((item) => item.fqn === values.assetFqn);
    setSaving(true);
    try {
      await GlossaryAPI.bindTerm(selectedTerm.id, {
        assetId: asset?.id,
        assetFqn: values.assetFqn,
        columnName: values.columnName,
        relationType: values.relationType || "DEFINES",
        source: "MANUAL",
      });
      message.success("字段已绑定");
      setBindingModalOpen(false);
      bindingForm.resetFields();
      setSelectedAssetFqn(undefined);
      await loadData(selectedTerm.id);
      await loadTermContext(selectedTerm.id, true);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "字段绑定失败");
    } finally {
      setSaving(false);
    }
  };

  const removeBinding = async (binding: BusinessTermBinding) => {
    if (!selectedTerm) return;
    setSaving(true);
    try {
      await GlossaryAPI.removeBinding(binding.id);
      message.success("字段绑定已移除");
      await loadData(selectedTerm.id);
      await loadTermContext(selectedTerm.id, true);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "移除绑定失败");
    } finally {
      setSaving(false);
    }
  };

  const resetFilters = () => {
    setKeyword("");
    setStatusFilter("ALL");
    setDomainFilter("ALL");
    setOwnerFilter("ALL");
    void loadData(undefined, {
      keyword: "",
      statusFilter: "ALL",
      domainFilter: "ALL",
    });
  };

  const primaryAction = (item: BusinessTerm) => {
    if (item.status === "DRAFT" || item.status === "REJECTED") {
      return (
        <Button
          type="link"
          size="small"
          icon={<SendOutlined />}
          onClick={() => runAction("submit", item)}
        >
          提交启用
        </Button>
      );
    }
    if (item.status === "REVIEWING") {
      return (
        <Button
          type="link"
          size="small"
          icon={<CheckCircleOutlined />}
          onClick={() => runAction("approve", item)}
        >
          确认启用
        </Button>
      );
    }
    if (item.status === "DEPRECATED") {
      return (
        <Button
          type="link"
          size="small"
          onClick={() => loadTermContext(item.id, true)}
        >
          查看影响
        </Button>
      );
    }
    return (
      <Button type="link" size="small" onClick={() => openEdit(item)}>
        修改
      </Button>
    );
  };

  const moreMenu = (item: BusinessTerm): MenuProps => ({
    items: [
      { key: "view", label: "查看详情" },
      { key: "edit", label: "修改术语" },
      { type: "divider" },
      {
        key: "submit",
        label: "提交启用",
        disabled: !(item.status === "DRAFT" || item.status === "REJECTED"),
      },
      {
        key: "approve",
        label: "确认启用",
        disabled: item.status !== "REVIEWING",
      },
      {
        key: "reject",
        label: "退回",
        disabled: item.status !== "REVIEWING",
      },
      {
        key: "deprecate",
        label: "停用术语",
        danger: true,
        disabled: item.status === "DEPRECATED" || item.status === "ARCHIVED",
      },
    ],
    onClick: ({ key }) => {
      if (key === "view") void loadTermContext(item.id, true);
      if (key === "edit") openEdit(item);
      if (key === "submit") void runAction("submit", item);
      if (key === "approve") void runAction("approve", item);
      if (key === "reject") void runAction("reject", item);
      if (key === "deprecate") void runAction("deprecate", item);
    },
  });

  const drawerTabs: TabsProps["items"] = [
    {
      key: "overview",
      label: "概览",
      children: selectedTerm && (
        <Space direction="vertical" size={16} style={{ width: "100%" }}>
          <Descriptions
            column={1}
            size="small"
            styles={{ label: { width: 96, color: "var(--ol-ink-3)" } }}
          >
            <Descriptions.Item label="业务定义">
              <Paragraph style={{ margin: 0 }}>
                {selectedTerm.definition || "未填写定义"}
              </Paragraph>
            </Descriptions.Item>
            <Descriptions.Item label="取值口径">
              {selectedTerm.caliberSql ? (
                <pre
                  style={{
                    margin: 0,
                    padding: 10,
                    borderRadius: 6,
                    border: "1px solid var(--ol-line-soft)",
                    background: "var(--ol-fill-soft)",
                    whiteSpace: "pre-wrap",
                    wordBreak: "break-word",
                    fontSize: 12,
                  }}
                >
                  {selectedTerm.caliberSql}
                </pre>
              ) : (
                "未填写口径"
              )}
            </Descriptions.Item>
            <Descriptions.Item label="负责人">
              {selectedTerm.ownerName || "未指定"}
            </Descriptions.Item>
            <Descriptions.Item label="数据密级">
              <Tag
                color={
                  selectedTerm.sensitivityLevel === "L3" ||
                  selectedTerm.sensitivityLevel === "L4"
                    ? "warning"
                    : "default"
                }
              >
                {sensitivityText(selectedTerm.sensitivityLevel)}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="同义词">
              {(selectedTerm.synonyms || []).length ? (
                <Space wrap size={4}>
                  {selectedTerm.synonyms.map((item) => (
                    <span key={item} className="ol-chip">
                      {item}
                    </span>
                  ))}
                </Space>
              ) : (
                "暂无"
              )}
            </Descriptions.Item>
            <Descriptions.Item label="标签">
              {(selectedTerm.tags || []).length ? (
                <Space wrap size={4}>
                  {selectedTerm.tags.map((item) => (
                    <Tag key={item} style={{ margin: 0 }}>
                      {item}
                    </Tag>
                  ))}
                </Space>
              ) : (
                "暂无"
              )}
            </Descriptions.Item>
          </Descriptions>
        </Space>
      ),
    },
    {
      key: "bindings",
      label: `字段绑定 (${activeBindings.length})`,
      children: (
        <Space direction="vertical" size={12} style={{ width: "100%" }}>
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setBindingModalOpen(true)}
            disabled={!selectedTerm}
          >
            绑定字段
          </Button>
          <Table
            size="small"
            rowKey="id"
            pagination={false}
            scroll={{ x: "max-content" }}
            locale={{ emptyText: "暂无绑定字段" }}
            dataSource={activeBindings}
            columns={[
              {
                title: "字段",
                render: (_: unknown, binding: BusinessTermBinding) => (
                  <Link
                    to={
                      binding.assetId
                        ? `/catalog/assets/${binding.assetId}?tab=schema&column=${binding.columnName || ""}`
                        : `/catalog/search?keyword=${binding.assetFqn}`
                    }
                  >
                    <Text code style={{ fontSize: 12 }}>
                      {binding.columnName
                        ? `${binding.assetFqn}.${binding.columnName}`
                        : binding.assetFqn}
                    </Text>
                  </Link>
                ),
              },
              {
                title: "关系",
                dataIndex: "relationType",
                width: 100,
                render: relationText,
              },
              {
                title: "来源",
                dataIndex: "source",
                width: 100,
                render: sourceText,
              },
              {
                title: "",
                width: 64,
                render: (_: unknown, binding: BusinessTermBinding) => (
                  <Button
                    type="link"
                    size="small"
                    danger
                    onClick={() => removeBinding(binding)}
                  >
                    移除
                  </Button>
                ),
              },
            ]}
          />
        </Space>
      ),
    },
    {
      key: "impact",
      label: "影响范围",
      children: (
        <Space direction="vertical" size={14} style={{ width: "100%" }}>
          {(impact?.warnings || []).length ? (
            <Space direction="vertical" size={6} style={{ width: "100%" }}>
              {impact?.warnings.map((warning) => (
                <Alert
                  key={warning}
                  type="warning"
                  showIcon
                  message={warning}
                />
              ))}
            </Space>
          ) : (
            <Alert type="success" showIcon message="暂无阻断风险" />
          )}
          <Row gutter={[10, 10]}>
            <Col span={12}>
              <MiniMetric
                icon={<DatabaseOutlined />}
                label="下游资产"
                value={impact?.downstreamAssets?.length || 0}
              />
            </Col>
            <Col span={12}>
              <MiniMetric
                icon={<CheckCircleOutlined />}
                label="质量规则"
                value={impact?.qualityRules?.length || 0}
              />
            </Col>
            <Col span={12}>
              <MiniMetric
                icon={<ApiOutlined />}
                label="DaaS API"
                value={impact?.apis?.length || 0}
              />
            </Col>
            <Col span={12}>
              <MiniMetric
                icon={<SafetyCertificateOutlined />}
                label="安全提示"
                value={impact?.securityNotices?.length || 0}
                tone={
                  (impact?.securityNotices?.length || 0) > 0
                    ? "warning"
                    : undefined
                }
              />
            </Col>
          </Row>
          <Tabs
            size="small"
            items={[
              {
                key: "assets",
                label: "资产",
                children: (
                  <Table
                    size="small"
                    rowKey={(row) => `${row.relation}-${row.fqn}`}
                    pagination={false}
                    scroll={{ x: "max-content" }}
                    locale={{ emptyText: "暂无资产影响" }}
                    dataSource={impact?.downstreamAssets || []}
                    columns={[
                      {
                        title: "资产",
                        render: (_: unknown, row) =>
                          row.id ? (
                            <Link to={`/catalog/assets/${row.id}`}>
                              <Text code style={{ fontSize: 12 }}>
                                {row.fqn}
                              </Text>
                            </Link>
                          ) : (
                            <Text code style={{ fontSize: 12 }}>
                              {row.fqn}
                            </Text>
                          ),
                      },
                      {
                        title: "关系",
                        dataIndex: "relation",
                        width: 96,
                        render: (value: string) =>
                          value === "BOUND" ? "已绑定" : "下游",
                      },
                    ]}
                  />
                ),
              },
              {
                key: "quality",
                label: "质量",
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={false}
                    scroll={{ x: "max-content" }}
                    locale={{ emptyText: "暂无质量规则引用" }}
                    dataSource={impact?.qualityRules || []}
                    columns={[
                      { title: "规则", dataIndex: "ruleType", width: 120 },
                      {
                        title: "字段",
                        render: (_: unknown, row) =>
                          row.targetColumn || row.targetFqn || "-",
                      },
                    ]}
                  />
                ),
              },
              {
                key: "api",
                label: "API",
                children: (
                  <Table
                    size="small"
                    rowKey="id"
                    pagination={false}
                    scroll={{ x: "max-content" }}
                    locale={{ emptyText: "暂无 API 引用" }}
                    dataSource={impact?.apis || []}
                    columns={[
                      {
                        title: "API",
                        render: (_: unknown, row) => (
                          <Link to={`/dataservice/apis/${row.id}`}>
                            <Text code style={{ fontSize: 12 }}>
                              {row.apiPath}
                            </Text>
                          </Link>
                        ),
                      },
                      { title: "状态", dataIndex: "status", width: 90 },
                    ]}
                  />
                ),
              },
              {
                key: "security",
                label: "安全",
                children: (
                  <Table
                    size="small"
                    rowKey={(row) => `${row.type}-${row.fqn}`}
                    pagination={false}
                    scroll={{ x: "max-content" }}
                    locale={{ emptyText: "暂无安全提示" }}
                    dataSource={impact?.securityNotices || []}
                    columns={[
                      { title: "提示", dataIndex: "message" },
                      { title: "级别", dataIndex: "level", width: 72 },
                      { title: "状态", dataIndex: "status", width: 90 },
                    ]}
                  />
                ),
              },
            ]}
          />
        </Space>
      ),
    },
    {
      key: "versions",
      label: `版本记录 (${versions.length})`,
      children: (
        <Space direction="vertical" size={12} style={{ width: "100%" }}>
          <Table
            size="small"
            rowKey="id"
            pagination={false}
            locale={{ emptyText: "暂无历史版本" }}
            dataSource={versions}
            columns={[
              {
                title: "版本",
                dataIndex: "version",
                width: 80,
                render: (value: number) => <Tag color="blue">v{value}</Tag>,
              },
              {
                title: "变更说明",
                dataIndex: "changeReason",
                render: (value?: string) => value || "-",
              },
              {
                title: "时间",
                dataIndex: "createdAt",
                width: 120,
                render: formatTime,
              },
            ]}
          />
          <Table
            size="small"
            rowKey="field"
            pagination={false}
            locale={{ emptyText: "暂无最近差异" }}
            dataSource={versionDiff?.changes || []}
            columns={[
              { title: "字段", dataIndex: "field", width: 110 },
              {
                title: "变更前",
                dataIndex: "before",
                render: formatChangeValue,
              },
              {
                title: "变更后",
                dataIndex: "after",
                render: formatChangeValue,
              },
            ]}
          />
        </Space>
      ),
    },
  ];

  return (
    <div className="ol-page">
      <PageHeader
        icon={<BookOutlined />}
        title="业务术语表"
        subtitle={<span className="ol-chip">数据目录 · L3-2</span>}
        description="以台账方式管理标准业务定义、字段绑定和跨模块影响"
        actions={
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建术语
          </Button>
        }
      />

      <SectionCard padded="md">
        <Space direction="vertical" size={12} style={{ width: "100%" }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 12,
              flexWrap: "wrap",
            }}
          >
            <Space wrap size={8}>
              <Input
                allowClear
                prefix={<SearchOutlined style={{ color: "var(--ol-ink-4)" }} />}
                placeholder="搜索名称、编码、定义、同义词"
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                onPressEnter={() => loadData()}
                style={{ width: compactLayout ? 240 : 340 }}
              />
              <Select
                value={statusFilter}
                onChange={setStatusFilter}
                options={STATUS_FILTERS}
                style={{ width: 118 }}
              />
              <Select
                value={domainFilter}
                onChange={setDomainFilter}
                options={[
                  { label: "全部业务域", value: "ALL" },
                  ...domains.map((domain) => ({
                    label: domain.name,
                    value: domain.id,
                  })),
                ]}
                style={{ width: 150 }}
              />
              <Select
                value={ownerFilter}
                onChange={setOwnerFilter}
                options={ownerOptions}
                style={{ width: 140 }}
              />
              <Button icon={<SearchOutlined />} onClick={() => loadData()}>
                查询
              </Button>
              <Button onClick={resetFilters}>重置</Button>
            </Space>
            <Space size={8} wrap>
              <SummaryPill label="全部术语" value={terms.length} />
              <SummaryPill
                label="已审定"
                value={
                  terms.filter((item) => item.status === "APPROVED").length
                }
                tone="success"
              />
              <SummaryPill
                label="待处理"
                value={
                  terms.filter(
                    (item) =>
                      item.status === "DRAFT" || item.status === "REVIEWING",
                  ).length
                }
                tone="warning"
              />
            </Space>
          </div>

          {loading ? (
            <StateView state="loading" rows={7} />
          ) : loadError ? (
            <StateView
              state="error"
              title="业务术语加载失败"
              description={loadError}
              onRetry={() => loadData()}
            />
          ) : (
            <Table
              rowKey="id"
              size="middle"
              dataSource={filteredTerms}
              loading={loading}
              pagination={{ pageSize: 12, showSizeChanger: false }}
              scroll={{ x: 980 }}
              locale={{
                emptyText:
                  terms.length === 0 ? "还没有业务术语" : "没有匹配的业务术语",
              }}
              onRow={(record) => ({
                onClick: () => loadTermContext(record.id, true),
                style: {
                  cursor: "pointer",
                  background:
                    selectedTerm?.id === record.id
                      ? "var(--ol-brand-soft)"
                      : undefined,
                },
              })}
              columns={[
                {
                  title: "术语",
                  width: 270,
                  fixed: compactLayout ? undefined : "left",
                  render: (_: unknown, record: BusinessTerm) => (
                    <Space
                      direction="vertical"
                      size={2}
                      style={{ minWidth: 0 }}
                    >
                      <Text strong style={{ color: "var(--ol-ink)" }}>
                        {record.name}
                      </Text>
                      <Text code style={{ fontSize: 12 }}>
                        {record.code}
                      </Text>
                    </Space>
                  ),
                },
                {
                  title: "状态",
                  dataIndex: "status",
                  width: 105,
                  filters: STATUS_FILTERS.filter(
                    (item) => item.value !== "ALL",
                  ).map((item) => ({
                    text: item.label,
                    value: item.value,
                  })),
                  onFilter: (value, record) => record.status === value,
                  render: (value: string) => (
                    <Tag color={statusColor(value)}>{statusText(value)}</Tag>
                  ),
                },
                {
                  title: "业务域",
                  dataIndex: "domainName",
                  width: 130,
                  render: (value?: string) => value || "未归属",
                },
                {
                  title: "负责人",
                  dataIndex: "ownerName",
                  width: 120,
                  render: (value?: string) => value || "未指定",
                },
                {
                  title: "密级",
                  dataIndex: "sensitivityLevel",
                  width: 110,
                  render: (value?: string) => sensitivityText(value),
                },
                {
                  title: "绑定字段",
                  width: 100,
                  sorter: (a, b) => termBindingCount(a) - termBindingCount(b),
                  render: (_: unknown, record: BusinessTerm) =>
                    termBindingCount(record),
                },
                {
                  title: "更新时间",
                  dataIndex: "updatedAt",
                  width: 130,
                  render: formatTime,
                },
                {
                  title: "操作",
                  width: 180,
                  fixed: compactLayout ? undefined : "right",
                  render: (_: unknown, record: BusinessTerm) => (
                    <Space
                      size={4}
                      onClick={(event) => event.stopPropagation()}
                    >
                      {primaryAction(record)}
                      <Dropdown menu={moreMenu(record)} trigger={["click"]}>
                        <Button
                          type="text"
                          size="small"
                          icon={<MoreOutlined />}
                        />
                      </Dropdown>
                    </Space>
                  ),
                },
              ]}
            />
          )}
        </Space>
      </SectionCard>

      <Drawer
        title={
          selectedTerm ? (
            <Space direction="vertical" size={2}>
              <Space size={8} wrap>
                <Text strong style={{ fontSize: 16 }}>
                  {selectedTerm.name}
                </Text>
                <Tag color={statusColor(selectedTerm.status)}>
                  {statusText(selectedTerm.status)}
                </Tag>
              </Space>
              <Text code style={{ fontSize: 12 }}>
                {selectedTerm.code}
              </Text>
            </Space>
          ) : (
            "术语详情"
          )
        }
        width={compactLayout ? "100%" : 620}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        extra={
          selectedTerm && (
            <Space size={6}>
              <Button
                size="small"
                icon={<EditOutlined />}
                onClick={() => openEdit()}
              >
                修改
              </Button>
              {canSubmit && (
                <Button
                  size="small"
                  icon={<SendOutlined />}
                  onClick={() => runAction("submit")}
                >
                  提交启用
                </Button>
              )}
              {canApprove && (
                <Button
                  size="small"
                  type="primary"
                  icon={<CheckCircleOutlined />}
                  onClick={() => runAction("approve")}
                >
                  确认启用
                </Button>
              )}
              {canDeprecate && (
                <Button
                  size="small"
                  danger
                  icon={<StopOutlined />}
                  onClick={() => runAction("deprecate")}
                >
                  停用
                </Button>
              )}
            </Space>
          )
        }
      >
        {detailLoading && !selectedTerm ? (
          <StateView state="loading" rows={6} />
        ) : selectedTerm ? (
          <Space direction="vertical" size={14} style={{ width: "100%" }}>
            <Alert
              type={selectedTerm.status === "APPROVED" ? "success" : "info"}
              showIcon
              message={statusHint(selectedTerm.status)}
            />
            <Tabs items={drawerTabs} />
          </Space>
        ) : (
          <StateView
            state="empty"
            title="请选择一个术语"
            description="从台账中选择术语后，这里会展示定义、字段绑定、影响范围和版本记录。"
          />
        )}
      </Drawer>

      <Modal
        open={termModalOpen}
        title={editingTerm ? "修改标准术语" : "新建标准术语"}
        onCancel={() => setTermModalOpen(false)}
        onOk={saveTerm}
        okText="保存术语"
        cancelText="取消"
        confirmLoading={saving}
        width={720}
      >
        <Form form={termForm} layout="vertical" requiredMark="optional">
          <Row gutter={12}>
            <Col xs={24} md={8}>
              <Form.Item
                label="术语编码"
                name="code"
                rules={[
                  {
                    required: true,
                    message: "请输入术语编码，例如 CUSTOMER_NAME",
                  },
                ]}
              >
                <Input
                  placeholder="如 CUSTOMER_NAME"
                  disabled={!!editingTerm}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                label="显示名称"
                name="name"
                rules={[
                  { required: true, message: "请输入术语名称，例如 客户姓名" },
                ]}
              >
                <Input placeholder="如 客户姓名" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item label="业务域" name="domainId">
                <Select
                  allowClear
                  placeholder="选择归属业务域"
                  options={domains.map((domain) => ({
                    label: domain.name,
                    value: domain.id,
                  }))}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            label="业务定义"
            name="definition"
            rules={[
              { required: true, message: "请输入这个术语在业务里的准确含义" },
            ]}
          >
            <Input.TextArea
              rows={3}
              placeholder="说明这个概念代表什么，哪些情况算，哪些情况不算"
            />
          </Form.Item>
          <Form.Item
            label="计算或取值口径"
            name="caliberSql"
            rules={[{ required: true, message: "请输入计算或取值口径" }]}
          >
            <Input.TextArea
              rows={3}
              style={{ fontFamily: "monospace" }}
              placeholder="如 trim(full_name) 或 SUM(order.amount) WHERE paid = true"
            />
          </Form.Item>
          <Row gutter={12}>
            <Col xs={24} md={12}>
              <Form.Item label="同义词" name="synonymsText">
                <Input placeholder="多个词用顿号或逗号分隔" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="负责人" name="ownerName">
                <Input placeholder="填写业务负责人或数据负责人" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={24} md={12}>
              <Form.Item label="数据密级建议" name="sensitivityLevel">
                <Select
                  allowClear
                  placeholder="选择该术语通常对应的密级"
                  options={["L1", "L2", "L3", "L4"].map((level) => ({
                    label: SENSITIVITY_LABEL[level],
                    value: level,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="标签" name="tagsText">
                <Input placeholder="如 客户、敏感、主数据" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        open={bindingModalOpen}
        title="绑定到数据字段"
        onCancel={() => setBindingModalOpen(false)}
        onOk={saveBinding}
        okText="保存绑定"
        cancelText="取消"
        confirmLoading={saving}
        width={640}
      >
        <Form form={bindingForm} layout="vertical" requiredMark="optional">
          <Form.Item
            label="数据资产"
            name="assetFqn"
            rules={[{ required: true, message: "请选择要绑定的数据资产" }]}
          >
            <Select
              showSearch
              placeholder="选择一张表或数据资产"
              optionFilterProp="label"
              onChange={(value) => {
                setSelectedAssetFqn(value);
                bindingForm.setFieldValue("columnName", undefined);
              }}
              options={assets.map((asset) => ({
                label: asset.fqn,
                value: asset.fqn,
              }))}
            />
          </Form.Item>
          <Form.Item label="字段（可选）" name="columnName">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="选择字段，不选则绑定整张资产"
              options={(selectedAsset?.columns || []).map((column) => ({
                label: column.name,
                value: column.name,
              }))}
            />
          </Form.Item>
          <Form.Item
            label="绑定关系"
            name="relationType"
            initialValue="DEFINES"
          >
            <Select
              options={[
                { label: "这个字段定义该术语", value: "DEFINES" },
                { label: "这个字段使用该术语", value: "USES" },
                { label: "这个字段由该术语派生", value: "DERIVES" },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
