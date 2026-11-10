package io.github.carlosphenomenal.dr41.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration
public class ExecutionConfig {

    /** One virtual thread per run / stderr pump / probe. They spend their lives blocked on I/O. */
    @Bean(name = "runExecutor", destroyMethod = "shutdownNow")
    public ExecutorService runExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dr41-run-", 0).factory());
    }

    /** One tiny platform thread shared by resource sampling, watchdogs, forced kills and housekeeping. */
    @Bean(name = "housekeepingScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService housekeepingScheduler() {
        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "dr41-scheduler");
            t.setDaemon(true);
            return t;
        });
        s.setRemoveOnCancelPolicy(true);
        return s;
    }
}
