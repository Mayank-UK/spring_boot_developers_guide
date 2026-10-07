package guide.config;

import guide.data.debug.InProcessStallTheater;
import guide.domain.debug.ReadStallThreads;
import guide.domain.debug.StallTheater;
import guide.domain.debug.StartStall;
import guide.domain.debug.StopStall;
import guide.domain.debug.ThreadDiagnostics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topic 35 (debugging) wiring — the stall demo's composition root. One
 * stateful {@link StallTheater} singleton (the theater), the pure-JDK
 * {@link ThreadDiagnostics} it reports through, and the three use cases the
 * thin controller binds to start, stop, and read. No machinery beyond the
 * JDK: the demo's entire point is that a thread dump needs nothing but the
 * running process itself.
 */
@Configuration
public class DebugDemoConfig {

    @Bean
    ThreadDiagnostics threadDiagnostics() {
        return new ThreadDiagnostics();
    }

    @Bean
    StallTheater stallTheater(ThreadDiagnostics diagnostics) {
        return new InProcessStallTheater(diagnostics);
    }

    @Bean
    ReadStallThreads readStallThreads(StallTheater theater, ThreadDiagnostics diagnostics) {
        return new ReadStallThreads(theater, diagnostics);
    }

    @Bean
    StartStall startStall(StallTheater theater, ReadStallThreads read) {
        return new StartStall(theater, read);
    }

    @Bean
    StopStall stopStall(StallTheater theater, ReadStallThreads read) {
        return new StopStall(theater, read);
    }
}
