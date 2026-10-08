package guide.config;

import guide.data.jobs.AsyncWorkService;
import guide.data.jobs.InMemoryJobQueue;
import guide.data.jobs.JobWorkerTicker;
import guide.data.jobs.JobsHeartbeat;
import guide.data.jobs.RecordingEmailSender;
import guide.domain.jobs.AsyncWorkBoard;
import guide.domain.jobs.CleanupPiiJobHandler;
import guide.domain.jobs.EnqueueJob;
import guide.domain.jobs.GenerateReportJobHandler;
import guide.domain.jobs.GetJob;
import guide.domain.jobs.JobHandler;
import guide.domain.jobs.JobQueue;
import guide.domain.jobs.JobWorker;
import guide.domain.jobs.ListDeadLetters;
import guide.domain.jobs.ReadQueueOverview;
import guide.domain.jobs.ReplayDeadLetter;
import guide.domain.jobs.WelcomeEmailJobHandler;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

/**
 * Topic 29 (background jobs) wiring — the area's composition root, and the
 * guide's SCHEDULING home base:
 *
 * - {@code @EnableScheduling} was RELOCATED here from WebhooksDemoConfig
 *   (sanctioned edit, topic 29): the annotation is idempotent and global,
 *   and jobs is the topic that OWNS time-driven work, so the tickers the
 *   guide runs (webhook deliveries, the messaging outbox relay, this
 *   worker poller, the heartbeat) all live beneath one deliberate
 *   registration. The suite proves the relocation: those areas' tests stay
 *   green and the scheduling integration test watches ticks land.
 * - {@code @EnableAsync} lands here for the same reason — the @Async
 *   specimen and its named executor are this topic's teaching, and the
 *   annotation is likewise idempotent.
 *
 * Every port→adapter wire is explicit (the house rule): the JobQueue port
 * to its in-memory adapter, the EmailSender port to the recording sender,
 * the JobHandler list to the worker, and the use cases the controller
 * calls. The Clock is NOT a bean (the webhooks precedent): production
 * wiring wants system UTC, classes take Clock in constructors, and tests
 * inject fakes without a bean-collision surface for sibling topics.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class JobsDemoConfig {

    /**
     * The executor every knob of this topic is taught on. Each number is a
     * failure-mode choice: core 2 = idle cost (threads you pay for while
     * nothing runs); max 4 = ceiling (the pool never eats an unbounded
     * number of threads); queue 8 = latency debt (work admitted but not
     * yet running — the buffer that makes bursts smooth AND the backlog
     * that hides saturation); CallerRunsPolicy = the backpressure valve
     * (a FULL pool runs new work on the submitter instead of rejecting
     * it — degradation, not death — visible on the ticket's thread name).
     */
    @Bean
    ThreadPoolTaskExecutor jobsDemoExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("jobs-demo-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    // ---- the queue: port → in-memory adapter, plus the sender ----

    /**
     * Declared as the CONCRETE adapter (which still satisfies every JobQueue
     * injection point): the shared demo-reset sweep needs the console-only
     * clear() handle, which is not part of the queue's persistence contract.
     */
    @Bean
    InMemoryJobQueue jobQueue() {
        return new InMemoryJobQueue();
    }

    // declared as the CONCRETE recorder (not the EmailSender port): the
    // console reads the sent-mail view from it, while the handler injects
    // the PORT — one instance, both resolutions deterministic. The demo
    // controller's direct dependency on a data/ adapter is the guide's
    // documented teaching-exception for demo consoles.
    @Bean
    RecordingEmailSender emailSender() {
        return new RecordingEmailSender(Clock.systemUTC());
    }

    // ---- the handlers (one per JobType; the worker refuses duplicates) ----

    @Bean
    WelcomeEmailJobHandler welcomeEmailJobHandler(RecordingEmailSender emailSender, ObjectMapper mapper) {
        return new WelcomeEmailJobHandler(emailSender, mapper);
    }

    @Bean
    GenerateReportJobHandler generateReportJobHandler() {
        return new GenerateReportJobHandler();
    }

    @Bean
    CleanupPiiJobHandler cleanupPiiJobHandler() {
        return new CleanupPiiJobHandler();
    }

    // ---- the worker, its heartbeat(s), and the async specimen ----

    @Bean
    JobWorker jobWorker(JobQueue jobQueue, List<JobHandler> handlers) {
        // production jitter: uniform [0,1) fraction of each delay on top —
        // tests inject () -> 0.0 for exact schedules
        return new JobWorker(jobQueue, handlers, new Random()::nextDouble);
    }

    @Bean
    JobWorkerTicker jobWorkerTicker(JobWorker jobWorker) {
        return new JobWorkerTicker(jobWorker, Clock.systemUTC());
    }

    @Bean
    JobsHeartbeat jobsHeartbeat() {
        return new JobsHeartbeat(Clock.systemUTC());
    }

    @Bean
    AsyncWorkBoard asyncWorkBoard() {
        return new AsyncWorkBoard();
    }

    @Bean
    AsyncWorkService asyncWorkService(AsyncWorkBoard asyncWorkBoard) {
        return new AsyncWorkService(asyncWorkBoard, Clock.systemUTC());
    }

    // ---- use cases (the controller's entire vocabulary) ----

    @Bean
    EnqueueJob enqueueJob(JobQueue jobQueue, ObjectMapper mapper) {
        return new EnqueueJob(jobQueue, Clock.systemUTC(), mapper);
    }

    @Bean
    GetJob getJob(JobQueue jobQueue) {
        return new GetJob(jobQueue);
    }

    @Bean
    ListDeadLetters listJobDeadLetters(JobQueue jobQueue) {
        // bean name deliberately distinct from the messaging area's
        // listDeadLetters — same use-case shape, different domain
        return new ListDeadLetters(jobQueue);
    }

    @Bean
    ReadQueueOverview readQueueOverview(JobQueue jobQueue) {
        return new ReadQueueOverview(jobQueue, Clock.systemUTC());
    }

    @Bean
    ReplayDeadLetter replayDeadLetter(JobQueue jobQueue) {
        return new ReplayDeadLetter(jobQueue, Clock.systemUTC());
    }
}
