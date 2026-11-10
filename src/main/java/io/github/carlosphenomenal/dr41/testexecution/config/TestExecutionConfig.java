package io.github.carlosphenomenal.dr41.testexecution.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TestExecutionConfig {

    @Bean(name = "k6Executor", destroyMethod = "shutdown")
    public ExecutorService testExecutor() {
        ThreadFactory virtualFactory = Thread.ofVirtual()
                .name("k6-test-worker-", 0)
                .factory();

        return Executors.newThreadPerTaskExecutor(virtualFactory);
    }
}
