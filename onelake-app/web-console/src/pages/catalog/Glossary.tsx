/**
 * 业务术语表（对应原型 §8.6.4 升级版）。
 */
import {
  App as AntdApp,
  Alert,
  Button,
  Col,
  Collapse,
  Form,
  Grid,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tree,
  Typography,
} from "antd";
import {
  ApiOutlined,
  ApartmentOutlined,
  BookOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  EditOutlined,
  LinkOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SendOutlined,
  StopOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import type { ReactNode } from "react";
import { useEffect, useMemo, useState } from "react";
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

const { Text } = Typography;
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
  DRAFT: "还未启用，先补齐定义和口径，再提交确认",
  REVIEWING: "正在等待负责人确认，确认后才能被其他模块正式继承",
  APPROVED: "已启用，可被目录、建模、质量规则、安全和 API 使用",
  REJECTED: "已退回，修改后可以重新提交",
  DEPRECATED: "已停用，保留历史影响面，避免继续使用",
  ARCHIVED: "已归档，仅用于历史追溯",
};

const SOURCE_LABEL: Record<string, string> = {
  MANUAL: "人工绑定",
  MODELING: "建模继承",
  CATALOG: "目录同步",
  QUALITY: "质量引用",
  DATASERVICE: "API 继承",
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

function MetricTile({
  icon,
  label,
  value,
  helper,
  tone = "default",
}: {
  icon: ReactNode;
  label: string;
  value: ReactNode;
  helper?: ReactNode;
  tone?: "default" | "success" | "warning";
}) {
  const toneStyle =
    tone === "success"
      ? {
          background: "var(--ol-success-soft)",
          color: "var(--ol-success)",
          borderColor: "#BBF7D0",
        }
      : tone === "warning"
        ? {
            background: "var(--ol-warning-soft)",
            color: "#B45309",
            borderColor: "#FDE68A",
          }
        : {
            background: "var(--ol-fill-soft)",
            color: "var(--ol-brand)",
            borderColor: "var(--ol-line-soft)",
          };
  return (
    <div
      style={{
        display: "flex",
        gap: 10,
        minHeight: 76,
        padding: 12,
        border: `1px solid ${toneStyle.borderColor}`,
        borderRadius: 8,
        background: toneStyle.background,
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
          color: toneStyle.color,
          background: "rgba(255,255,255,0.72)",
          flexShrink: 0,
        }}
      >
        {icon}
      </span>
      <div style={{ minWidth: 0 }}>
        <div
          style={{ fontSize: 12, color: "var(--ol-ink-3)", lineHeight: 1.3 }}
        >
          {label}
        </div>
        <div
          style={{
            marginTop: 3,
            fontSize: 20,
            fontWeight: 700,
            color: "var(--ol-ink)",
            lineHeight: 1.2,
          }}
        >
          {value}
        </div>
        {helper && (
          <div
            style={{
              marginTop: 4,
              fontSize: 12,
              color: "var(--ol-ink-3)",
              lineHeight: 1.35,
            }}
          >
            {helper}
          </div>
        )}
      </div>
    </div>
  );
}

function InfoBlock({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <div>
      <Text style={{ color: "var(--ol-ink-3)", fontSize: 12 }}>{label}</Text>
      <div
        style={{
          marginTop: 5,
          fontSize: 13,
          color: "var(--ol-ink)",
          lineHeight: 1.6,
        }}
      >
        {children || "-"}
      </div>
    </div>
  );
}

export default function Glossary() {
  const { message } = AntdApp.useApp();
  const screens = useBreakpoint();
  const compactLayout = !screens.md;
  const [terms, setTerms] = useState<BusinessTerm[]>([]);
  const [term, setTerm] = useState<BusinessTerm>();
  const [impact, setImpact] = useState<BusinessTermImpact>();
  const [versions, setVersions] = useState<BusinessTermVersion[]>([]);
  const [versionDiff, setVersionDiff] = useState<BusinessTermVersionDiff>();
  const [domains, setDomains] = useState<SubjectDomain[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string>();
  const [termModalOpen, setTermModalOpen] = useState(false);
  const [bindingModalOpen, setBindingModalOpen] = useState(false);
  const [editingTerm, setEditingTerm] = useState<BusinessTerm>();
  const [saving, setSaving] = useState(false);
  const [selectedAssetFqn, setSelectedAssetFqn] = useState<string>();
  const [termForm] = Form.useForm();
  const [bindingForm] = Form.useForm();

  const loadTermContext = async (id: string) => {
    const [detail, nextImpact, nextVersions, nextDiff] = await Promise.all([
      GlossaryAPI.getTerm(id),
      GlossaryAPI.termImpact(id),
      GlossaryAPI.termVersions(id),
      GlossaryAPI.termVersionDiff(id),
    ]);
    setTerms((current) =>
      current.map((item) => (item.id === detail.id ? detail : item)),
    );
    setTerm(detail);
    setImpact(nextImpact);
    setVersions(nextVersions);
    setVersionDiff(nextDiff);
  };

  const loadData = async (preferredId?: string) => {
    setLoading(true);
    setLoadError(undefined);
    try {
      const [nextTerms, nextDomains, nextAssets] = await Promise.all([
        GlossaryAPI.listTerms(keyword ? { keyword } : undefined),
        ModelingAPI.listDomains(),
        CatalogAPI.listAssets(),
      ]);
      setTerms(nextTerms);
      setDomains(nextDomains);
      setAssets(nextAssets);
      const nextId = preferredId || term?.id || nextTerms[0]?.id;
      if (nextId) {
        await loadTermContext(nextId);
      } else {
        setTerm(undefined);
        setImpact(undefined);
        setVersions([]);
        setVersionDiff(undefined);
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

  const treeData = useMemo(() => {
    const byDomain = new Map<string, BusinessTerm[]>();
    terms.forEach((item) => {
      const key = item.domainName || "未归属";
      byDomain.set(key, [...(byDomain.get(key) || []), item]);
    });
    return Array.from(byDomain.entries()).map(([domain, items]) => ({
      title: <Text strong>{domain}</Text>,
      key: `domain:${domain}`,
      children: items.map((item) => ({
        title: compactLayout ? (
          <div style={{ minWidth: 0, padding: "2px 0" }}>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 6,
                minWidth: 0,
                flexWrap: "wrap",
              }}
            >
              <Text
                strong
                className="ol-truncate"
                style={{ fontSize: 13, maxWidth: "100%" }}
              >
                {item.name}
              </Text>
              <Tag
                color={statusColor(item.status)}
                style={{ margin: 0, flexShrink: 0 }}
              >
                {statusText(item.status)}
              </Tag>
            </div>
            <div
              style={{ marginTop: 2, fontSize: 11, color: "var(--ol-ink-3)" }}
            >
              {item.bindingCount ?? item.bindings?.length ?? 0} 个字段
            </div>
          </div>
        ) : (
          <div style={{ minWidth: 0, padding: "2px 0" }}>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 6,
                minWidth: 0,
                flexWrap: "wrap",
              }}
            >
              <Text
                strong
                className="ol-truncate"
                style={{ fontSize: 13, maxWidth: "100%" }}
              >
                {item.name}
              </Text>
              <Tag
                color={statusColor(item.status)}
                style={{ margin: 0, flexShrink: 0 }}
              >
                {statusText(item.status)}
              </Tag>
            </div>
            <div
              style={{
                marginTop: 2,
                display: "flex",
                alignItems: "center",
                gap: 8,
                color: "var(--ol-ink-3)",
                flexWrap: "wrap",
              }}
            >
              <Text
                code
                style={{
                  fontSize: 11,
                  whiteSpace: "normal",
                  wordBreak: "break-all",
                }}
              >
                {item.code}
              </Text>
              <span style={{ fontSize: 11 }}>
                {item.bindingCount ?? item.bindings?.length ?? 0} 个字段
              </span>
            </div>
          </div>
        ),
        key: item.id,
      })),
    }));
  }, [compactLayout, terms]);

  const selectedAsset = assets.find((asset) => asset.fqn === selectedAssetFqn);
  const activeBindings = (term?.bindings || []).filter(
    (item) => item.status === "ACTIVE",
  );
  const warningCount = impact?.warnings?.length || 0;
  const canSubmit = term?.status === "DRAFT" || term?.status === "REJECTED";
  const canApprove = term?.status === "REVIEWING";
  const canDeprecate =
    !!term && term.status !== "DEPRECATED" && term.status !== "ARCHIVED";
  const impactScore = impact?.impactScore ?? 0;
  const linkedCount =
    activeBindings.length +
    (impact?.downstreamAssets?.length || 0) +
    (impact?.qualityRules?.length || 0) +
    (impact?.apis?.length || 0) +
    (impact?.securityNotices?.length || 0);

  const openCreate = () => {
    setEditingTerm(undefined);
    termForm.resetFields();
    termForm.setFieldsValue({ status: "DRAFT" });
    setTermModalOpen(true);
  };

  const openEdit = () => {
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
    } catch (error) {
      message.error(error instanceof Error ? error.message : "术语保存失败");
    } finally {
      setSaving(false);
    }
  };

  const runAction = async (action: "submit" | "approve" | "deprecate") => {
    if (!term) return;
    setSaving(true);
    try {
      const next =
        action === "submit"
          ? await GlossaryAPI.submitTerm(term.id)
          : action === "approve"
            ? await GlossaryAPI.approveTerm(term.id)
            : await GlossaryAPI.deprecateTerm(term.id);
      setTerm(next);
      await loadData(next.id);
      message.success(
        action === "submit"
          ? "已提交审定"
          : action === "approve"
            ? "已审定"
            : "已标记废弃",
      );
    } catch (error) {
      message.error(error instanceof Error ? error.message : "操作失败");
    } finally {
      setSaving(false);
    }
  };

  const saveBinding = async () => {
    if (!term) return;
    const values = await bindingForm.validateFields();
    const asset = assets.find((item) => item.fqn === values.assetFqn);
    setSaving(true);
    try {
      await GlossaryAPI.bindTerm(term.id, {
        assetId: asset?.id,
        assetFqn: values.assetFqn,
        columnName: values.columnName,
        relationType: values.relationType || "DEFINES",
        source: "MANUAL",
      });
      message.success("关联字段已保存");
      setBindingModalOpen(false);
      bindingForm.resetFields();
      setSelectedAssetFqn(undefined);
      const next = await GlossaryAPI.getTerm(term.id);
      setTerm(next);
      await loadData(term.id);
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : "关联字段保存失败",
      );
    } finally {
      setSaving(false);
    }
  };

  const removeBinding = async (binding: BusinessTermBinding) => {
    if (!term) return;
    setSaving(true);
    try {
      await GlossaryAPI.removeBinding(binding.id);
      message.success("关联字段已移除");
      const next = await GlossaryAPI.getTerm(term.id);
      setTerm(next);
      await loadData(term.id);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "移除关联失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<BookOutlined />}
        title="业务术语表"
        subtitle={<span className="ol-chip">数据目录 · L3-2</span>}
        description="把业务概念维护成统一定义，并绑定到真实字段，让目录、建模、质量、安全和 API 使用同一套口径"
        meta={[
          { label: "总术语", value: terms.length },
          {
            label: "已审定",
            value: terms.filter((item) => item.status === "APPROVED").length,
          },
          {
            label: "字段绑定",
            value: terms.reduce(
              (sum, item) =>
                sum + (item.bindingCount ?? item.bindings?.length ?? 0),
              0,
            ),
          },
        ]}
        actions={
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            {compactLayout ? "新建" : "新建标准术语"}
          </Button>
        }
      />

      <SectionCard padded="md">
        <Space.Compact style={{ width: "100%" }}>
          <Input
            prefix={<SearchOutlined style={{ color: "var(--ol-ink-4)" }} />}
            placeholder="搜索名称、编码、定义、口径或同义词"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onPressEnter={() => loadData()}
          />
          <Button icon={<SearchOutlined />} onClick={() => loadData()}>
            {compactLayout ? null : "搜索"}
          </Button>
        </Space.Compact>
      </SectionCard>

      {loading ? (
        <StateView state="loading" rows={6} />
      ) : loadError ? (
        <StateView
          state="error"
          title="业务术语加载失败"
          description={loadError}
          onRetry={() => loadData()}
        />
      ) : terms.length === 0 ? (
        <StateView
          state="empty"
          title="还没有业务术语"
          description="创建一条标准定义后，再绑定到数据字段，相关字段就能在目录、建模、质量、安全和 API 中复用同一口径。"
          cta={
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              {compactLayout ? "新建" : "新建标准术语"}
            </Button>
          }
        />
      ) : (
        <Row gutter={compactLayout ? [0, 12] : [16, 16]}>
          <Col xs={24} xl={7} xxl={6}>
            <SectionCard
              title="选择术语"
              subtitle="按业务域分组，点选后查看定义和联动情况"
              icon={<BookOutlined />}
              padded="sm"
            >
              <Tree
                defaultExpandAll
                selectedKeys={term ? [term.id] : []}
                treeData={treeData}
                onSelect={async (keys) => {
                  const id = String(keys[0] || "");
                  if (!id || id.startsWith("domain:")) return;
                  try {
                    await loadTermContext(id);
                  } catch (error) {
                    message.error(
                      error instanceof Error
                        ? error.message
                        : "术语详情加载失败",
                    );
                  }
                }}
              />
            </SectionCard>
          </Col>
          <Col xs={24} xl={17} xxl={18}>
            {term ? (
              <Space direction="vertical" size={16} style={{ width: "100%" }}>
                <SectionCard
                  title={
                    <Space size={8} wrap>
                      <Text strong style={{ fontSize: 16 }}>
                        {term.name}
                      </Text>
                      <Text code style={{ fontSize: 12 }}>
                        {term.code}
                      </Text>
                      <Tag
                        color={statusColor(term.status)}
                        style={{ margin: 0 }}
                      >
                        {statusText(term.status)}
                      </Tag>
                      <Tag color="blue" style={{ margin: 0 }}>
                        {term.domainName || "未归属业务域"}
                      </Tag>
                      <Tag style={{ margin: 0 }}>v{term.version}</Tag>
                    </Space>
                  }
                  subtitle={statusHint(term.status)}
                  icon={<BookOutlined />}
                  extra={
                    <Space wrap>
                      <Button
                        size="small"
                        icon={<EditOutlined />}
                        onClick={openEdit}
                      >
                        {compactLayout ? "修改" : "修改术语"}
                      </Button>
                      <Button
                        size="small"
                        icon={<SendOutlined />}
                        onClick={() => runAction("submit")}
                        loading={saving}
                        disabled={!canSubmit}
                      >
                        {compactLayout ? "提交" : "提交启用"}
                      </Button>
                      <Button
                        size="small"
                        type="primary"
                        icon={<CheckCircleOutlined />}
                        onClick={() => runAction("approve")}
                        loading={saving}
                        disabled={!canApprove}
                      >
                        {compactLayout ? "启用" : "确认启用"}
                      </Button>
                      <Button
                        size="small"
                        danger
                        icon={<StopOutlined />}
                        onClick={() => runAction("deprecate")}
                        loading={saving}
                        disabled={!canDeprecate}
                      >
                        {compactLayout ? "停用" : "停用术语"}
                      </Button>
                    </Space>
                  }
                >
                  <Row gutter={compactLayout ? [0, 12] : [16, 16]}>
                    <Col xs={24} lg={16}>
                      <Space
                        direction="vertical"
                        size={14}
                        style={{ width: "100%" }}
                      >
                        <InfoBlock label="业务定义">
                          {term.definition || (
                            <Text type="secondary">未填写定义</Text>
                          )}
                        </InfoBlock>
                        <InfoBlock label="计算或取值口径">
                          {term.caliberSql ? (
                            <pre
                              style={{
                                margin: 0,
                                padding: "8px 10px",
                                border: "1px solid var(--ol-line-soft)",
                                borderRadius: 6,
                                background: "var(--ol-fill-soft)",
                                whiteSpace: "pre-wrap",
                                wordBreak: "break-word",
                                fontSize: 12,
                                lineHeight: 1.55,
                              }}
                            >
                              {term.caliberSql}
                            </pre>
                          ) : (
                            <Text type="secondary">未填写口径</Text>
                          )}
                        </InfoBlock>
                        <InfoBlock label="同义词和标签">
                          <Space size={6} wrap>
                            {(term.synonyms || []).map((item) => (
                              <span key={`syn-${item}`} className="ol-chip">
                                {item}
                              </span>
                            ))}
                            {(term.tags || []).map((item) => (
                              <Tag key={`tag-${item}`} style={{ margin: 0 }}>
                                {item}
                              </Tag>
                            ))}
                            {!(term.synonyms || []).length &&
                              !(term.tags || []).length && (
                                <Text type="secondary">暂无</Text>
                              )}
                          </Space>
                        </InfoBlock>
                      </Space>
                    </Col>
                    <Col xs={24} lg={8}>
                      <div
                        style={{
                          height: "100%",
                          padding: 14,
                          border: "1px solid var(--ol-line-soft)",
                          borderRadius: 8,
                          background: "var(--ol-fill-soft)",
                        }}
                      >
                        <Text
                          style={{ color: "var(--ol-ink-3)", fontSize: 12 }}
                        >
                          当前可用状态
                        </Text>
                        <div style={{ marginTop: 8 }}>
                          <Tag
                            color={statusColor(term.status)}
                            style={{ margin: 0 }}
                          >
                            {statusText(term.status)}
                          </Tag>
                        </div>
                        <div
                          style={{
                            marginTop: 10,
                            color: "var(--ol-ink)",
                            fontSize: 13,
                            lineHeight: 1.55,
                          }}
                        >
                          {statusHint(term.status)}
                        </div>
                        <div
                          style={{
                            marginTop: 14,
                            display: "flex",
                            flexWrap: "wrap",
                            gap: 6,
                          }}
                        >
                          <span className="ol-chip">
                            负责人 {term.ownerName || "未指定"}
                          </span>
                          <span className="ol-chip">
                            {term.sensitivityLevel
                              ? SENSITIVITY_LABEL[term.sensitivityLevel] ||
                                term.sensitivityLevel
                              : "未设密级"}
                          </span>
                        </div>
                      </div>
                    </Col>
                  </Row>
                </SectionCard>

                <SectionCard
                  title="绑定到数据字段"
                  subtitle="这些字段会在目录、建模、质量规则和 API 中继承该术语的定义、口径和密级"
                  icon={<LinkOutlined />}
                  extra={
                    <Button
                      size="small"
                      icon={<PlusOutlined />}
                      onClick={() => setBindingModalOpen(true)}
                    >
                      {compactLayout ? "绑定" : "绑定字段"}
                    </Button>
                  }
                >
                  {activeBindings.length === 0 ? (
                    <Alert
                      type="info"
                      showIcon
                      message="暂无绑定字段"
                      description="术语只有绑定到真实字段后，其他模块才能自动识别和复用它。"
                    />
                  ) : (
                    <Table
                      size="small"
                      rowKey="id"
                      pagination={false}
                      scroll={{ x: "max-content" }}
                      dataSource={activeBindings}
                      columns={[
                        {
                          title: "数据字段",
                          render: (
                            _: unknown,
                            binding: BusinessTermBinding,
                          ) => (
                            <Link
                              to={
                                binding.assetId
                                  ? `/catalog/assets/${binding.assetId}?tab=schema&column=${binding.columnName || ""}`
                                  : `/catalog/search?keyword=${binding.assetFqn}`
                              }
                            >
                              <Text
                                code
                                style={{
                                  fontSize: 12,
                                  whiteSpace: "normal",
                                  wordBreak: "break-all",
                                }}
                              >
                                {binding.columnName
                                  ? `${binding.assetFqn}.${binding.columnName}`
                                  : binding.assetFqn}
                              </Text>
                            </Link>
                          ),
                        },
                        {
                          title: "绑定关系",
                          dataIndex: "relationType",
                          width: 120,
                          render: (value: string) => (
                            <Tag>{relationText(value)}</Tag>
                          ),
                        },
                        {
                          title: "来源",
                          dataIndex: "source",
                          width: 120,
                          render: (value: string) => sourceText(value),
                        },
                        {
                          title: "操作",
                          width: 80,
                          render: (
                            _: unknown,
                            binding: BusinessTermBinding,
                          ) => (
                            <Button
                              type="link"
                              danger
                              size="small"
                              onClick={() => removeBinding(binding)}
                            >
                              移除
                            </Button>
                          ),
                        },
                      ]}
                    />
                  )}
                </SectionCard>

                <SectionCard
                  title="联动概览"
                  subtitle="这里汇总该术语已经影响到的模块，改定义或停用前先看风险提示"
                  icon={<ApartmentOutlined />}
                >
                  <Space
                    direction="vertical"
                    size={12}
                    style={{ width: "100%" }}
                  >
                    {warningCount > 0 ? (
                      <Space
                        direction="vertical"
                        size={6}
                        style={{ width: "100%" }}
                      >
                        {impact?.warnings.map((warning) => (
                          <Alert
                            key={warning}
                            type="warning"
                            showIcon
                            message={
                              <span style={{ fontSize: 13 }}>{warning}</span>
                            }
                          />
                        ))}
                      </Space>
                    ) : (
                      <Alert
                        type="success"
                        showIcon
                        message="暂无阻断风险"
                        description="当前没有待处理审批或高风险影响提示。"
                      />
                    )}
                    <Row gutter={compactLayout ? [0, 10] : [10, 10]}>
                      <Col xs={12} md={8} xl={4}>
                        <MetricTile
                          icon={<LinkOutlined />}
                          label="字段"
                          value={activeBindings.length}
                          helper="直接绑定"
                          tone={
                            activeBindings.length > 0 ? "success" : "default"
                          }
                        />
                      </Col>
                      <Col xs={12} md={8} xl={4}>
                        <MetricTile
                          icon={<DatabaseOutlined />}
                          label="下游资产"
                          value={impact?.downstreamAssets?.length || 0}
                          helper="含绑定和血缘"
                        />
                      </Col>
                      <Col xs={12} md={8} xl={4}>
                        <MetricTile
                          icon={<CheckCircleOutlined />}
                          label="质量规则"
                          value={impact?.qualityRules?.length || 0}
                          helper="规则引用"
                        />
                      </Col>
                      <Col xs={12} md={8} xl={4}>
                        <MetricTile
                          icon={<ApiOutlined />}
                          label="API"
                          value={impact?.apis?.length || 0}
                          helper="响应字段继承"
                        />
                      </Col>
                      <Col xs={12} md={8} xl={4}>
                        <MetricTile
                          icon={<SafetyCertificateOutlined />}
                          label="安全"
                          value={impact?.securityNotices?.length || 0}
                          helper="密级和脱敏"
                          tone={
                            (impact?.securityNotices?.length || 0) > 0
                              ? "warning"
                              : "default"
                          }
                        />
                      </Col>
                      <Col xs={12} md={8} xl={4}>
                        <MetricTile
                          icon={<WarningOutlined />}
                          label="影响分"
                          value={impactScore}
                          helper={`${linkedCount} 项联动`}
                          tone={warningCount > 0 ? "warning" : "default"}
                        />
                      </Col>
                    </Row>
                  </Space>
                </SectionCard>

                <SectionCard
                  title="详细影响与版本"
                  subtitle="需要修改口径、停用术语或排查影响时再展开查看"
                  icon={<WarningOutlined />}
                >
                  <Collapse
                    size="small"
                    bordered={false}
                    items={[
                      {
                        key: "assets",
                        label: `关联和下游资产 (${impact?.downstreamAssets?.length || 0})`,
                        children: (
                          <Table
                            size="small"
                            rowKey={(row) => `${row.relation}-${row.fqn}`}
                            pagination={false}
                            scroll={{ x: "max-content" }}
                            locale={{ emptyText: "暂无关联或下游资产" }}
                            dataSource={impact?.downstreamAssets || []}
                            columns={[
                              {
                                title: "资产",
                                render: (_: unknown, row) =>
                                  row.id ? (
                                    <Link to={`/catalog/assets/${row.id}`}>
                                      <Text
                                        code
                                        style={{
                                          fontSize: 12,
                                          whiteSpace: "normal",
                                          wordBreak: "break-all",
                                        }}
                                      >
                                        {row.fqn}
                                      </Text>
                                    </Link>
                                  ) : (
                                    <Text
                                      code
                                      style={{
                                        fontSize: 12,
                                        whiteSpace: "normal",
                                        wordBreak: "break-all",
                                      }}
                                    >
                                      {row.fqn}
                                    </Text>
                                  ),
                              },
                              {
                                title: "名称",
                                dataIndex: "displayName",
                                width: 180,
                                render: (value?: string) => value || "-",
                              },
                              {
                                title: "分层",
                                dataIndex: "layer",
                                width: 90,
                                render: (value?: string) =>
                                  value ? <Tag>{value}</Tag> : "-",
                              },
                              {
                                title: "关系",
                                dataIndex: "relation",
                                width: 110,
                                render: (value: string) => (
                                  <Tag
                                    color={
                                      value === "BOUND" ? "blue" : "purple"
                                    }
                                  >
                                    {value === "BOUND" ? "已绑定" : "下游"}
                                  </Tag>
                                ),
                              },
                            ]}
                          />
                        ),
                      },
                      {
                        key: "quality-api",
                        label: `质量规则和 API (${(impact?.qualityRules?.length || 0) + (impact?.apis?.length || 0)})`,
                        children: (
                          <Row gutter={compactLayout ? [0, 10] : [10, 10]}>
                            <Col xs={24} lg={12}>
                              <Table
                                size="small"
                                rowKey="id"
                                pagination={false}
                                scroll={{ x: "max-content" }}
                                locale={{ emptyText: "暂无质量规则引用" }}
                                dataSource={impact?.qualityRules || []}
                                columns={[
                                  {
                                    title: "质量规则",
                                    render: (_: unknown, row) => (
                                      <Space size={6}>
                                        <Tag
                                          color={
                                            row.enabled ? "green" : "default"
                                          }
                                        >
                                          {row.ruleType}
                                        </Tag>
                                        <Text code style={{ fontSize: 12 }}>
                                          {row.targetColumn || row.targetFqn}
                                        </Text>
                                      </Space>
                                    ),
                                  },
                                  {
                                    title: "级别",
                                    dataIndex: "severity",
                                    width: 80,
                                  },
                                ]}
                              />
                            </Col>
                            <Col xs={24} lg={12}>
                              <Table
                                size="small"
                                rowKey="id"
                                pagination={false}
                                scroll={{ x: "max-content" }}
                                locale={{ emptyText: "暂无 API 引用" }}
                                dataSource={impact?.apis || []}
                                columns={[
                                  {
                                    title: "DaaS API",
                                    render: (_: unknown, row) => (
                                      <Link to={`/dataservice/apis/${row.id}`}>
                                        <Text code style={{ fontSize: 12 }}>
                                          {row.apiPath}
                                        </Text>
                                      </Link>
                                    ),
                                  },
                                  {
                                    title: "状态",
                                    dataIndex: "status",
                                    width: 90,
                                    render: (value: string) => (
                                      <Tag>{value}</Tag>
                                    ),
                                  },
                                ]}
                              />
                            </Col>
                          </Row>
                        ),
                      },
                      {
                        key: "security",
                        label: `安全提示和审批 (${(impact?.securityNotices?.length || 0) + (impact?.approvals?.length || 0)})`,
                        children: (
                          <Row gutter={compactLayout ? [0, 10] : [10, 10]}>
                            <Col xs={24} lg={12}>
                              <Table
                                size="small"
                                rowKey={(row) => `${row.type}-${row.fqn}`}
                                pagination={false}
                                scroll={{ x: "max-content" }}
                                locale={{ emptyText: "暂无安全提示" }}
                                dataSource={impact?.securityNotices || []}
                                columns={[
                                  { title: "安全提示", dataIndex: "message" },
                                  {
                                    title: "级别",
                                    dataIndex: "level",
                                    width: 80,
                                    render: (value?: string) =>
                                      value ? (
                                        <Tag color="error">{value}</Tag>
                                      ) : (
                                        "-"
                                      ),
                                  },
                                  {
                                    title: "状态",
                                    dataIndex: "status",
                                    width: 90,
                                  },
                                ]}
                              />
                            </Col>
                            <Col xs={24} lg={12}>
                              <Table
                                size="small"
                                rowKey="id"
                                pagination={false}
                                scroll={{ x: "max-content" }}
                                locale={{ emptyText: "暂无治理审批" }}
                                dataSource={impact?.approvals || []}
                                columns={[
                                  {
                                    title: "治理审批",
                                    dataIndex: "requestType",
                                  },
                                  {
                                    title: "状态",
                                    dataIndex: "status",
                                    width: 90,
                                    render: (value: string) => (
                                      <Tag
                                        color={
                                          value === "PENDING"
                                            ? "processing"
                                            : "default"
                                        }
                                      >
                                        {value}
                                      </Tag>
                                    ),
                                  },
                                ]}
                              />
                            </Col>
                          </Row>
                        ),
                      },
                      {
                        key: "version",
                        label: `版本和最近变化 (${versions.length})`,
                        children: (
                          <Row gutter={compactLayout ? [0, 10] : [10, 10]}>
                            <Col xs={24} lg={10}>
                              <Table
                                size="small"
                                rowKey="id"
                                pagination={false}
                                scroll={{ x: "max-content" }}
                                locale={{ emptyText: "暂无历史版本" }}
                                dataSource={versions}
                                columns={[
                                  {
                                    title: "版本",
                                    dataIndex: "version",
                                    width: 80,
                                    render: (value: number) => (
                                      <Tag color="blue">v{value}</Tag>
                                    ),
                                  },
                                  {
                                    title: "变更说明",
                                    dataIndex: "changeReason",
                                    render: (value?: string) => value || "-",
                                  },
                                ]}
                              />
                            </Col>
                            <Col xs={24} lg={14}>
                              <Table
                                size="small"
                                rowKey="field"
                                pagination={false}
                                scroll={{ x: "max-content" }}
                                locale={{ emptyText: "暂无最近差异" }}
                                dataSource={versionDiff?.changes || []}
                                columns={[
                                  {
                                    title: "字段",
                                    dataIndex: "field",
                                    width: 120,
                                  },
                                  {
                                    title: "变更前",
                                    dataIndex: "before",
                                    ellipsis: true,
                                    render: formatChangeValue,
                                  },
                                  {
                                    title: "变更后",
                                    dataIndex: "after",
                                    ellipsis: true,
                                    render: formatChangeValue,
                                  },
                                ]}
                              />
                            </Col>
                          </Row>
                        ),
                      },
                    ]}
                  />
                </SectionCard>
              </Space>
            ) : (
              <StateView
                state="empty"
                title="请选择一个业务术语"
                description="左侧选择术语后，这里会展示定义、绑定字段和跨模块联动情况。"
              />
            )}
          </Col>
        </Row>
      )}

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
            <Col span={8}>
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
            <Col span={8}>
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
            <Col span={8}>
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
            <Col span={12}>
              <Form.Item label="同义词" name="synonymsText">
                <Input placeholder="多个词用顿号或逗号分隔" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="负责人" name="ownerName">
                <Input placeholder="填写业务负责人或数据负责人" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
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
            <Col span={12}>
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
