package guide.presentation.demo;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.FilesDemoConfig;
import guide.domain.files.GeneratedBytePattern;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc over the files demos with the REAL demo beans (disk store pointed
 * at a throwaway directory, cap pinned at 1024 bytes so the 413 path needs
 * no multi-megabyte payload). Covers the full round trip: multipart upload
 * → sniffed type + sha256 ETag; the download's four answers (200 full,
 * 304 conditional, 206 partial with exact Content-Range/length, 416 with
 * bytes star-slash size); the typed 413 cap envelope; the traversal
 * rejection; the thumbnail derived variant (deterministic in-test PNG
 * fixture, dimensions verified after re-decode); and the generated
 * backpressure stream's deterministic pattern. StreamingResponseBody
 * handlers run through Spring's real async dispatch, like topic 09's.
 */
@WebMvcTest(FilesDemoController.class)
@Import(FilesDemoConfig.class)
@TestPropertySource(properties = {
        "guide.demo.files.storage-dir=target/files-demo-test",
        "guide.demo.files.max-upload-bytes=1024"
})
class FilesDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- the deterministic PNG fixture (no randomness: fixed pixels) ----

    /** A 64x32 PNG with a fixed two-tone pattern — decodable, small, identical every run. */
    private static byte[] deterministicPng() {
        try {
            BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 32; y++) {
                    graphics.setColor((x / 8 + y / 8) % 2 == 0 ? Color.WHITE : Color.DARK_GRAY);
                    graphics.fillRect(x, y, 1, 1);
                }
            }
            graphics.dispose();
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ImageIO.write(image, "png", encoded);
            return encoded.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("fixture generation failed", failure);
        }
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** Uploads content and returns the STORED id from the 201 body. */
    private String upload(byte[] content, String filename, String claimedType) throws Exception {
        String body = mockMvc.perform(multipart("/api/demo/files/upload")
                        .file(new MockMultipartFile("file", filename, claimedType, content)))
                .andExpect(status().is(201))
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return body.split("\"id\":\"")[1].split("\"")[0];
    }

    // ---- upload: stream in, sniffed type, digest, ETag ----

    @Test
    void multipartUploadRoundTripsSniffedTypeDigestAndEtag() throws Exception {
        byte[] png = deterministicPng();
        String digest = sha256Hex(png);

        mockMvc.perform(multipart("/api/demo/files/upload")
                        .file(new MockMultipartFile("file", "cat.png", "text/html", png))) // the lying claim
                .andExpect(status().is(201))
                .andExpect(jsonPath("$.filename").value("cat.png"))
                .andExpect(jsonPath("$.contentType").value("image/png")) // sniffed, not claimed
                .andExpect(jsonPath("$.claimedContentType").value("text/html"))
                .andExpect(jsonPath("$.size").value((long) png.length))
                .andExpect(jsonPath("$.sha256").value(digest))
                .andExpect(jsonPath("$.etag").value("\"" + digest + "\""))
                .andExpect(jsonPath("$.derivedFrom").doesNotExist());

        mockMvc.perform(get("/api/demo/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sha256 == '" + digest + "')].filename").value(hasItem("cat.png")));
    }

    @Test
    void traversalFilenameIsSanitizedToItsBasenameOnUpload() throws Exception {
        mockMvc.perform(multipart("/api/demo/files/upload")
                        .file(new MockMultipartFile("file", "../../etc/passwd",
                                "application/octet-stream", new byte[]{1, 2, 3})))
                .andExpect(status().is(201))
                .andExpect(jsonPath("$.filename").value("passwd")); // the path is GONE
    }

    @Test
    void pureTraversalFilenameIsRejectedWithTheValidationEnvelope() throws Exception {
        mockMvc.perform(multipart("/api/demo/files/upload")
                        .file(new MockMultipartFile("file", "..", "application/octet-stream",
                                new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.requestId").exists());
    }

    @Test
    void capCrossedMidStreamAnswersTheTyped413EnvelopeWithEvidence() throws Exception {
        byte[] over = new byte[1025];
        java.util.Arrays.fill(over, (byte) 'x');

        mockMvc.perform(multipart("/api/demo/files/upload")
                        .file(new MockMultipartFile("file", "big.bin", "application/octet-stream", over)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("payload_too_large"))
                .andExpect(jsonPath("$.error.requestId").exists())
                .andExpect(jsonPath("$.error.maxBytes").value(1024))
                .andExpect(jsonPath("$.error.receivedBytes").value(1025));
    }

    // ---- download: 200 / 304 / 206 / 416 ----

    @Test
    void fullDownloadCarriesValidatorsDispositionAndExactBytes() throws Exception {
        byte[] png = deterministicPng();
        String id = upload(png, "photo.png", "image/png");

        MvcResult started = mockMvc.perform(get("/api/demo/files/{id}", id))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + sha256Hex(png) + "\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, png.length))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"photo.png\"; filename*=UTF-8''photo.png"))
                .andReturn();
        assertArrayEquals(png, dispatched.getResponse().getContentAsByteArray());
    }

    @Test
    void unknownIdAnswersTheTyped404Envelope() throws Exception {
        mockMvc.perform(get("/api/demo/files/{id}", "99999999-9999-9999-9999-999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void matchingIfNoneMatchShortCircuitsTo304WithNoBody() throws Exception {
        byte[] png = deterministicPng();
        String id = upload(png, "photo.png", "image/png");
        String etag = "\"" + sha256Hex(png) + "\"";

        mockMvc.perform(get("/api/demo/files/{id}", id).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    void boundedRangeAnswers206WithExactSliceAndContentRange() throws Exception {
        byte[] png = deterministicPng();
        String id = upload(png, "photo.png", "image/png");

        MvcResult started = mockMvc.perform(get("/api/demo/files/{id}", id)
                        .header(HttpHeaders.RANGE, "bytes=0-99"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-99/" + png.length))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 100))
                .andReturn();
        assertArrayEquals(java.util.Arrays.copyOfRange(png, 0, 100),
                dispatched.getResponse().getContentAsByteArray());
    }

    @Test
    void suffixRangeFetchesTheTailExactly() throws Exception {
        byte[] png = deterministicPng();
        String id = upload(png, "photo.png", "image/png");

        MvcResult started = mockMvc.perform(get("/api/demo/files/{id}", id)
                        .header(HttpHeaders.RANGE, "bytes=-50"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes " + (png.length - 50) + "-" + (png.length - 1) + "/" + png.length))
                .andReturn();
        assertArrayEquals(java.util.Arrays.copyOfRange(png, png.length - 50, png.length),
                dispatched.getResponse().getContentAsByteArray());
    }

    @Test
    void unsatisfiableRangeAnswers416WithStarSlashContentRange() throws Exception {
        byte[] png = deterministicPng();
        String id = upload(png, "photo.png", "image/png");

        mockMvc.perform(get("/api/demo/files/{id}", id).header(HttpHeaders.RANGE, "bytes=999999-"))
                .andExpect(status().is(416))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */" + png.length))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));

        mockMvc.perform(get("/api/demo/files/{id}", id).header(HttpHeaders.RANGE, "bytes=50-10"))
                .andExpect(status().is(416));
    }

    @Test
    void unicodeFilenameTravelsViaRfc6266StarParameter() throws Exception {
        byte[] png = deterministicPng();
        String id = upload(png, "résumé–final.png", "image/png");

        MvcResult started = mockMvc.perform(get("/api/demo/files/{id}", id)
                        .queryParam("disposition", "attachment"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"rsumfinal.png\"; "
                                + "filename*=UTF-8''r%C3%A9sum%C3%A9%E2%80%93final.png"));
    }

    // ---- derived variants ----

    @Test
    void thumbnailStoresADerivedPngVariantWithinTheBox() throws Exception {
        byte[] png = deterministicPng();
        String originalId = upload(png, "cat.png", "image/png");

        String body = mockMvc.perform(post("/api/demo/files/{id}/thumbnail", originalId)
                        .queryParam("size", "16"))
                .andExpect(status().is(201))
                .andExpect(jsonPath("$.derivedFrom").value(originalId))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.filename").value("cat-16.png"))
                .andReturn().getResponse().getContentAsString();
        String thumbnailId = body.split("\"id\":\"")[1].split("\"")[0];

        MvcResult started = mockMvc.perform(get("/api/demo/files/{id}", thumbnailId))
                .andExpect(request().asyncStarted())
                .andReturn();
        byte[] thumbnailBytes = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnailBytes));
        assertTrue(decoded.getWidth() <= 16 && decoded.getHeight() <= 16,
                "the variant must fit the 16x16 box, got " + decoded.getWidth() + "x" + decoded.getHeight());
        assertTrue(decoded.getWidth() > 0 && decoded.getHeight() > 0, "and must not be empty");

        // a derived variant cannot chain
        mockMvc.perform(post("/api/demo/files/{id}/thumbnail", thumbnailId).queryParam("size", "16"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void thumbnailOfANonImageIsRefused() throws Exception {
        String id = upload("plain old words".getBytes(), "notes.txt", "text/plain");
        mockMvc.perform(post("/api/demo/files/{id}/thumbnail", id).queryParam("size", "16"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ---- the generated stream ----

    @Test
    void generatedStreamProducesExactContentLengthAndDeterministicPattern() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/demo/files/generated/{bytes}", 1000)
                        .queryParam("delayMs", "0"))
                .andExpect(request().asyncStarted())
                .andReturn();

        byte[] body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 1000))
                .andReturn().getResponse().getContentAsByteArray();

        assertEquals(body.length, 1000); // exact length, no chunking surprises
        for (int i = 0; i < body.length; i++) {
            if (body[i] != (byte) (i % 251)) {
                throw new AssertionError("pattern broke at byte " + i);
            }
        }
    }

    @Test
    void generatedStreamCapsAbsurdRequests() throws Exception {
        mockMvc.perform(get("/api/demo/files/generated/{bytes}", GeneratedBytePattern.MAX_BYTES + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }
}
