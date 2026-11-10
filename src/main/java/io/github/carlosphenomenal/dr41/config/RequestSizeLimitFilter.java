package io.github.carlosphenomenal.dr41.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Rejects oversized plan uploads from the Content-Length header, before the body is buffered. */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {
    private final long maxBytes;

    public RequestSizeLimitFilter(AppProperties props) {
        this.maxBytes = props.limits().maxPlanBytes() + 16_384L; // slack for multipart framing
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/tests");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, @NonNull HttpServletResponse res, @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (req.getContentLengthLong() > maxBytes) {
            res.setStatus(413);
            res.setContentType("application/json");
            res.getOutputStream().write(("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Plan exceeds "
                    + maxBytes + " bytes\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(req, res);
    }
}
