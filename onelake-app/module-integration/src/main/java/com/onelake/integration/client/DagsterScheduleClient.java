package com.onelake.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.integration.domain.entity.SyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Integration 侧的 Dagster 调度登记客户端。
 *
 * <p>Dagster schedule 通常由 Python repository 定义，Java 控制面不直接“创建” schedule。
 * 这里通过可配置的 reconciliation job 传递启停意图；本地默认关闭，避免数据面未部署时阻断任务发布。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DagsterScheduleClient {

    private static final String LAUNCH_RECONCILE_JOB = """
        mutation($job: String!, $repo: String!, $location: String!, $config: RunConfigData!, $tags: [ExecutionTag!]) {
          launchRun(executionParams: {
            selector: { repositoryName: $repo, repositoryLocationName: $location, jobName: $job },
            runConfigData: $config,
            executionMetadata: { tags: $tags }
          }) { __typename ... on LaunchRunSuccess { run { runId status } } ... on PythonError { message } }
        }""";

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.dagster.graphql-url:http://localhost:3000/graphql}")
    private String graphqlUrl;

    @Value("${onelake.dataplane.dagster.schedule-enabled:false}")
    private boolean scheduleEnabled;

    @Value("${onelake.dataplane.dagster.repository-name:onelake}")
    private String repositoryName;

    @Value("${onelake.dataplane.dagster.location-name:onelake-loc}")
    private String locationName;

    @Value("${onelake.dataplane.dagster.schedule-reconcile-job:onelake_sync_task_schedule_reconcile}")
    private String reconcileJobName;

    public boolean registerOrUpdate(SyncTask task) {
        if (!shouldReconcile(task)) {
            return false;
        }
        return launch(task, "UPSERT");
    }

    public boolean disable(SyncTask task) {
        if (!scheduleEnabled || task == null || task.getId() == null || task.getScheduleCron() == null || task.getScheduleCron().isBlank()) {
            return false;
        }
        return launch(task, "DISABLE");
    }

    private boolean shouldReconcile(SyncTask task) {
        return scheduleEnabled
            && task != null
            && task.getId() != null
            && task.getScheduleCron() != null
            && !task.getScheduleCron().isBlank();
    }

    private boolean launch(SyncTask task, String action) {
        try {
            WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
            Map<String, Object> payload = Map.of(
                "query", LAUNCH_RECONCILE_JOB,
                "variables", Map.of(
                    "job", reconcileJobName,
                    "repo", repositoryName,
                    "location", locationName,
                    "config", runConfig(task, action),
                    "tags", List.of(
                        Map.of("key", "onelake.task_id", "value", task.getId().toString()),
                        Map.of("key", "onelake.action", "value", action)
                    )
                )
            );
            JsonNode resp = client.post().bodyValue(payload).retrieve().bodyToMono(JsonNode.class).block();
            String runId = resp == null ? "" : resp.at("/data/launchRun/run/runId").asText("");
            if (runId.isBlank()) {
                String error = resp == null ? "empty response" : resp.at("/data/launchRun/message").asText(resp.toString());
                log.warn("dagster schedule reconcile {} task={} failed: {}", action, task.getId(), error);
                return false;
            }
            log.info("dagster schedule reconcile {} task={} runId={}", action, task.getId(), runId);
            return true;
        } catch (Exception e) {
            log.warn("dagster schedule reconcile {} task={} skipped: {}", action, task.getId(), e.getMessage());
            return false;
        }
    }

    private Map<String, Object> runConfig(SyncTask task, String action) {
        return Map.of(
            "ops", Map.of(
                "reconcile_sync_task_schedule", Map.of(
                    "config", Map.of(
                        "action", action,
                        "task_id", task.getId().toString(),
                        "tenant_id", task.getTenantId() == null ? "" : task.getTenantId().toString(),
                        "name", task.getName() == null ? "" : task.getName(),
                        "cron", task.getScheduleCron() == null ? "" : task.getScheduleCron(),
                        "airbyte_connection_id", task.getAirbyteConnectionId() == null ? "" : task.getAirbyteConnectionId()
                    )
                )
            )
        );
    }
}
