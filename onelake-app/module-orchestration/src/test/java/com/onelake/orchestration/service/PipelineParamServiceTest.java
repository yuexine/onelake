package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.system.entity.TenantEntity;
import com.onelake.common.system.repository.TenantRepository;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.dto.ParamDTO;
import com.onelake.orchestration.dto.ParamReplaceRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link PipelineParamService} 的租户边界、替换语义与类型校验测试。 */
@ExtendWith(MockitoExtension.class)
class PipelineParamServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DAG_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private PipelineParamRepository paramRepo;
    @Mock private DagRepository dagRepo;
    @Mock private PipelineTaskRepository taskRepo;
    @Mock private TenantRepository tenantRepo;

    private PipelineParamService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        service = new PipelineParamService(paramRepo, dagRepo, taskRepo, tenantRepo);
        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        lenient().when(tenantRepo.findByIdForUpdate(TENANT_ID)).thenReturn(Optional.of(tenant));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listsPipelineAndTaskScopesWithinTenantDag() {
        stubDag();
        when(paramRepo.findByTenantIdAndDagIdAndScope(TENANT_ID, DAG_ID, "PIPELINE"))
                .thenReturn(List.of(entity("PIPELINE", null, "region", "us", "STRING")));
        when(paramRepo.findByTenantIdAndDagIdAndScope(TENANT_ID, DAG_ID, "TASK"))
                .thenReturn(List.of(entity("TASK", "transform", "region", "eu", "STRING")));

        List<ParamDTO> result = service.listPipelineParams(DAG_ID);

        assertThat(result).extracting(ParamDTO::scope).containsExactly("PIPELINE", "TASK");
        assertThat(result).extracting(ParamDTO::paramValue).containsExactly("us", "eu");
    }

    @Test
    void replacesPipelineParamsAndNormalizesValues() {
        stubDag();
        List<PipelineParam> existing = List.of(
                entity("PIPELINE", null, "old", "value", "STRING"));
        when(paramRepo.findByTenantIdAndDagIdAndScope(TENANT_ID, DAG_ID, "PIPELINE"))
                .thenReturn(existing);
        when(paramRepo.saveAllAndFlush(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ParamDTO> result = replace("PIPELINE", null, List.of(
                dto("PIPELINE", null, "threshold", "12.50", "number")));

        assertThat(result).singleElement()
                .satisfies(param -> {
                    assertThat(param.valueType()).isEqualTo("NUMBER");
                    assertThat(param.paramValue()).isEqualTo("12.50");
                });
        var replacementOrder = inOrder(dagRepo, paramRepo);
        replacementOrder.verify(dagRepo).findByIdForUpdate(DAG_ID);
        replacementOrder.verify(paramRepo).findByTenantIdAndDagIdAndScope(
                TENANT_ID, DAG_ID, "PIPELINE");
        verify(paramRepo).deleteAllInBatch(existing);
        verify(paramRepo, never()).findByTenantIdAndDagIdAndScope(
                TENANT_ID, DAG_ID, "TASK");
    }

    @Test
    void replacesOnlySelectedTaskParamsAndLeavesPipelineScopeUntouched() {
        stubDag();
        PipelineTask task = new PipelineTask();
        task.setTenantId(TENANT_ID);
        task.setDagId(DAG_ID);
        task.setTaskKey("transform");
        when(taskRepo.findByDagIdAndTaskKeyForUpdate(DAG_ID, "transform"))
                .thenReturn(Optional.of(task));
        List<PipelineParam> existing = List.of(
                entity("TASK", "transform", "old", "value", "STRING"));
        when(paramRepo.findByTenantIdAndDagIdAndTaskKeyAndScope(
                TENANT_ID, DAG_ID, "transform", "TASK"))
                .thenReturn(existing);
        when(paramRepo.saveAllAndFlush(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ParamDTO> result = replace("TASK", "transform", List.of(
                dto("TASK", "transform", "enabled", "TRUE", "bool"),
                dto("TASK", "transform", "date_expr", "${bizdate-1:yyyyMMdd}", "expr")));

        assertThat(result).extracting(ParamDTO::valueType)
                .containsExactly("EXPR", "BOOL");
        assertThat(result).extracting(ParamDTO::paramValue)
                .containsExactly("${bizdate-1:yyyyMMdd}", "true");
        verify(taskRepo).findByDagIdAndTaskKeyForUpdate(DAG_ID, "transform");
        verify(dagRepo, never()).findByIdForUpdate(DAG_ID);
        verify(paramRepo).deleteAllInBatch(existing);
        verify(paramRepo, never()).findByTenantIdAndDagIdAndScope(
                TENANT_ID, DAG_ID, "PIPELINE");
    }

    @Test
    void rejectsInvalidKeysDuplicateScopeAndUnknownTasksBeforeWriting() {
        stubDag();

        assertThatThrownBy(() -> replace("PIPELINE", null, List.of(
                dto("PIPELINE", null, "bad key", "x", "STRING"))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(40042));
        assertThatThrownBy(() -> replace("PIPELINE", null, List.of(
                dto("PIPELINE", null, "region", "cn", "STRING"),
                dto("PIPELINE", null, "region", "us", "STRING"))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(40044));
        assertThatThrownBy(() -> replace("TASK", "missing", List.of(
                dto("TASK", "missing", "region", "eu", "STRING"))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(40043));

        verify(paramRepo, never()).saveAllAndFlush(anyList());
    }

    @Test
    void rejectsBuiltInAndExpressionNamespaceKeys() {
        stubDag();

        for (String key : List.of(
                "run_id", "bizdate", "cyctime", "bizdate-1",
                "bizdate_backup", "bizdate--1", "cyctime_zone",
                "upstream.extract.rowsWritten")) {
            assertThatThrownBy(() -> replace("PIPELINE", null, List.of(
                    dto("PIPELINE", null, key, "value", "STRING"))))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(40042))
                    .hasMessageContaining("保留名称");
        }

        verify(paramRepo, never()).saveAllAndFlush(anyList());
    }

    @Test
    void rejectsMismatchedNumberBoolAndUnparseableExpressions() {
        stubDag();

        assertRejectedValue("abc", "NUMBER", 40047);
        assertRejectedValue("yes", "BOOL", 40047);
        assertRejectedValue("${bizdate--1}", "EXPR", 40048);
        assertRejectedValue("", "EXPR", 40048);
    }

    @Test
    void emptyGlobalListDeletesAllAndReturnsEmpty() {
        PipelineParam existing = entity("GLOBAL", null, "region", "cn", "STRING");
        when(paramRepo.findByTenantIdAndScope(TENANT_ID, "GLOBAL")).thenReturn(List.of(existing));
        when(paramRepo.saveAllAndFlush(anyList())).thenReturn(List.of());

        assertThat(service.replaceGlobalParams(List.of())).isEmpty();

        var replacementOrder = inOrder(tenantRepo, paramRepo);
        replacementOrder.verify(tenantRepo).findByIdForUpdate(TENANT_ID);
        replacementOrder.verify(paramRepo).findByTenantIdAndScope(TENANT_ID, "GLOBAL");
        verify(paramRepo).deleteAllInBatch(List.of(existing));
    }

    private void assertRejectedValue(String value, String type, int code) {
        assertThatThrownBy(() -> replace("PIPELINE", null, List.of(
                dto("PIPELINE", null, "candidate", value, type))))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(code));
    }

    private void stubDag() {
        Dag dag = new Dag();
        dag.setId(DAG_ID);
        dag.setTenantId(TENANT_ID);
        when(dagRepo.findByIdAndTenantId(DAG_ID, TENANT_ID)).thenReturn(Optional.of(dag));
        lenient().when(dagRepo.findByIdForUpdate(DAG_ID)).thenReturn(Optional.of(dag));
    }

    private ParamDTO dto(String scope, String taskKey, String key, String value, String type) {
        return new ParamDTO(null, scope, DAG_ID, taskKey, key, value, type, "说明", null);
    }

    private List<ParamDTO> replace(String scope, String taskKey, List<ParamDTO> params) {
        return service.replacePipelineParams(
                DAG_ID, new ParamReplaceRequest(scope, taskKey, params));
    }

    private PipelineParam entity(String scope, String taskKey, String key, String value, String type) {
        PipelineParam param = new PipelineParam();
        param.setId(UUID.randomUUID());
        param.setTenantId(TENANT_ID);
        param.setScope(scope);
        param.setDagId("GLOBAL".equals(scope) ? null : DAG_ID);
        param.setTaskKey(taskKey);
        param.setParamKey(key);
        param.setParamValue(value);
        param.setValueType(type);
        return param;
    }
}
