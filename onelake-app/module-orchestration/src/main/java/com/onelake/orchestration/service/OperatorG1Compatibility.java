package com.onelake.orchestration.service;

import com.onelake.orchestration.dto.OperatorManifestDTO;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** G1 Spark SQL 算子在 Palette、创建和编译阶段共享的兼容性边界。 */
final class OperatorG1Compatibility {

    private OperatorG1Compatibility() {
    }

    static boolean supports(OperatorManifestDTO manifest) {
        if (manifest == null || !"SPARK".equalsIgnoreCase(manifest.compileTarget())) {
            return false;
        }
        OperatorSqlGenerator.TemplateKind kind;
        try {
            kind = OperatorSqlGenerator.TemplateKind.valueOf(templateKind(manifest));
        } catch (RuntimeException ex) {
            return false;
        }
        List<Map<String, Object>> inputPorts = manifest.inputPorts();
        if (inputPorts == null || inputPorts.size() > 1) {
            return false;
        }
        if (inputPorts.isEmpty()) {
            return kind == OperatorSqlGenerator.TemplateKind.SELECT_EXPR
                    || kind == OperatorSqlGenerator.TemplateKind.RAW_SQL
                    || kind == OperatorSqlGenerator.TemplateKind.SPARK_SQL;
        }
        Map<String, Object> port = inputPorts.get(0);
        String name = port == null ? null : text(port.get("name"));
        String cardinality = port == null ? null : text(port.get("cardinality"));
        return StringUtils.hasText(name)
                && (!StringUtils.hasText(cardinality) || "ONE".equalsIgnoreCase(cardinality));
    }

    private static String templateKind(OperatorManifestDTO manifest) {
        Object rawKind = manifest.template() == null ? null : manifest.template().get("kind");
        if (rawKind == null && manifest.template() != null) {
            rawKind = manifest.template().get("templateKind");
        }
        return String.valueOf(rawKind).trim().toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
