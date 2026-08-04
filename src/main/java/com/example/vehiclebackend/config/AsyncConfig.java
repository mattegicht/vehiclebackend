package com.example.vehiclebackend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Pool for outbound mail, used by {@code @Async("mailExecutor")}. Deliberately
     * small and bounded: SMTP can block for MAIL_TIMEOUT_MS per phase, and the only
     * sender is a public, unauthenticated endpoint, so an unbounded pool would just
     * move a denial-of-service from the DB connection pool to the thread count.
     *
     * <p>When the queue is full the send is dropped with a log line rather than
     * throwing or running on the caller's thread — throwing would surface from an
     * after-commit callback on a request that already succeeded, and running inline
     * would reintroduce the blocking this executor exists to avoid. Mail is already
     * best-effort here ({@code PasswordResetService} swallows and logs MailException).
     */
    @Bean("mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("mail-");
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Mail executor saturated — dropping an outbound email. "
                        + "Is the SMTP host unreachable?"));
        executor.initialize();
        return executor;
    }
}
