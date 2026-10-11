package com.htmake.reader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionProfileTests {
    @Test
    void productionProfileLoadsForAContainerLaunch() {
        var application = new SpringApplication(ProfileConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        try (var context = application.run("--spring.profiles.active=prod")) {
            var environment = context.getEnvironment();
            assertTrue(environment.matchesProfiles("prod"));
            assertEquals("storage", environment.getRequiredProperty("reader.app.storagePath"));
            assertEquals("8080", environment.getRequiredProperty("reader.server.port"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProfileConfiguration { }
}
