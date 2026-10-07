package guide.data.caching;

import guide.domain.caching.ReportCodec;
import guide.domain.caching.TopicStatsReport;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * The ReportCodec port's adapter: Jackson 3 (Boot 4 ships the
 * tools.jackson.* databind) over the report record. Records serialize and
 * deserialize natively — a record IS its canonical constructor signature,
 * which is all a JSON object round trip needs. Lives in the data layer with
 * the other library-owning adapters; the domain named the seam, this file
 * picked the library.
 */
public final class JacksonReportCodec implements ReportCodec {

    private final ObjectMapper mapper;

    public JacksonReportCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String encode(TopicStatsReport report) {
        return mapper.writeValueAsString(report);
    }

    @Override
    public TopicStatsReport decode(String encoded) {
        return mapper.readValue(encoded, TopicStatsReport.class);
    }
}
