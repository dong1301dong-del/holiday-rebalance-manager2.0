package com.tiaoxiu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调休管家后端应用启动类。
 *
 * <p>位于根包 {@code com.tiaoxiu} 下，Spring Boot 由此开始向下扫描
 * controller / service / repository / config / task 等组件。
 *
 * <p>开启 {@code @EnableScheduling} 是为了启用 {@link com.tiaoxiu.task.ReminderScheduler} 中的定时任务。
 */
@SpringBootApplication
@EnableScheduling
public class TiaoxiuApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行启动参数，由 Spring Boot 解析（如 {@code --spring.profiles.active=prod}）
     */
    public static void main(String[] args) {
        SpringApplication.run(TiaoxiuApplication.class, args);
    }
}
