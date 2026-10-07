package guide.domain.caching;

/**
 * What one read actually did — the fact every caching wrapper in this topic
 * reports instead of guessing: HIT (the backend answered; the compute did
 * not run) or MISS (the compute ran — absent, evicted, or expired, and a
 * miss is a miss).
 */
public enum CacheVerdict {
    HIT, MISS
}
