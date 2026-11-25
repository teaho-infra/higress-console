package io.higress.custom.quota;

import java.lang.reflect.Method;
import java.util.function.Predicate;
import javax.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

/** Optional bridge avoids a dependency cycle from the quota module back to Console. */
final class ConsoleAuthentication {
    private static final String SERVICE = "com.alibaba.higress.console.service.SessionService";

    static Predicate<HttpServletRequest> create(ApplicationContext context) {
        if (!ClassUtils.isPresent(SERVICE, context.getClassLoader()))
            return null;
        try {
            Class<?> type = ClassUtils.forName(SERVICE, context.getClassLoader());
            Method validate = type.getMethod("validateSession", HttpServletRequest.class);
            return request -> {
                try {
                    return validate.invoke(context.getBean(type), request) != null;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Console session validation failed", e);
                }
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Console session API is incompatible", e);
        }
    }
}
