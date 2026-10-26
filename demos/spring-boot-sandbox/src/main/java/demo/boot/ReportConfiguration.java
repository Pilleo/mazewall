package demo.boot;

import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Explicit application configuration; no universal automatic proxying or starter baseline. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class ReportConfiguration {
    @Bean JdbcDataSource dataSource() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:bootReport;DB_CLOSE_DELAY=-1");
        return data;
    }
    @Bean JdbcTemplate jdbcTemplate(JdbcDataSource data) { return new JdbcTemplate(data); }
    @Bean(name = "jdbc") DataSourceTransactionManager jdbcManager(JdbcDataSource data) {
        return new DataSourceTransactionManager(data);
    }
    @Bean Reports reportService(ReportBootstrap bootstrap, JdbcTemplate jdbc,
                                DataSourceTransactionManager manager, ConfigurableListableBeanFactory beans) throws Exception {
        bootstrap.configureBinding(beans);
        Reports target = switch (bootstrap.target()) {
            case REPORT -> new ReportService(jdbc, bootstrap.root());
            case VERIFICATION -> new ReportScenarioService(jdbc, bootstrap.root(), bootstrap.state());
            case UNSUPPORTED -> new UnsupportedReportService(jdbc, bootstrap.root(), bootstrap.state());
        };
        return bootstrap.binder().createJdbcProxy(Reports.class, target,
                new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()));
    }
}
