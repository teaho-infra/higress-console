package io.higress.custom.quota;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class QuotaAutoConfigurationTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QuotaAutoConfiguration.class));

    @Test
    void disabledByDefault() {
        context.run(c -> assertThat(c).doesNotHaveBean(QuotaApi.class));
    }

    @Test
    void optInRegistersFilter() {
        context.withPropertyValues("walmart.quota.enabled=true", "PORTAL_PASSWORD=long-test-password",
                "GATEWAY_ADMIN_KEY=admin-key").run(c -> {
                    assertThat(c).hasSingleBean(QuotaApi.class);
                    assertThat(c).hasBean("walmartQuotaFilter");
                });
    }

    @Test
    void missingSecretsFailsStartup() {
        context.withPropertyValues("walmart.quota.enabled=true").run(c -> assertThat(c).hasFailed());
    }
}
