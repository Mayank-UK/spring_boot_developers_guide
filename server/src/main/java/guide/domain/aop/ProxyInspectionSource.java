package guide.domain.aop;

import java.util.List;

/**
 * Port for topic 06's aop:proxy demo: runtime facts about how Spring handed
 * out a fixed set of this app's own beans. The domain asks "what was really
 * injected?"; the adapter (data/aop) is the only file that talks to Spring's
 * AOP machinery to answer. Same seam as AppInfoSource in topic 04 —
 * framework introspection stays behind a port so the use case and exhibit
 * remain domain-pure.
 */
public interface ProxyInspectionSource {

    /** One ProxyFacts per wired bean the demo covers, in demo display order. */
    List<ProxyFacts> wiredBeanProxyFacts();
}
