package guide.domain.aop;

import guide.core.result.Result;
import java.util.List;

/**
 * Use case behind GET /api/demo/aop/proxy: ask the port how the container
 * really handed out this app's own beans — runtime classes with their proxy
 * markers, interfaces, and the JDK-vs-CGLIB verdict with the rule that
 * decided it. No input, no expected failure mode — kept as a use case
 * anyway so the controller stays thin and the wiring mirrors every other
 * endpoint.
 */
public class ShowProxyKinds {

    private final ProxyInspectionSource source;

    public ShowProxyKinds(ProxyInspectionSource source) {
        this.source = source;
    }

    public Result<List<ProxyFacts>> execute() {
        return Result.success(List.copyOf(source.wiredBeanProxyFacts()));
    }
}
