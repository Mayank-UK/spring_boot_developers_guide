package guide.data.redis;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.domain.redis.RedisPlayground;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The LIVE SWAP, redis edition: one RedisPlayground bean that answers every
 * call from whichever adapter the ScenarioStore points the 'redis' area at
 * AT CALL TIME. The area carries NO config-time pin (unlike 'api') — it
 * deliberately follows the GLOBAL toggle, so the public PATCH
 * /api/demo/scenario {"dataMode":"live"} is itself the demo: the very next
 * op reads the real server, PATCH it back and the one after that reads the
 * in-memory fake. No restart, no redeploy.
 *
 * The live path activates LAZILY, exactly once: the first live call pings
 * the server through LiveRedisArea (typed unavailable on failure, flag left
 * down so a healed dependency retried-activates) and only then reaches the
 * StringRedisTemplate adapter. The two suppliers arrive from the composition
 * root as ObjectProviders, so contexts without Redis autoconfiguration (the
 * @WebMvcTest slice) never construct the live side at all.
 */
public final class ScenarioRoutingRedisPlayground implements RedisPlayground {

    private final ScenarioStore scenarioStore;
    private final InMemoryRedisPlayground fake;
    private final Supplier<LiveRedisArea> activation;
    private final Supplier<StringRedisTemplatePlayground> liveAdapter;

    public ScenarioRoutingRedisPlayground(ScenarioStore scenarioStore,
            InMemoryRedisPlayground fake,
            Supplier<LiveRedisArea> activation,
            Supplier<StringRedisTemplatePlayground> liveAdapter) {
        this.scenarioStore = Objects.requireNonNull(scenarioStore, "scenarioStore");
        this.fake = Objects.requireNonNull(fake, "fake");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.liveAdapter = Objects.requireNonNull(liveAdapter, "liveAdapter");
    }

    /** The adapter this call goes to — read at CALL time, which is the whole point. */
    private RedisPlayground delegate() {
        if (scenarioStore.dataModeFor(AREA) != ScenarioDataMode.LIVE) {
            return fake;
        }
        activation.get().ensureConnected();
        return liveAdapter.get();
    }

    @Override
    public String namespaced(String bareKey) {
        // Same answer in both modes — fake/live parity is part of the port's contract.
        return RedisNamespace.prefixed(bareKey);
    }

    @Override
    public boolean exists(String key) {
        return delegate().exists(key);
    }

    @Override
    public String set(String key, String value) {
        return delegate().set(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        return delegate().get(key);
    }

    @Override
    public boolean delete(String key) {
        return delegate().delete(key);
    }

    @Override
    public void hashSet(String key, String field, String value) {
        delegate().hashSet(key, field, value);
    }

    @Override
    public Map<String, String> hashGetAll(String key) {
        return delegate().hashGetAll(key);
    }

    @Override
    public long listPush(String key, String value) {
        return delegate().listPush(key, value);
    }

    @Override
    public List<String> listRange(String key, long start, long stop) {
        return delegate().listRange(key, start, stop);
    }

    @Override
    public long setAdd(String key, String member) {
        return delegate().setAdd(key, member);
    }

    @Override
    public Set<String> setMembers(String key) {
        return delegate().setMembers(key);
    }

    @Override
    public long zAdd(String key, double score, String member) {
        return delegate().zAdd(key, score, member);
    }

    @Override
    public List<ScoredMember> zRange(String key, long start, long stop) {
        return delegate().zRange(key, start, stop);
    }

    @Override
    public void setWithTtl(String key, String value, Duration ttl) {
        delegate().setWithTtl(key, value, ttl);
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return delegate().expire(key, ttl);
    }

    @Override
    public Optional<Long> ttlOf(String key) {
        return delegate().ttlOf(key);
    }

    @Override
    public long publish(String channel, String message) {
        return delegate().publish(channel, message);
    }

    /** The fake this router serves in fake mode (tests reset through its concrete type). */
    public InMemoryRedisPlayground fakeAdapter() {
        return fake;
    }
}
