package com.ecom.notificationservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

// WebFluxConfigurer — not WebMvcConfigurer (that's Spring MVC)
@Configuration
public class CorsConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/notifications/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET")
                .allowedHeaders("*");
    }
}
