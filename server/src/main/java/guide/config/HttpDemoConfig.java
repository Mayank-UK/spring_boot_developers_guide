package guide.config;

import guide.domain.http.HeadersExhibit;
import guide.domain.http.InspectHttpRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Demo wiring for topic 03 (http-web): the header inspector's exhibit and
 * use case. One @Configuration per topic keeps the composition root growing
 * in reviewable units — GuideConfig stays the app-wide skeleton, each topic
 * owns its demo beans here.
 */
@Configuration
public class HttpDemoConfig {

    @Bean
    HeadersExhibit headersExhibit() {
        return new HeadersExhibit();
    }

    @Bean
    InspectHttpRequest inspectHttpRequest(HeadersExhibit headersExhibit) {
        return new InspectHttpRequest(headersExhibit);
    }
}
