package com.onelake.integration.service.validation;

import com.onelake.common.exception.BizException;
import com.onelake.integration.domain.enums.DataSourceType;
import com.onelake.integration.domain.enums.NetworkMode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DataSourceConfigValidator {

    public DataSourceType parseType(String type) {
        try {
            return DataSourceType.valueOf(type.toUpperCase());
        } catch (RuntimeException e) {
            throw new BizException(40011, "不支持的数据源类型: " + type);
        }
    }

    public void validate(String type, Map<String, Object> config) {
        validate(parseType(type), NetworkMode.DIRECT, config);
    }

    public void validate(DataSourceType type, Map<String, Object> config) {
        validate(type, NetworkMode.DIRECT, config);
    }

    public void validate(String type, String networkMode, Map<String, Object> config) {
        validate(parseType(type), parseNetworkMode(networkMode), config);
    }

    public void validate(DataSourceType type, NetworkMode networkMode, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            throw new BizException(40020, "数据源配置不能为空");
        }
        switch (type) {
            case MYSQL, POSTGRES, ORACLE, SQLSERVER -> validateJdbc(config);
            case HIVE -> validateHive(config);
            case KAFKA -> validateKafka(config);
            case S3 -> validateS3(config);
            case FTP, SFTP -> validateFileServer(config);
            case NAS -> requireAny(config, "path", "rootPath");
            case HTTP -> require(config, "baseUrl");
            default -> throw new BizException(40011, "不支持的数据源类型: " + type);
        }
        validateNetwork(networkMode == null ? NetworkMode.DIRECT : networkMode, config);
    }

    public void validateDatabaseProbe(DataSourceType type, NetworkMode networkMode, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            throw new BizException(40020, "数据源配置不能为空");
        }
        switch (type) {
            case MYSQL, POSTGRES -> {
                require(config, "host");
                require(config, "port");
                require(config, "username");
            }
            default -> throw new BizException(40024, "当前类型暂不支持库列表探查，请手动输入");
        }
        validateNetwork(networkMode == null ? NetworkMode.DIRECT : networkMode, config);
    }

    public NetworkMode parseNetworkMode(String networkMode) {
        if (networkMode == null || networkMode.isBlank()) {
            return NetworkMode.DIRECT;
        }
        try {
            return NetworkMode.valueOf(networkMode.toUpperCase());
        } catch (RuntimeException e) {
            throw new BizException(40023, "不支持的网络模式: " + networkMode);
        }
    }

    private void validateJdbc(Map<String, Object> config) {
        require(config, "host");
        require(config, "port");
        requireAny(config, "dbName", "database");
        require(config, "username");
    }

    private void validateHive(Map<String, Object> config) {
        require(config, "host");
        require(config, "port");
        requireAny(config, "dbName", "database");
        String authMode = text(config.get("authMode"));
        if ("KERBEROS".equalsIgnoreCase(authMode)) {
            require(config, "principal");
        }
    }

    private void validateKafka(Map<String, Object> config) {
        require(config, "bootstrapServers");
        String securityProtocol = text(config.get("securityProtocol"));
        if (securityProtocol != null && securityProtocol.toUpperCase().startsWith("SASL")) {
            require(config, "saslMechanism");
            require(config, "saslUsername");
            require(config, "saslPassword");
        }
    }

    private void validateS3(Map<String, Object> config) {
        require(config, "bucket");
        boolean hasAccessKey = hasText(config.get("accessKey"));
        boolean hasSecretKey = hasText(config.get("secretKey"));
        if (hasAccessKey != hasSecretKey) {
            throw new BizException(40022, "S3 accessKey 和 secretKey 需要同时填写");
        }
    }

    private void validateFileServer(Map<String, Object> config) {
        require(config, "host");
        require(config, "port");
        requireAny(config, "path", "rootPath");
        require(config, "username");
    }

    private void validateNetwork(NetworkMode networkMode, Map<String, Object> config) {
        switch (networkMode) {
            case DIRECT -> {
                return;
            }
            case VPC -> require(config, "networkAccessRef");
            case SSH_TUNNEL -> {
                require(config, "sshHost");
                require(config, "sshPort");
                require(config, "sshUsername");
                String authType = text(config.get("sshAuthType"));
                if ("PASSWORD".equalsIgnoreCase(authType)) {
                    require(config, "sshPasswordRef");
                } else {
                    require(config, "sshPrivateKeyRef");
                }
            }
            default -> throw new BizException(40023, "不支持的网络模式: " + networkMode);
        }
    }

    private void require(Map<String, Object> config, String key) {
        if (!hasText(config.get(key))) {
            throw new BizException(40021, "缺少数据源配置字段: " + key);
        }
    }

    private void requireAny(Map<String, Object> config, String first, String second) {
        if (!hasText(config.get(first)) && !hasText(config.get(second))) {
            throw new BizException(40021, "缺少数据源配置字段: " + first + "/" + second);
        }
    }

    private boolean hasText(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        return true;
    }

    private String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
