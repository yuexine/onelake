package com.onelake.catalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.catalog.domain.entity.Asset;
import com.onelake.catalog.domain.entity.LineageEdge;
import com.onelake.catalog.dto.ImpactReportDTO;
import com.onelake.catalog.dto.LineageGraphDTO;
import com.onelake.catalog.dto.LineageGraphDTO.Column;
import com.onelake.catalog.dto.LineageGraphDTO.ColumnEdge;
import com.onelake.catalog.dto.LineageGraphDTO.Edge;
import com.onelake.catalog.dto.LineageGraphDTO.Node;
import com.onelake.catalog.repository.AssetConsumerRepository;
import com.onelake.catalog.repository.AssetRepository;
import com.onelake.catalog.repository.LineageEdgeRepository;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 血缘图查询 + 影响分析服务（对应《血缘图模块完善设计方案》§5.1.3 / §5.2.2）。
 *
 * <p>核心要点：
 * <ul>
 *   <li>双向 BFS + depth 限制（默认 3）</li>
 *   <li>节点元数据 / 边批量查询，避免 N+1</li>
 *   <li>影响分析只查 catalog.asset_consumer 投影表，零跨 schema 读</li>
 *   <li>severity 多维度加权，暴露 severityReasons 供前端展示</li>
 * </ul>
 */
@Service
public class CatalogLineageService {

    private static final int DEFAULT_DEPTH_FALLBACK = 3;
    private static final int SUBSCRIPTION_HIGH_THRESHOLD_FALLBACK = 10;
    private static final int JOBS_MEDIUM_THRESHOLD_FALLBACK = 5;

    private final AssetRepository assetRepo;
    private final LineageEdgeRepository lineageRepo;
    private final AssetConsumerRepository consumerRepo;
    private final int defaultDepth;
    private final int subscriptionHighThreshold;
    private final int jobsMediumThreshold;

    public CatalogLineageService(
        AssetRepository assetRepo,
        LineageEdgeRepository lineageRepo,
        AssetConsumerRepository consumerRepo,
        @org.springframework.beans.factory.annotation.Value("${onelake.catalog.lineage.default-depth:3}") int defaultDepth,
        @org.springframework.beans.factory.annotation.Value("${onelake.catalog.lineage.impact.subscription-high-threshold:10}") int subscriptionHighThreshold,
        @org.springframework.beans.factory.annotation.Value("${onelake.catalog.lineage.impact.jobs-medium-threshold:5}") int jobsMediumThreshold
    ) {
        this.assetRepo = assetRepo;
        this.lineageRepo = lineageRepo;
        this.consumerRepo = consumerRepo;
        this.defaultDepth = defaultDepth > 0 ? defaultDepth : DEFAULT_DEPTH_FALLBACK;
        this.subscriptionHighThreshold = subscriptionHighThreshold > 0 ? subscriptionHighThreshold : SUBSCRIPTION_HIGH_THRESHOLD_FALLBACK;
        this.jobsMediumThreshold = jobsMediumThreshold > 0 ? jobsMediumThreshold : JOBS_MEDIUM_THRESHOLD_FALLBACK;
    }

    @Transactional(readOnly = true)
    public LineageGraphDTO graph(UUID tenantId, String rootFqn, String direction, Integer depth) {
        if (tenantId == null) throw new BizException(40100, "缺少租户上下文");
        if (rootFqn == null || rootFqn.isBlank()) throw new BizException(40000, "缺少 fqn 参数");

        Direction dir = Direction.parse(direction);
        int maxDepth = depth == null || depth <= 0 ? defaultDepth : Math.min(depth, 6);

        Set<String> visited = new LinkedHashSet<>();
        visited.add(rootFqn);
        List<LineageEdge> collectedEdges = new ArrayList<>();

        if (dir != Direction.UP) {
            bfs(tenantId, rootFqn, maxDepth, true, visited, collectedEdges);
        }
        if (dir != Direction.DOWN) {
            bfs(tenantId, rootFqn, maxDepth, false, visited, collectedEdges);
        }

        List<Node> nodes = buildNodes(tenantId, visited);
        List<Edge> edges = collectedEdges.stream().map(this::toEdge).toList();
        return new LineageGraphDTO(rootFqn, nodes, edges);
    }

    @Transactional(readOnly = true)
    public ImpactReportDTO impact(UUID tenantId, String rootFqn) {
        if (tenantId == null) throw new BizException(40100, "缺少租户上下文");
        if (rootFqn == null || rootFqn.isBlank()) throw new BizException(40000, "缺少 fqn 参数");

        Set<String> direct = new LinkedHashSet<>();
        directDownstream(tenantId, rootFqn, direct);

        Set<String> all = new LinkedHashSet<>();
        all.add(rootFqn);
        bfs(tenantId, rootFqn, defaultDepth, true, all, new ArrayList<>());

        List<String> directList = new ArrayList<>(direct);
        List<String> indirectList = all.stream()
            .filter(f -> !rootFqn.equals(f) && !direct.contains(f))
            .toList();

        int apis = (int) consumerRepo.countActiveByTypeAndFqnIn(tenantId, "API", all);
        int subs = (int) consumerRepo.countActiveByTypeAndFqnIn(tenantId, "SUBSCRIPTION", all);
        int jobs = (int) consumerRepo.countActiveByTypeAndFqnIn(tenantId, "JOB", all);
        boolean touchesDwsAds = all.stream().anyMatch(CatalogLineageService::isDwsOrAds);

        return new ImpactReportDTO(
            rootFqn,
            directList,
            indirectList,
            jobs,
            apis,
            subs,
            severityOf(apis, subs, jobs, touchesDwsAds),
            severityReasonsOf(apis, subs, jobs, touchesDwsAds)
        );
    }

    /**
     * 导出影响报告 CSV（对应方案 §5.2.3）。
     * 列：root_fqn, asset_fqn, layer, classification, owner_name,
     *     consumer_type, consumer_name, consumer_owner, severity
     */
    @Transactional(readOnly = true)
    public String exportImpactCsv(UUID tenantId, String rootFqn) {
        if (tenantId == null) throw new BizException(40100, "缺少租户上下文");
        if (rootFqn == null || rootFqn.isBlank()) throw new BizException(40000, "缺少 fqn 参数");

        Set<String> all = new LinkedHashSet<>();
        all.add(rootFqn);
        bfs(tenantId, rootFqn, defaultDepth, true, all, new ArrayList<>());

        // 资产元数据
        List<Asset> assets = assetRepo.findByTenantIdAndOmFqnIn(tenantId, all);
        Map<String, Asset> assetByFqn = new LinkedHashMap<>();
        for (Asset a : assets) assetByFqn.put(a.getOmFqn(), a);

        // 三类 consumer
        List<com.onelake.catalog.domain.entity.AssetConsumer> apiConsumers =
            consumerRepo.findActiveByTypeAndFqnIn(tenantId, "API", all);
        List<com.onelake.catalog.domain.entity.AssetConsumer> subConsumers =
            consumerRepo.findActiveByTypeAndFqnIn(tenantId, "SUBSCRIPTION", all);
        List<com.onelake.catalog.domain.entity.AssetConsumer> jobConsumers =
            consumerRepo.findActiveByTypeAndFqnIn(tenantId, "JOB", all);

        boolean touchesDwsAds = all.stream().anyMatch(CatalogLineageService::isDwsOrAds);
        String severity = severityOf(
            apiConsumers.size(), subConsumers.size(), jobConsumers.size(), touchesDwsAds);

        StringBuilder sb = new StringBuilder();
        sb.append("root_fqn,asset_fqn,layer,classification,owner_name,")
          .append("consumer_type,consumer_name,consumer_owner,severity\n");
        appendRows(sb, rootFqn, assetByFqn, apiConsumers, "API", severity);
        appendRows(sb, rootFqn, assetByFqn, subConsumers, "SUBSCRIPTION", severity);
        appendRows(sb, rootFqn, assetByFqn, jobConsumers, "JOB", severity);
        return sb.toString();
    }

    private void appendRows(StringBuilder sb, String rootFqn, Map<String, Asset> assetByFqn,
                            List<com.onelake.catalog.domain.entity.AssetConsumer> consumers,
                            String consumerType, String severity) {
        for (com.onelake.catalog.domain.entity.AssetConsumer c : consumers) {
            Asset a = assetByFqn.get(c.getAssetFqn());
            String layer = a != null && a.getLayer() != null ? a.getLayer()
                : inferLayerFromFqn(c.getAssetFqn());
            String classification = a != null ? a.getClassification() : "";
            String owner = a != null && a.getOwnerName() != null ? a.getOwnerName() : "";
            sb.append(csv(rootFqn)).append(',')
              .append(csv(c.getAssetFqn())).append(',')
              .append(csv(layer)).append(',')
              .append(csv(classification)).append(',')
              .append(csv(owner)).append(',')
              .append(csv(consumerType)).append(',')
              .append(csv(c.getConsumerName() == null ? c.getConsumerRef() : c.getConsumerName())).append(',')
              .append(csv(c.getOwnerName() == null ? "" : c.getOwnerName())).append(',')
              .append(csv(severity)).append('\n');
        }
    }

    private static String inferLayerFromFqn(String fqn) {
        if (fqn == null) return "";
        String upper = fqn.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ODS")) return "ODS";
        if (upper.startsWith("DWD")) return "DWD";
        if (upper.startsWith("DWS")) return "DWS";
        if (upper.startsWith("ADS")) return "ADS";
        if (upper.startsWith("API")) return "API";
        return "SOURCE";
    }

    /** RFC 4180 minimal escape：含 ",\n,\r 时整体加双引号并把内部双引号翻倍。 */
    private static String csv(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\n") || s.contains("\r") || s.contains("\"")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ---- BFS ----

    private enum Direction { UP, DOWN, BOTH;
        static Direction parse(String raw) {
            if (raw == null) return BOTH;
            return switch (raw.toUpperCase(Locale.ROOT)) {
                case "UP" -> UP;
                case "DOWN" -> DOWN;
                default -> BOTH;
            };
        }
    }

    /**
     * @param downstream true 走下游（沿 upstream→downstream），false 走上游
     */
    private void bfs(UUID tenantId, String root, int maxDepth, boolean downstream,
                     Set<String> visited, List<LineageEdge> collectedEdges) {
        ArrayDeque<String> frontier = new ArrayDeque<>();
        frontier.add(root);
        int depth = 0;
        while (!frontier.isEmpty() && depth < maxDepth) {
            List<LineageEdge> edges = downstream
                ? lineageRepo.findByTenantIdAndUpstreamFqnIn(tenantId, frontier)
                : lineageRepo.findByTenantIdAndDownstreamFqnIn(tenantId, frontier);
            ArrayDeque<String> next = new ArrayDeque<>();
            for (LineageEdge e : edges) {
                collectedEdges.add(e);
                String target = downstream ? e.getDownstreamFqn() : e.getUpstreamFqn();
                if (target != null && !target.isBlank() && visited.add(target)) {
                    next.add(target);
                }
            }
            frontier = next;
            depth++;
        }
    }

    private void directDownstream(UUID tenantId, String root, Set<String> out) {
        List<LineageEdge> edges = lineageRepo.findByTenantIdAndUpstreamFqn(tenantId, root);
        for (LineageEdge e : edges) {
            if (e.getDownstreamFqn() != null && !e.getDownstreamFqn().isBlank()) {
                out.add(e.getDownstreamFqn());
            }
        }
    }

    // ---- 节点 / 边构造 ----

    private List<Node> buildNodes(UUID tenantId, Set<String> fqns) {
        if (fqns.isEmpty()) return Collections.emptyList();
        List<Asset> assets = assetRepo.findByTenantIdAndOmFqnIn(tenantId, fqns);
        Map<String, Asset> byFqn = new LinkedHashMap<>();
        for (Asset a : assets) byFqn.put(a.getOmFqn(), a);

        List<Node> nodes = new ArrayList<>(fqns.size());
        for (String fqn : fqns) {
            Asset a = byFqn.get(fqn);
            nodes.add(a == null ? nodeFromFqn(fqn) : nodeFromAsset(a, fqn));
        }
        return nodes;
    }

    private Node nodeFromAsset(Asset a, String fqn) {
        return new Node(
            fqn,
            shortName(fqn, a.getDisplayName()),
            layerOf(fqn, a.getLayer()),
            nodeTypeOf(fqn, a.getAssetType()),
            a.getClassification(),
            a.getQualityScore(),
            a.getOwnerName(),
            a.getRowCount(),
            a.getSyncedAt(),
            parseColumns(a.getColumns())
        );
    }

    private Node nodeFromFqn(String fqn) {
        return new Node(
            fqn,
            shortName(fqn, null),
            layerOf(fqn, null),
            nodeTypeOf(fqn, null),
            null,
            null,
            null,
            null,
            null,
            Collections.emptyList()
        );
    }

    private Edge toEdge(LineageEdge e) {
        return new Edge(
            e.getUpstreamFqn(),
            e.getDownstreamFqn(),
            e.getJobRef(),
            parseColumnEdges(e.getColumnLevel())
        );
    }

    // ---- severity ----

    String severityOf(int apis, int subs, int jobs, boolean touchesDwsAds) {
        if (apis > 0 || subs >= subscriptionHighThreshold) return "HIGH";
        if (jobs >= jobsMediumThreshold || touchesDwsAds) return "MEDIUM";
        return "LOW";
    }

    List<String> severityReasonsOf(int apis, int subs, int jobs, boolean touchesDwsAds) {
        List<String> reasons = new ArrayList<>();
        if (apis > 0) reasons.add("影响对外 API " + apis + " 个");
        if (subs >= subscriptionHighThreshold) reasons.add("影响订阅方 " + subs + " 个");
        if (jobs >= jobsMediumThreshold) reasons.add("影响任务 " + jobs + " 个");
        if (touchesDwsAds) reasons.add("影响 DWS/ADS 聚合层");
        if (reasons.isEmpty()) reasons.add("仅影响 ODS/DWD 中间层");
        return reasons;
    }

    /**
     * 构造通知摘要（包含受影响数量 + 跳转链接上下文）。
     * 调用方负责把 receiver 传入 NotificationService。
     */
    public String buildImpactSummary(ImpactReportDTO report) {
        StringBuilder sb = new StringBuilder();
        sb.append("直接下游 ").append(report.directDownstream().size())
          .append("，间接下游 ").append(report.indirectDownstream().size())
          .append("。")
          .append("受影响：API ").append(report.affectedApis())
          .append(" / 订阅 ").append(report.affectedSubscribers())
          .append(" / 任务 ").append(report.affectedJobs()).append("。");
        if (!report.severityReasons().isEmpty()) {
            sb.append("判定依据：").append(String.join("；", report.severityReasons()));
        }
        return sb.toString();
    }

    private static boolean isDwsOrAds(String fqn) {
        return fqn != null && (fqn.startsWith("dws.") || fqn.startsWith("ads.")
            || fqn.startsWith("DWS.") || fqn.startsWith("ADS."));
    }

    // ---- 解析工具 ----

    private static String layerOf(String fqn, String layer) {
        if (layer != null && !layer.isBlank()) return layer.toUpperCase(Locale.ROOT);
        if (fqn == null || fqn.isBlank()) return null;
        int dot = fqn.indexOf('.');
        String prefix = dot > 0 ? fqn.substring(0, dot).toUpperCase(Locale.ROOT) : "";
        if (prefix.startsWith("ODS")) return "ODS";
        if (prefix.startsWith("DWD")) return "DWD";
        if (prefix.startsWith("DWS")) return "DWS";
        if (prefix.startsWith("ADS")) return "ADS";
        if (prefix.startsWith("API")) return "API";
        return "SOURCE";
    }

    private static String nodeTypeOf(String fqn, String assetType) {
        if (fqn != null && fqn.toUpperCase(Locale.ROOT).startsWith("API")) return "API";
        if (assetType != null && !assetType.isBlank()) return assetType.toUpperCase(Locale.ROOT);
        return "TABLE";
    }

    private static String shortName(String fqn, String displayName) {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (fqn == null || fqn.isBlank()) return "-";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot < fqn.length() - 1 ? fqn.substring(dot + 1) : fqn;
    }

    private List<Column> parseColumns(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) return Collections.emptyList();
            List<Column> columns = new ArrayList<>();
            node.forEach(item -> {
                String name = item.path("name").asText("");
                if (name.isBlank()) return;
                columns.add(new Column(
                    name,
                    item.path("type").asText("-"),
                    item.path("classification").asText(null)
                ));
            });
            return columns;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<ColumnEdge> parseColumnEdges(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) return Collections.emptyList();
            List<ColumnEdge> edges = new ArrayList<>();
            node.forEach(item -> {
                String from = item.path("from").asText("");
                String to = item.path("to").asText("");
                if (from.isBlank() && to.isBlank()) return;
                String transform = item.path("transform").asText(null);
                edges.add(new ColumnEdge(from, to, transform));
            });
            return edges;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }
}
