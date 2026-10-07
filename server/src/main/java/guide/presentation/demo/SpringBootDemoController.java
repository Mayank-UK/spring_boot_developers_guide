package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.spring.AppInfoExhibit;
import guide.domain.spring.ShowAppInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint for topic 04 (spring-boot-fundamentals) — the live half of
 * the anatomy-of-a-controller section:
 * - GET /api/demo/spring/app-info → 200 live introspection of the RUNNING
 *   context: application name + active profiles, Java version, startup
 *   timestamp, bean-definition count, @Configuration class count, embedded
 *   server + port, exposed actuator endpoints, and the top-level package
 *   layout of this app's own beans.
 *
 * Thin like every controller: no input to parse, ONE use case call, the
 * Result mapped onto HTTP. All the introspection lives behind the
 * ShowAppInfo use case; the controller would not change if the exhibit were
 * assembled some other way.
 */
@RestController
public class SpringBootDemoController {

    private final ShowAppInfo showAppInfo;

    public SpringBootDemoController(ShowAppInfo showAppInfo) {
        this.showAppInfo = showAppInfo;
    }

    /** GET /api/demo/spring/app-info → 200 live introspection of the running context. */
    @GetMapping("/api/demo/spring/app-info")
    AppInfoExhibit appInfo() {
        return unwrap(showAppInfo.execute());
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
