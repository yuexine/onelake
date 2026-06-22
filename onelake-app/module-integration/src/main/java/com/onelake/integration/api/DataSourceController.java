package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.integration.api.vo.ConnectivityResult;
import com.onelake.integration.api.vo.CreateDataSourceVO;
import com.onelake.integration.api.vo.DatabaseProbeResult;
import com.onelake.integration.api.vo.ProbeDatabasesVO;
import com.onelake.integration.api.vo.TestDataSourceVO;
import com.onelake.integration.api.vo.UpdateDataSourceVO;
import com.onelake.integration.dto.DataSourceDTO;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import com.onelake.integration.dto.AirbyteConnectorDefinitionDTO;
import com.onelake.integration.dto.AirbyteConnectorSpecDTO;
import com.onelake.integration.service.DataSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * 数据源 Controller（对应《技术初始化文档》§6.10 api 层）。
 * 路径与《详细功能清单》关键接口保持一致：/api/v1/integration/datasources
 */
@RestController
@RequestMapping("/api/v1/integration/datasources")
@RequiredArgsConstructor
@Tag(name = "数据源管理", description = "连接管理、连接测试、Airbyte 连接器元数据和源端结构探查接口。")
public class DataSourceController {

    private final DataSourceService service;

    @Operation(
        summary = "创建数据源",
        description = "用途：保存 MySQL、PostgreSQL、文件源等连接配置。前端对接：IntegrationAPI.createDatasource，由 DatasourceList 新建连接抽屉提交。"
    )
    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DataSourceDTO> create(@Valid @RequestBody CreateDataSourceVO vo) {
        return ApiResponse.ok(service.create(vo));
    }

    @Operation(
        summary = "更新数据源",
        description = "用途：修改已有连接的配置、环境、网络和凭据引用。前端对接：当前 API 聚合层尚未封装编辑入口，供后续 DatasourceDetail 或连接编辑表单使用。"
    )
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DataSourceDTO> update(@PathVariable UUID id,
                                             @RequestBody UpdateDataSourceVO vo) {
        return ApiResponse.ok(service.update(id, vo));
    }

    @Operation(
        summary = "获取数据源详情",
        description = "用途：读取单个连接的基础信息、健康状态和配置摘要。前端对接：IntegrationAPI.getDatasource，由 DatasourceDetail 使用。"
    )
    @GetMapping("/{id}")
    public ApiResponse<DataSourceDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(
        summary = "查询数据源列表",
        description = "用途：按类型、健康、环境和关键字筛选连接清单。前端对接：IntegrationAPI.listDatasources，由 DatasourceList、SyncTaskWizard 等页面使用。"
    )
    @GetMapping
    public ApiResponse<List<DataSourceDTO>> list(@RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String health,
                                                 @RequestParam(required = false) String envLevel,
                                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.list(type, health, envLevel, keyword));
    }

    @Operation(
        summary = "删除数据源",
        description = "用途：删除连接并清理相关资源引用。前端对接：IntegrationAPI.deleteDatasource，由 DatasourceList 和 DatasourceDetail 的删除操作调用。"
    )
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @Operation(
        summary = "测试已保存数据源连接",
        description = "用途：基于已保存配置验证连通性、权限和诊断信息。前端对接：IntegrationAPI.testDatasource，由 DatasourceList 和 DatasourceDetail 的测试按钮调用。"
    )
    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ConnectivityResult> test(@PathVariable UUID id) {
        return ApiResponse.ok(service.testConnectivity(id));
    }

    @Operation(
        summary = "测试未保存数据源配置",
        description = "用途：在新建连接表单提交前验证临时配置。前端对接：IntegrationAPI.testDatasourceConfig，由 DatasourceList 新建抽屉的测试连接调用。"
    )
    @PostMapping("/test-config")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<ConnectivityResult> testConfig(@Valid @RequestBody TestDataSourceVO vo) {
        return ApiResponse.ok(service.testConnectivity(vo));
    }

    @Operation(
        summary = "探测数据库列表",
        description = "用途：根据临时连接配置读取可访问数据库或 schema 候选。前端对接：IntegrationAPI.probeDatabases，由 DatasourceList 新建连接流程自动填充数据库选择。"
    )
    @PostMapping("/probe-databases")
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<DatabaseProbeResult> probeDatabases(@Valid @RequestBody ProbeDatabasesVO vo) {
        return ApiResponse.ok(service.probeDatabases(vo));
    }

    @Operation(
        summary = "查询 Airbyte Source 连接器定义",
        description = "用途：从 Airbyte 获取可用源端连接器清单。前端对接：IntegrationAPI.listAirbyteSourceDefinitions，由 DatasourceList 连接类型选择使用。"
    )
    @GetMapping("/airbyte/source-definitions")
    public ApiResponse<List<AirbyteConnectorDefinitionDTO>> airbyteSourceDefinitions() {
        return ApiResponse.ok(service.listAirbyteSourceDefinitions());
    }

    @Operation(
        summary = "查询 Airbyte Destination 连接器定义",
        description = "用途：从 Airbyte 获取可用目标端连接器清单。前端对接：IntegrationAPI.listAirbyteDestinationDefinitions 已封装，当前页面未直接调用，供采集目标配置使用。"
    )
    @GetMapping("/airbyte/destination-definitions")
    public ApiResponse<List<AirbyteConnectorDefinitionDTO>> airbyteDestinationDefinitions() {
        return ApiResponse.ok(service.listAirbyteDestinationDefinitions());
    }

    @Operation(
        summary = "查询 Airbyte Source 连接器规格",
        description = "用途：获取指定源端连接器的 JSON Schema 配置说明。前端对接：IntegrationAPI.getAirbyteSourceDefinitionSpec 已封装，当前页面未直接调用，供动态连接表单使用。"
    )
    @GetMapping("/airbyte/source-definitions/{definitionId}/spec")
    public ApiResponse<AirbyteConnectorSpecDTO> airbyteSourceDefinitionSpec(@PathVariable String definitionId) {
        return ApiResponse.ok(service.getAirbyteSourceDefinitionSpec(definitionId));
    }

    @Operation(
        summary = "查询 Airbyte Destination 连接器规格",
        description = "用途：获取指定目标端连接器的 JSON Schema 配置说明。前端对接：IntegrationAPI.getAirbyteDestinationDefinitionSpec 已封装，当前页面未直接调用，供目标端动态表单使用。"
    )
    @GetMapping("/airbyte/destination-definitions/{definitionId}/spec")
    public ApiResponse<AirbyteConnectorSpecDTO> airbyteDestinationDefinitionSpec(@PathVariable String definitionId) {
        return ApiResponse.ok(service.getAirbyteDestinationDefinitionSpec(definitionId));
    }

    @Operation(
        summary = "列出数据源 schema",
        description = "用途：读取指定连接下的 schema 或 database 候选。前端对接：IntegrationAPI.listDatasourceSchemas，由 SyncTaskWizard 选择源表时调用。"
    )
    @GetMapping("/{id}/schemas")
    public ApiResponse<List<String>> schemas(@PathVariable UUID id) {
        return ApiResponse.ok(service.listSchemas(id));
    }

    @Operation(
        summary = "列出数据源表",
        description = "用途：读取指定连接和可选 schema 下的表名。前端对接：IntegrationAPI.listDatasourceTables，由 SyncTaskWizard 源表选择调用。"
    )
    @GetMapping("/{id}/tables")
    public ApiResponse<List<String>> tables(@PathVariable UUID id,
                                            @RequestParam(required = false) String schema) {
        return ApiResponse.ok(service.listTables(id, schema));
    }

    @Operation(
        summary = "读取数据源表字段",
        description = "用途：描述源端表字段、类型、主键和空值信息。前端对接：IntegrationAPI.describeDatasourceTable，由 SyncTaskWizard 预览字段映射使用。"
    )
    @GetMapping("/{id}/tables/{objectName}/columns")
    public ApiResponse<List<DiscoveredColumnDTO>> columns(@PathVariable UUID id,
                                                          @PathVariable String objectName) {
        return ApiResponse.ok(service.describeTable(id, objectName));
    }
}
