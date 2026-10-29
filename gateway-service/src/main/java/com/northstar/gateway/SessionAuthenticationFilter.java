package com.northstar.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String identityUrl;
    private final String internalSecret;

    SessionAuthenticationFilter(
            ObjectMapper json,
            @Value("${bank.services.identity-url}") String identityUrl,
            @Value("${bank.internal-secret}") String internalSecret) {
        this.json = json;
        this.identityUrl = identityUrl;
        this.internalSecret = internalSecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.equals("/api/login")
                || path.equals("/api/users")
                || path.equals("/api/users/verify")
                || path.equals("/api/users/resend")
                || path.equals("/api/v1/auth/signup")
                || path.equals("/api/csrf")
                || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = cookieValue(request, "bank_session");
        if (token == null || token.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            var downstream = client.send(HttpRequest.newBuilder(URI.create(identityUrl + "/internal/session"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Key", internalSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of("token", token))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (downstream.statusCode() == 200) {
                JsonNode user = json.readTree(downstream.body());
                String role = user.path("role").asText();
                if (!role.equals("CUSTOMER") && !role.equals("ADMIN")) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken(
                        user.path("id").asText(), null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        } catch (Exception exception) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(name)) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
