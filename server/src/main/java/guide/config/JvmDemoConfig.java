package guide.config;

import guide.domain.jvm.CompareThreadModels;
import guide.domain.jvm.RunStreamingDemo;
import guide.domain.jvm.RunThreadsDemo;
import guide.domain.jvm.StreamingExhibit;
import guide.domain.jvm.ThreadsExhibit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 02 (jvm) demo wiring: the exhibits and their use cases as explicit
 * beans, constructor-injected, per the composition-root rule. JvmDemoController
 * consumes these; nothing below presentation knows Spring.
 */
@Configuration
public class JvmDemoConfig {

    @Bean
    ThreadsExhibit threadsExhibit() {
        return new ThreadsExhibit();
    }

    @Bean
    RunThreadsDemo runThreadsDemo(ThreadsExhibit threadsExhibit) {
        return new RunThreadsDemo(threadsExhibit);
    }

    @Bean
    CompareThreadModels compareThreadModels(ThreadsExhibit threadsExhibit) {
        return new CompareThreadModels(threadsExhibit);
    }

    @Bean
    StreamingExhibit streamingExhibit() {
        return new StreamingExhibit();
    }

    @Bean
    RunStreamingDemo runStreamingDemo(StreamingExhibit streamingExhibit) {
        return new RunStreamingDemo(streamingExhibit);
    }
}
