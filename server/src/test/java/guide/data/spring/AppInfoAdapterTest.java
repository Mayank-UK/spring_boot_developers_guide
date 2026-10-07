package guide.data.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import guide.domain.spring.AppInfoFacts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The adapter half of topic 04's demo, with Spring's own surfaces mocked:
 * Environment, ApplicationContext, and the optional BuildProperties bean.
 * Verifies the bridging — Spring types in, primitives out — including the
 * no-build-info degradation and the ready-event timestamp. doReturn/doThrow
 * throughout because getType's Class<?> wildcard fights type inference.
 */
class AppInfoAdapterTest {

    private final Environment environment = mock(Environment.class);
    private final ApplicationContext context = mock(ApplicationContext.class);

    private void noBeans() {
        when(context.getBeanDefinitionNames()).thenReturn(new String[0]);
        when(context.getBeanNamesForAnnotation(Configuration.class)).thenReturn(new String[0]);
    }

    @Test
    void readsNameProfilesPortAndEndpointsFromTheEnvironment() {
        when(environment.getProperty("spring.application.name", "unknown"))
                .thenReturn("springboot-developers-guide");
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        when(environment.getProperty("server.port", Integer.class)).thenReturn(8080);
        when(environment.getProperty("management.endpoints.web.exposure.include"))
                .thenReturn("health,info");
        noBeans();

        AppInfoFacts facts = new AppInfoAdapter(environment, context, null).facts();

        assertEquals("springboot-developers-guide", facts.applicationName());
        assertEquals(List.of("dev"), facts.activeProfiles());
        assertEquals(8080, facts.serverPort());
        assertEquals(List.of("health", "info"), facts.actuatorEndpoints());
    }

    @Test
    void exposurePropertyDefaultsToHealthOnlyWhenUnset() {
        when(environment.getProperty("management.endpoints.web.exposure.include")).thenReturn(null);
        noBeans();

        assertEquals(List.of("health"),
                new AppInfoAdapter(environment, context, null).facts().actuatorEndpoints());
    }

    @Test
    void buildFieldsDegradeToNullWithoutABuildPropertiesBean() {
        noBeans();

        AppInfoFacts facts = new AppInfoAdapter(environment, context, null).facts();

        assertNull(facts.buildArtifact());
        assertNull(facts.buildVersion());
        assertNull(facts.buildTime());
    }

    @Test
    void buildFieldsComeFromBuildPropertiesWhenPresent() {
        BuildProperties build = mock(BuildProperties.class);
        when(build.getArtifact()).thenReturn("server");
        when(build.getVersion()).thenReturn("0.0.1-SNAPSHOT");
        when(build.getTime()).thenReturn(Instant.parse("2026-10-06T09:00:00Z"));
        noBeans();

        AppInfoFacts facts = new AppInfoAdapter(environment, context, build).facts();

        assertEquals("server", facts.buildArtifact());
        assertEquals("0.0.1-SNAPSHOT", facts.buildVersion());
        assertEquals("2026-10-06T09:00:00Z", facts.buildTime());
    }

    @Test
    void countsBeanDefinitionsAndResolvesGuideTypesOnly() {
        when(context.getBeanDefinitionNames()).thenReturn(new String[] {
                "guideConfig", "healthController", "jacksonAutoConfiguration", "brokenBean"});
        when(context.getBeanNamesForAnnotation(Configuration.class))
                .thenReturn(new String[] {"guideApplication", "guideConfig", "springDemoConfig"});
        doReturn(guide.config.GuideConfig.class).when(context).getType("guideConfig");
        doReturn(guide.presentation.HealthController.class).when(context).getType("healthController");
        doReturn(Object.class).when(context).getType("jacksonAutoConfiguration");
        doThrow(new IllegalStateException("unresolvable")).when(context).getType("brokenBean");

        AppInfoFacts facts = new AppInfoAdapter(environment, context, null).facts();

        assertEquals(4, facts.beanDefinitionCount());
        assertEquals(3, facts.configurationClassCount());
        assertEquals(List.of("guide.config.GuideConfig", "guide.presentation.HealthController"),
                facts.guideBeanTypeNames());
    }

    @Test
    void readyEventTimestampWinsOverConstructionTime() throws Exception {
        noBeans();
        AppInfoAdapter adapter = new AppInfoAdapter(environment, context, null);
        long beforeEvent = System.currentTimeMillis();
        Thread.sleep(5); // guarantee construction time < event time
        adapter.onApplicationEvent(null);

        assertTrue(adapter.facts().startupEpochMillis() >= beforeEvent,
                "ready timestamp should be the event time");
    }

    @Test
    void unstubbledEnvironmentYieldsNullsAndSafeDefaultsTheDomainNormalizes() {
        noBeans();

        AppInfoFacts facts = new AppInfoAdapter(environment, context, null).facts();

        assertNull(facts.applicationName());
        assertEquals(List.of(), facts.activeProfiles());
        assertEquals(8080, facts.serverPort());
        assertEquals(0, facts.beanDefinitionCount());
    }
}
