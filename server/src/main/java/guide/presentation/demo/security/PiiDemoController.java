package guide.presentation.demo.security;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.security.ErasureLog;
import guide.domain.security.ErasureLogEntry;
import guide.domain.security.ErasureService;
import guide.domain.security.ReadPii;
import guide.domain.security.StorePii;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 25 (security) — the PII crypto-shred vault. The
 * BEFORE/AFTER story is the demo: store reads back fine; after erasure the
 * SAME read route answers a typed failure saying the ciphertext still
 * exists but its key is gone. No plaintext is logged anywhere on these
 * paths — that discipline is part of the lesson.
 *
 * - POST /api/demo/security/pii/store          → {subject, fields} sealed
 *   under a fresh per-subject key; ciphertext previews + key id come back
 * - GET  /api/demo/security/pii/read/{subject} → decrypted fields (while
 *   the key exists), or the typed undecryptable failure (after erasure)
 * - POST /api/demo/security/pii/erase/{subject} → key destroyed, erasure
 *   logged, ciphertext rows deliberately retained
 * - GET  /api/demo/security/pii/log            → the append-only audit trail
 */
@RestController
public class PiiDemoController {

    private final StorePii storePii;
    private final ReadPii readPii;
    private final ErasureService erasureService;
    private final ErasureLog erasureLog;

    public PiiDemoController(StorePii storePii, ReadPii readPii, ErasureService erasureService,
            ErasureLog erasureLog) {
        this.storePii = storePii;
        this.readPii = readPii;
        this.erasureService = erasureService;
        this.erasureLog = erasureLog;
    }

    @PostMapping(value = "/api/demo/security/pii/store",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    StorePii.Stored store(@RequestBody StorePii.Request request) {
        return unwrap(storePii.execute(request));
    }

    @GetMapping(value = "/api/demo/security/pii/read/{subject}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ReadPii.View read(@PathVariable String subject) {
        return unwrap(readPii.execute(subject));
    }

    @PostMapping(value = "/api/demo/security/pii/erase/{subject}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ErasureService.Erased erase(@PathVariable String subject) {
        return unwrap(erasureService.execute(subject));
    }

    @GetMapping(value = "/api/demo/security/pii/log", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ErasureLogEntry> log() {
        return erasureLog.entries();
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
