package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.DeployDemoConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the deployment-story demo with the topic's own wiring. The
 * slice is the teaching: application.yml loads in the test context, so the
 * graceful-shutdown posture the endpoint reports is the REAL bound property
 * pair (server.shutdown=graceful, spring.lifecycle.timeout-per-shutdown-
 * phase=30s), not a hardcoded copy. Pinned: the six-service compose mirror
 * in file order, the parity rails (datasource/mongo/redis identical dev vs
 * prod; profile + deploy-env deliberately different), the actuator probe
 * pair the k8s Deployment polls, and the shutdown contract line.
 */
@WebMvcTest(DeployDemoController.class)
@Import(DeployDemoConfig.class)
class DeployDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theStackReportsTheProdContainerItShips() throws Exception {
        mockMvc.perform(get("/api/demo/deploy/stack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.container.image").value("sbdg_server_prod"))
                .andExpect(jsonPath("$.container.base").value("eclipse-temurin:25-jre-noble"))
                .andExpect(jsonPath("$.container.entrypoint")
                        .value("org.springframework.boot.loader.launch.JarLauncher"))
                .andExpect(jsonPath("$.container.port").value(8080));
    }

    @Test
    void theComposeMirrorServesAllSixServicesInFileOrder() throws Exception {
        mockMvc.perform(get("/api/demo/deploy/stack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.composeServices.length()").value(6))
                .andExpect(jsonPath("$.composeServices[0].name").value("dev"))
                .andExpect(jsonPath("$.composeServices[0].ports.length()").value(0))
                .andExpect(jsonPath("$.composeServices[1].name").value("server"))
                .andExpect(jsonPath("$.composeServices[1].image").value("maven:3.9-eclipse-temurin-25"))
                .andExpect(jsonPath("$.composeServices[1].ports[0]").value("8080:8080"))
                .andExpect(jsonPath("$.composeServices[2].name").value("client"))
                .andExpect(jsonPath("$.composeServices[3].name").value("postgres"))
                .andExpect(jsonPath("$.composeServices[3].image").value("postgres:17"))
                .andExpect(jsonPath("$.composeServices[4].name").value("redis"))
                .andExpect(jsonPath("$.composeServices[5].name").value("mongo"));
    }

    @Test
    void theEnvRailsShowParityWhereValuesAreIdentical() throws Exception {
        mockMvc.perform(get("/api/demo/deploy/stack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envRails.length()").value(5))
                // deliberate differences: the values that NAME the environment
                .andExpect(jsonPath("$.envRails[0].name").value("SPRING_PROFILES_ACTIVE"))
                .andExpect(jsonPath("$.envRails[0].devValue").value("dev"))
                .andExpect(jsonPath("$.envRails[0].prodValue").value("(unset → default)"))
                .andExpect(jsonPath("$.envRails[1].name").value("GUIDE_DEPLOY_ENV"))
                .andExpect(jsonPath("$.envRails[1].devValue").value("dev"))
                // parity: the datasource rail is byte-identical in both stacks
                .andExpect(jsonPath("$.envRails[2].name").value("SPRING_DATASOURCE_URL"))
                .andExpect(jsonPath("$.envRails[2].devValue").value("jdbc:postgresql://postgres:5432/guide"))
                .andExpect(jsonPath("$.envRails[2].prodValue").value("jdbc:postgresql://postgres:5432/guide"))
                .andExpect(jsonPath("$.envRails[4].name").value("SPRING_DATA_REDIS_HOST"))
                .andExpect(jsonPath("$.envRails[4].devValue").value("redis"))
                .andExpect(jsonPath("$.envRails[4].prodValue").value("redis"));
    }

    @Test
    void theProbePairMatchesTheKubernetesManifest() throws Exception {
        mockMvc.perform(get("/api/demo/deploy/stack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probes.livenessPath").value("/actuator/health/liveness"))
                .andExpect(jsonPath("$.probes.readinessPath").value("/actuator/health/readiness"))
                .andExpect(jsonPath("$.probes.port").value(8080));
    }

    @Test
    void theShutdownPostureIsTheRealBoundConfigNotACopy() throws Exception {
        mockMvc.perform(get("/api/demo/deploy/stack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shutdown.mode").value("graceful"))
                .andExpect(jsonPath("$.shutdown.timeout").value("30s"))
                .andExpect(jsonPath("$.shutdown.contract")
                        .value(org.hamcrest.Matchers.containsString("terminationGracePeriodSeconds")))
                .andExpect(jsonPath("$.reading.length()").value(4))
                .andExpect(jsonPath("$.reading[3]")
                        .value(org.hamcrest.Matchers.containsString("server.shutdown=graceful")));
    }
}
