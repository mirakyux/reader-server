package com.htmake.reader.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "reader.server.cors")
class CorsConfig {
    var allowedOrigins: List<String> = listOf(
        "http://localhost:5173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://127.0.0.1:4173"
    )
    var allowCredentials: Boolean = true
}
