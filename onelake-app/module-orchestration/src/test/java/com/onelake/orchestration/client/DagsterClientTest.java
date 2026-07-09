package com.onelake.orchestration.client;

import com.onelake.common.exception.DataplaneException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagsterClientTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void terminateUsesSafePolicyByDefault() throws IOException {
        DagsterClient client = clientResponding("""
            {"data":{"terminateRun":{"__typename":"TerminateRunSuccess","run":{"runId":"dagster-run-1","status":"CANCELING"}}}}
            """);

        assertThat(client.terminate("dagster-run-1", false)).isTrue();
        assertThat(requestBody.get()).contains("terminateRun");
        assertThat(requestBody.get()).contains("mutation($runId: String!");
        assertThat(requestBody.get()).contains("\"runId\":\"dagster-run-1\"");
        assertThat(requestBody.get()).contains("\"policy\":\"SAFE_TERMINATE\"");
    }

    @Test
    void terminateForceUsesImmediateCancelPolicyAndTreatsRunNotFoundAsSuccess() throws IOException {
        DagsterClient client = clientResponding("""
            {"data":{"terminateRun":{"__typename":"RunNotFoundError","message":"Run not found"}}}
            """);

        assertThat(client.terminate("dagster-run-missing", true)).isTrue();
        assertThat(requestBody.get()).contains("\"policy\":\"MARK_AS_CANCELED_IMMEDIATELY\"");
    }

    @Test
    void terminateTreatsAlreadyTerminalRunAsSuccess() throws IOException {
        DagsterClient client = clientResponding("""
            {"data":{"terminateRun":{"__typename":"TerminateRunFailure","message":"Run could not be terminated due to status SUCCESS","run":{"runId":"dagster-run-1","status":"SUCCESS"}}}}
            """);

        assertThat(client.terminate("dagster-run-1", false)).isTrue();
    }

    @Test
    void terminateThrowsForNonTerminalFailure() throws IOException {
        DagsterClient client = clientResponding("""
            {"data":{"terminateRun":{"__typename":"TerminateRunFailure","message":"terminate denied","run":{"runId":"dagster-run-1","status":"STARTED"}}}}
            """);

        assertThatThrownBy(() -> client.terminate("dagster-run-1", false))
                .isInstanceOf(DataplaneException.class)
                .hasMessageContaining("terminate denied");
    }

    private DagsterClient clientResponding(String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/graphql", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, responseBody);
        });
        server.start();
        DagsterClient client = new DagsterClient(WebClient.builder());
        ReflectionTestUtils.setField(client, "graphqlUrl",
                "http://localhost:" + server.getAddress().getPort() + "/graphql");
        return client;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
