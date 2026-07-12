package com.onelake.orchestration.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptSandboxPolicyTest {

    @Test
    void requiresBothEnvironmentSwitchAndTenantCapability() {
        UUID allowed = UUID.randomUUID();
        UUID denied = UUID.randomUUID();

        assertThat(new ScriptSandboxPolicy(false, allowed.toString()).isEnabledFor(allowed)).isFalse();
        ScriptSandboxPolicy enabled = new ScriptSandboxPolicy(true, allowed.toString());
        assertThat(enabled.isEnabledFor(allowed)).isTrue();
        assertThat(enabled.isEnabledFor(denied)).isFalse();
        assertThat(new ScriptSandboxPolicy(true, "").isEnabledFor(allowed)).isFalse();
    }

    @Test
    void rejectsMalformedTenantConfigurationAtStartup() {
        assertThatThrownBy(() -> new ScriptSandboxPolicy(true, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid script sandbox tenant UUID");
    }
}
