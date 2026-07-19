package com.dating.match.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring TaskScheduler 配置 — 用于 DH 延迟匹配 + 通用调度.
 */
@Configuration
@EnableScheduling
public class TaskSchedulerConfig {

    /**
     * Spring TaskScheduler — DhDelayedMatchService 进程内 15s-2min 延迟调度.
     *
     * <p>10k DAU × 5 右划/天 × 50% DH = 25k/天 ≈ 0.3/秒;任何时刻 in-flight < 600.
     */
    @Bean(name = "matchTaskScheduler")
    public ThreadPoolTaskScheduler matchTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("match-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        scheduler.initialize();
        return scheduler;
    }
}
