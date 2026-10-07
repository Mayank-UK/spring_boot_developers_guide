package guide.presentation.demo;

import guide.domain.testing.RunTestBattery;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 34 (testing):
 *
 * - GET /api/demo/testing/run → run the battery of example test-style
 *   checks across the pyramid's layers (unit, data, web) and answer the
 *   runner's report: every check with name, layer, status, duration, and
 *   the reason; plus the tally (passed/failed/skipped, total ms) and the
 *   scenario it ran under. With the scenario's failure injection ON, the
 *   deliberately wrong assertion FAILS — the demo's honest exhibit of a
 *   red check.
 *
 * Thin as always: one endpoint, one use case call, no mapping logic — the
 * report is already the wire shape.
 */
@RestController
public class TestingDemoController {

    private final RunTestBattery runTestBattery;

    public TestingDemoController(RunTestBattery runTestBattery) {
        this.runTestBattery = runTestBattery;
    }

    @GetMapping(value = "/api/demo/testing/run", produces = MediaType.APPLICATION_JSON_VALUE)
    RunTestBattery.BatteryReport run() {
        return runTestBattery.execute();
    }
}
