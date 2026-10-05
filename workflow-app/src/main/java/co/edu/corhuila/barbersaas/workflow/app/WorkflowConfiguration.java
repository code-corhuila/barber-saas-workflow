package co.edu.corhuila.barbersaas.workflow.app;

import co.edu.corhuila.barbersaas.workflow.adapter.in.http.CorrelationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Composition root: the only place that knows every concrete type, and where every limit is
 * declared explicitly (norm 5.3.10) instead of hidden in defaults.
 */
@Configuration
public class WorkflowConfiguration {

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
