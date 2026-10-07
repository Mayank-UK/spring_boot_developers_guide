package guide.domain.messaging;

import java.util.List;

/**
 * Use case: read the dead-letter stream — the poisoned entries park
 * themselves here with a reason, newest last, for human eyes. A DLQ is not
 * a trash can: it is a WORK QUEUE FOR OPERATORS (inspect, fix, replay into
 * the main stream once the fix ships), and the console's replay story is
 * exactly XADD of the stored event again.
 */
public class ListDeadLetters {

    private final EventStream stream;

    public ListDeadLetters(EventStream stream) {
        this.stream = stream;
    }

    /** The DLQ window, bounded. */
    public List<EventStream.DeadLetter> execute(int limit) {
        return stream.deadLetters(Math.clamp(limit, 1, 200));
    }
}
