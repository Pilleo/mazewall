package demo.boot;

import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.framework.ProxyFactory;
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
                               DataSourceTransactionManager manager, ConfigurableListableBeanFactory beans) {
        bootstrap.configureBinding(beans);
        var target = bootstrap.unsupported()
                ? new UnsupportedReportService(jdbc, bootstrap.root(), bootstrap.state())
                : new ReportService(jdbc, bootstrap.root(), bootstrap.state());
        var factory = new ProxyFactory(target);
        factory.addAdvisor(bootstrap.binder().dispatchAdvisor());
        factory.addAdvisor(bootstrap.guard().transactionAdvisor());
        factory.addAdvisor(bootstrap.guard().diagnosisAdvisor());
        factory.addAdvisor(new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()));
        factory.setFrozen(true);
        return (Reports) factory.getProxy();
    }
}
