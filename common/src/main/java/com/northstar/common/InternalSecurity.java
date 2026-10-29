package com.northstar.common;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Configuration
public class InternalSecurity implements WebMvcConfigurer {
    @Value("${bank.internal-secret}") String secret;
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) throws Exception {
                String provided=req.getHeader("X-Internal-Key");
                if(provided==null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8))) {
                    res.setStatus(401); res.setContentType("application/json"); res.getWriter().write("{\"error\":\"Service authentication required\"}"); return false;
                }
                return true;
            }
        }).addPathPatterns("/internal/**");
    }
}
