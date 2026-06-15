package com.onelake.orchestration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Dagster GraphQL 客户端（对应《技术初始化文档》§6.5 编排模块）。
 * 调 Dagster GraphQL 触发物化（launchRun）。
 */
@Component
@RequiredArgsConstructor
public class DagsterClient {

    private static final String LAUNCH_RUN = """
        mutation($job: String!, $repo: String!, $location: String!) {
          launchRun(executionParams: {
            selector: { repositoryName: $repo, repositoryLocationName: $location, jobName: $job },
            executionMetadata: {}
          }) { __typename ... on LaunchRunSuccess { run { runId status } } }
        }""";

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.dagster.graphql-url:http://localhost:3000/graphql}")
    private String graphqlUrl;

    public String launch(String jobName, String repo, String location) {
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        var body = Map.of("query", LAUNCH_RUN,
            "variables", Map.of("job", jobName, "repo", repo, "location", location));
        JsonNode resp = client.post()
            .bodyValue(body)
            .retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class).map(b -> new DataplaneException("dagster launch failed: " + b)))
            .bodyToMono(JsonNode.class)
            .block();
        if (resp == null) throw new DataplaneException("dagster empty response");
        return resp.at("/data/launchRun/run/runId").asText();
    }
}
