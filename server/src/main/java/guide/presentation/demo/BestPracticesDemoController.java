package guide.presentation.demo;

import guide.domain.best.PracticeChecklist;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint for topic 42 (best-practices) — the guide's practices as a
 * checklist the running app grades against itself:
 *
 * - GET /api/demo/best/checklist → every item (category + id + title) with
 *   either a LIVE verdict the app self-reported from in-process state (the
 *   registered failure taxonomy, the validated content registry, the
 *   effective configuration properties) or a VERIFY pointer (one curl
 *   command) where the truth is environmental — CI green, a database
 *   actually parameterizing, a probe answering. The tallies and reading
 *   lines ride along.
 *
 * Thin as always: one use-case call over boot-time facts; the report is
 * already the wire shape. Deterministic: every input is process state that
 * exists before the first request — no clocks, no randomness.
 */
@RestController
public class BestPracticesDemoController {

    private final PracticeChecklist practiceChecklist;
    private final PracticeChecklist.Facts facts;

    public BestPracticesDemoController(PracticeChecklist practiceChecklist,
            PracticeChecklist.Facts facts) {
        this.practiceChecklist = practiceChecklist;
        this.facts = facts;
    }

    @GetMapping(value = "/api/demo/best/checklist", produces = MediaType.APPLICATION_JSON_VALUE)
    PracticeChecklist.ChecklistReport checklist() {
        return practiceChecklist.evaluate(facts);
    }
}
