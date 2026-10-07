package guide.domain.caching;

/**
 * The serialization PORT: a shared cache stores BYTES, and bytes need a
 * codec. The CacheBackend port deliberately speaks String ("a cache stores
 * the serialized form and stays ignorant of what it cached"), so the report
 * needs one owned seam between its typed self and that wire form.
 *
 * Keeping it a port (implemented by data/caching/JacksonReportCodec) holds
 * the dependency rule: the domain names the operation, the adapter picks the
 * library. Both caching paths — explicit cache-aside and the
 * CacheManager-backed annotation path — encode through the SAME codec, so
 * what lands in the backend is byte-identical whichever door wrote it.
 */
public interface ReportCodec {

    /** The report as its cached wire form (JSON in this repo's adapter). */
    String encode(TopicStatsReport report);

    /** Parses the cached form back; the caller knows which type it put in. */
    TopicStatsReport decode(String encoded);
}
