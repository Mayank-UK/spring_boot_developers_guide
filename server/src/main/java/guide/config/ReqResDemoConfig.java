package guide.config;

import guide.domain.reqres.DecideMethodResponse;
import guide.domain.reqres.InspectRequestSurface;
import guide.domain.reqres.MethodsExhibit;
import guide.domain.reqres.RequestSurfaceExhibit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Demo wiring for topic 09 (request-response): the inspector's exhibit +
 * use case, and the methods matrix's exhibit + use case. One @Configuration
 * per topic keeps the composition root growing in reviewable units —
 * GuideConfig stays the app-wide skeleton, each topic owns its demo beans
 * here.
 */
@Configuration
public class ReqResDemoConfig {

    @Bean
    RequestSurfaceExhibit requestSurfaceExhibit() {
        return new RequestSurfaceExhibit();
    }

    @Bean
    InspectRequestSurface inspectRequestSurface(RequestSurfaceExhibit requestSurfaceExhibit) {
        return new InspectRequestSurface(requestSurfaceExhibit);
    }

    @Bean
    MethodsExhibit methodsExhibit() {
        return new MethodsExhibit();
    }

    @Bean
    DecideMethodResponse decideMethodResponse(MethodsExhibit methodsExhibit) {
        return new DecideMethodResponse(methodsExhibit);
    }
}
