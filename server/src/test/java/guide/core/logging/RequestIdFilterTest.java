package guide.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesEchoesAndPublishesWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> idInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> idInsideChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        String echoed = response.getHeader(RequestIdFilter.HEADER);
        assertTrue(echoed != null && !echoed.isBlank(), "a request id must be generated");
        assertEquals(echoed, idInsideChain.get(), "MDC must carry the id inside the chain");
        assertEquals(echoed, request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "MDC must be cleaned after the chain");
    }

    @Test
    void honorsAndEchoesAWelFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(RequestIdFilter.HEADER, "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> assertEquals("req-123", MDC.get(RequestIdFilter.MDC_KEY)));

        assertEquals("req-123", response.getHeader(RequestIdFilter.HEADER));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void replacesAnUnsafeIncomingIdWithAGeneratedOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(RequestIdFilter.HEADER, "bad\r\nSet-Cookie: x=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        String echoed = response.getHeader(RequestIdFilter.HEADER);
        assertNotEquals("bad\r\nSet-Cookie: x=1", echoed);
        assertTrue(echoed != null && !echoed.isBlank());
    }

    @Test
    void cleansMdcEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilter(request, response,
                (req, res) -> {
                    throw new ServletException("boom");
                }));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "MDC must be cleaned even on the exception path");
    }
}
