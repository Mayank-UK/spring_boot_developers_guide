package guide.domain.authz;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The action axis of the authorization matrix — deliberately TINY. The
 * verbs the demo guards are read, list, publish; everything an endpoint
 * does maps onto one of them (delete would join here in a real app).
 *
 * The enum exists so the KNOWN actions are a closed set the compiler can
 * see; the evaluator still receives actions as STRINGS from the wire,
 * because deny-by-default is a property of the parse: an action that is
 * not in this set must DENY, never throw, never default to allow.
 */
public enum AuthzAction {
    READ, LIST, PUBLISH;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The deny-by-default parse: unknown strings return empty, never throw. */
    public static Optional<AuthzAction> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(action -> action.wireName().equals(value.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
