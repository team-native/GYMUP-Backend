package com.teamnative.backend.global.logging;

import com.teamnative.backend.global.logging.AppLogDto.ServerLogData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class ServerLifecycleLogger {

    private final AppLogger appLogger;
    private final Environment environment;
    private final String applicationName;
    private final String serverPort;

    public ServerLifecycleLogger(
            AppLogger appLogger,
            Environment environment,
            @Value("${spring.application.name:backend}") String applicationName,
            @Value("${server.port:8080}") String serverPort
    ) {
        this.appLogger = appLogger;
        this.environment = environment;
        this.applicationName = applicationName;
        this.serverPort = serverPort;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logServerStarted() {
        appLogger.info(new AppLogDto(
                LogLevel.INFO,
                LogEvent.SERVER_STARTED,
                "Server started successfully.",
                null,
                serverData(),
                null,
                null
        ));
    }

    @EventListener(ContextClosedEvent.class)
    public void logServerStopped() {
        appLogger.info(new AppLogDto(
                LogLevel.INFO,
                LogEvent.SERVER_STOPPED,
                "Server stopped gracefully.",
                null,
                serverData(),
                null,
                null
        ));
    }

    private ServerLogData serverData() {
        return new ServerLogData(
                applicationName,
                serverPort,
                Arrays.asList(environment.getActiveProfiles())
        );
    }
}
