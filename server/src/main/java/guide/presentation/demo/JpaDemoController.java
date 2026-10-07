package guide.presentation.demo;

import guide.domain.jpa.ReadJpaPage;
import guide.domain.jpa.RunJpaProbe;
import guide.domain.jpa.RunLockingProbe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Topic 18 (spring-data-jpa) demo endpoints — three thin handlers under
 * /api/demo/jpa, each doing exactly the house job: parse the input, call one
 * use case, hand back its value. The learning lives in domain/jpa +
 * data/jpa; none of it here. Until the 'jpa' area is flipped live, the use
 * cases answer with the typed UNAVAILABLE envelope (503) that names the
 * dependency and the activation command — that envelope is part of the
 * demo's teaching, not an error to hide.
 *
 * Surface map:
 *   GET /probe?mode=n-plus-1|join-fetch|entity-graph|batch&size=N
 *        — one specimen page under one fetch shape, measured: queriesIssued,
 *          postsReturned, entriesLoaded, wallTimeNanos, verdict
 *   GET /probe/locking?surface=report|envelope
 *        — the two-writer optimistic race; envelope ends in the real 409
 *   GET /probe/pagination?page=&size=&status=&projection=interface|class
 *        — Page metadata plus the count-query cost, DTO rows only
 */
@Tag(name = "JPA demos", description = "Topic 18's live-PostgreSQL measurements: the N+1 probe, the optimistic-locking race, and Page metadata with its count-query cost.")
@RestController
@RequestMapping("/api/demo/jpa")
public class JpaDemoController {

    private final RunJpaProbe probe;
    private final RunLockingProbe locking;
    private final ReadJpaPage pagination;

    public JpaDemoController(RunJpaProbe probe, RunLockingProbe locking, ReadJpaPage pagination) {
        this.probe = probe;
        this.locking = locking;
        this.pagination = pagination;
    }

    @Operation(summary = "Measure one fetch shape over a page of posts", operationId = "runJpaProbe",
            description = "Seeds N specimen posts (rolled back afterwards) and loads the page under the "
                    + "requested fetch shape, counting every SQL statement Hibernate issues for the read. "
                    + "n-plus-1 is the disease; join-fetch, entity-graph, and batch are the three cures, "
                    + "each with its measured query count.")
    @GetMapping(value = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    guide.domain.jpa.JpaProbePort.ProbeReport probe(
            @RequestParam("mode") String mode,
            @RequestParam(name = "size", required = false) Integer size) {
        return probe.run(mode, size);
    }

    @Operation(summary = "Two writers, one @Version column", operationId = "runJpaLockingProbe",
            description = "Two independent readers load one committed row; the first writer's save bumps "
                    + "the version, the second writer's stale save is rejected. surface=envelope ends the "
                    + "request with the losing writer's outcome as the guide's 409 conflict envelope.")
    @GetMapping(value = "/probe/locking", produces = MediaType.APPLICATION_JSON_VALUE)
    guide.domain.jpa.JpaProbePort.LockingReport locking(
            @RequestParam(name = "surface", required = false) String surface) {
        return locking.run(surface);
    }

    @Operation(summary = "Page metadata and the count-query cost", operationId = "readJpaPage",
            description = "A real paged read: totalElements/totalPages/hasNext plus how many SQL "
                    + "statements the page cost (content + count). projection=interface composes a "
                    + "Specification from the optional status filter; projection=class runs a fixed "
                    + "constructor-expression query — the trade-off is the Specifications lesson.")
    @GetMapping(value = "/probe/pagination", produces = MediaType.APPLICATION_JSON_VALUE)
    guide.domain.jpa.JpaProbePort.PageReport pagination(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "projection", required = false) String projection) {
        return pagination.read(page, size, status, projection);
    }
}
