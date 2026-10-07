package guide.domain.arch;

import guide.core.result.Result;
import java.util.List;

/**
 * Use case: assemble the layer map the arch demo serves — the computed
 * shape of the codebase (layers, class counts, the port/adapter inventory
 * with the @Bean bindings that wire them) joined with the EXEMPTION LEDGER
 * (guide.domain.arch.ArchExemptions), so the map always tells the whole
 * truth: the rule, and every documented place it bends. The counts and
 * inventory come from the CodebaseMapSource port at request time; the
 * ledger is the same single source the DependencyRuleTest enforces the
 * real tree against — nothing on this response is hand-maintained except
 * the reasons.
 */
public class BuildLayerMap {

    private final CodebaseMapSource source;

    public BuildLayerMap(CodebaseMapSource source) {
        this.source = source;
    }

    public Result<LayerMap> execute() {
        return Result.success(LayerMap.of(source.read()));
    }

    /**
     * The served map: computed structure plus the documented exemptions.
     * Layer order is the dependency rule's order — inward first.
     */
    public record LayerMap(
            List<CodebaseMapSource.LayerCount> layers,
            List<CodebaseMapSource.PortBinding> ports,
            List<ArchExemptions.TeachingExhibit> teachingExhibits,
            List<ArchExemptions.DomainExemption> domainExemptions) {

        static LayerMap of(CodebaseMapSource.CodebaseMap computed) {
            return new LayerMap(
                    List.copyOf(computed.layers()),
                    List.copyOf(computed.ports()),
                    ArchExemptions.teachingExhibits(),
                    ArchExemptions.domainLedger());
        }
    }
}
