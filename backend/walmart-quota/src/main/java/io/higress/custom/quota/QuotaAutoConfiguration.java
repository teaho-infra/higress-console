package io.higress.custom.quota;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "walmart.quota.enabled", havingValue = "true")
public class QuotaAutoConfiguration {
    @Bean(destroyMethod = "close")
    public QuotaApi walmartQuotaApi(Environment env, org.springframework.context.ApplicationContext context) {
        java.util.function.Predicate<javax.servlet.http.HttpServletRequest> auth = ConsoleAuthentication
                .create(context);
        return new QuotaApi(QuotaSettings.from(env, auth != null), Clock.systemUTC(), auth);
    }

    @Bean
    public FilterRegistrationBean<QuotaApi> walmartQuotaFilter(QuotaApi api) {
        FilterRegistrationBean<QuotaApi> registration = new FilterRegistrationBean<>(api);
        registration.setName("walmartQuotaApi");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/api/login", "/api/logout", "/api/session", "/api/quota/*");
        return registration;
    }
}
