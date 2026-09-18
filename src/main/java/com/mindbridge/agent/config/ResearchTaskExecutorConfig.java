package com.mindbridge.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
/**
 * 研究任务异步线程池。worker-count 来自 mindbridge.task.worker-count。
 */
public class ResearchTaskExecutorConfig {

    public static final String RESEARCH_TASK_WORKER_EXECUTOR = "researchTaskWorkerExecutor";

    @Bean(name = RESEARCH_TASK_WORKER_EXECUTOR)
    public TaskExecutor researchTaskWorkerExecutor(MindBridgeProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int workers = Math.max(1, properties.getTask().getWorkerCount());
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("research-task-");
        executor.initialize();
        return executor;
    }
}
