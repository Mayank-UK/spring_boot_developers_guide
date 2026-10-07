package guide.domain.jobs;

/**
 * Handler for cleanup-pii — the recurring archetype: the retention sweep
 * the security topic's GDPR section promised. In production this deletes
 * or crypto-shreds records past their retention window in BATCHES; the
 * job-queue framing is what makes it safe — it runs off the request path,
 * its failures retry with backoff instead of aborting a cron run halfway,
 * and an operator can watch the last run's outcome on the record instead
 * of grepping cron logs. In a real system something SCHEDULES this type
 * (the @Scheduled section shows the one-liner); here the console enqueues
 * it by hand.
 */
public final class CleanupPiiJobHandler implements JobHandler {

    @Override
    public JobType type() {
        return JobType.CLEANUP_PII;
    }

    @Override
    public void execute(JobRecord job) {
        // the real body: find records past payload.olderThanDays, shred or
        // delete in batches, record how many rows went
    }
}
