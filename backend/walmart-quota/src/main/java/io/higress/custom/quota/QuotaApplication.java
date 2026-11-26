package io.higress.custom.quota;

import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import javax.servlet.Filter;
import javax.servlet.http.HttpServletResponse;

/** Compatibility launcher; production Console imports QuotaAutoConfiguration instead. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class QuotaApplication {
    @Bean
    public FilterRegistrationBean<Filter> standaloneHeaders() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>((request, response, chain) -> {
            HttpServletResponse res = (HttpServletResponse) response;
            res.setHeader("X-Frame-Options", "DENY");
            res.setHeader("X-Content-Type-Options", "nosniff");
            res.setHeader("Referrer-Policy", "same-origin");
            res.setHeader("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
            chain.doFilter(request, response);
        });
        bean.addUrlPatterns("/*");
        bean.setOrder(-200);
        return bean;
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(QuotaApplication.class);
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("walmart.quota.enabled", "true");
        defaults.put("server.port", "${PORTAL_PORT:18890}");
        defaults.put("server.address", "${PORTAL_BIND:127.0.0.1}");
        app.setDefaultProperties(defaults);
        app.run(args);
    }
}
