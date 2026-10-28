package com.teamnative.gymup.core

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfiguration(
    private val jwtAuthenticationInterceptor: JwtAuthenticationInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(jwtAuthenticationInterceptor)
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/health", "/api/v1/auth/dev-token", "/api/v1/photos/*/download")
    }
}
