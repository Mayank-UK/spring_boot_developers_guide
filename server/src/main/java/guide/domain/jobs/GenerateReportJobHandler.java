package guide.domain.jobs;

/**
 * Handler for generate-report — the heavy archetype: work too slow to sit
 * in a request (minutes of querying and rendering, a file produced). The
 * teaching body is deliberately skeletal — in production this queries the
 * read model, renders a document, uploads it to object storage, and mails
 * a link; the files-media and third-party topics own those halves. What
 * this handler DOES carry is the job discipline: the payload names the
 * scope, the work is idempotent (regenerating a report overwrites, never
 * double-charges), and success is recorded on the record itself.
 */
public final class GenerateReportJobHandler implements JobHandler {

    @Override
    public JobType type() {
        return JobType.GENERATE_REPORT;
    }

    @Override
    public void execute(JobRecord job) {
        // the real body: query the read model for the scope, render, upload,
        // notify — minutes of work that must never ride a request thread
    }
}
