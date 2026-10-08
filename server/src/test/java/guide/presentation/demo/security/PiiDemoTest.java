package guide.presentation.demo.security;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The PII crypto-shred demo over HTTP — the before/after IS the test: store
 * returns ciphertext previews only; read decrypts while the key exists;
 * erase shreds the key and logs the receipt; the SAME read route then
 * answers the typed undecryptable failure whose details distinguish
 * "erased" from "never stored". Every test uses its own subject id, so the
 * shared demo state never bleeds between cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PiiDemoTest {

    @Autowired
    private MockMvc mockMvc;

    private String store(String subject) throws Exception {
        mockMvc.perform(post("/api/demo/security/pii/store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"" + subject + "\",\"fields\":{"
                                + "\"name\":\"Ada Lovelace\",\"email\":\"ada@example.com\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject))
                .andExpect(jsonPath("$.keyId", startsWith("key_")))
                .andExpect(jsonPath("$.algorithm").value("AES-256/GCM/NoPadding"))
                .andExpect(jsonPath("$.fieldCount").value(2))
                .andExpect(jsonPath("$.ciphertextPreview.name", endsWith("…")))
                .andExpect(jsonPath("$.ciphertextPreview.email", endsWith("…")));
        return subject;
    }

    @Test
    void theFullLifecycleStoreReadEraseTypedFailureLog() throws Exception {
        String subject = store("usr_http_" + System.nanoTime());

        mockMvc.perform(get("/api/demo/security/pii/read/" + subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.fields.email").value("ada@example.com"));

        mockMvc.perform(post("/api/demo/security/pii/erase/" + subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject))
                .andExpect(jsonPath("$.dataUnreadableForever").value(true))
                .andExpect(jsonPath("$.ciphertextFieldsStillStored").value(2))
                .andExpect(jsonPath("$.logEntryId", startsWith("erasure_")));

        // the read AFTER: same route, typed failure, erased (not never-stored)
        mockMvc.perform(get("/api/demo/security/pii/read/" + subject))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.erased").value(true))
                .andExpect(jsonPath("$.error.ciphertextFields").value(2))
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers.containsString("undecryptable")));

        mockMvc.perform(get("/api/demo/security/pii/log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.subjectId == '" + subject + "')].keyId").isNotEmpty());
    }

    @Test
    void storingTwiceIsAConflictAndValidationShapesAreTyped() throws Exception {
        String subject = store("usr_dup_" + System.nanoTime());
        mockMvc.perform(post("/api/demo/security/pii/store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"" + subject + "\",\"fields\":{\"name\":\"Again\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"))
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers.containsString("crypto-shred")));

        mockMvc.perform(post("/api/demo/security/pii/store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"  \",\"fields\":{\"name\":\"Nobody\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void unknownSubjectsGetThePlainNotFoundWithoutTheErasedMarker() throws Exception {
        mockMvc.perform(get("/api/demo/security/pii/read/usr_ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.erased").doesNotExist());
        mockMvc.perform(post("/api/demo/security/pii/erase/usr_ghost"))
                .andExpect(status().isNotFound());
    }
}
