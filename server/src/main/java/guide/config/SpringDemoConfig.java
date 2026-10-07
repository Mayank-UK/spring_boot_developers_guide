package guide.config;

import guide.data.spring.AppInfoAdapter;
import guide.domain.spring.AppInfoSource;
import guide.domain.spring.ShowAppInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Topic 04 (spring-boot-fundamentals) demo wiring — the port→adapter seam
 * this topic teaches, done the GuideConfig way: explicit @Bean methods,
 * constructor injection, nothing below presentation knowing Spring. The
 * AppInfoAdapter is the only Spring-aware piece; ShowAppInfo and its exhibit
 * stay domain-pure. The clock is a supplier so domain tests pin time.
 */
@Configuration
public class SpringDemoConfig {

    /** ObjectProvider: BuildProperties exists only when build-info was generated. */
    @Bean
    AppInfoSource appInfoSource(Environment environment, ApplicationContext context,
            ObjectProvider<BuildProperties> buildProperties) {
        return new AppInfoAdapter(environment, context, buildProperties.getIfAvailable());
    }

    @Bean
    ShowAppInfo showAppInfo(AppInfoSource appInfoSource) {
        return new ShowAppInfo(appInfoSource, System::currentTimeMillis);
    }
}
