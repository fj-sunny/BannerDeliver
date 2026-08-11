package com.bannerdeliver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Banner 投放系统 Spring Boot 启动类。 */
@EnableScheduling
@SpringBootApplication
public class App {

    /** 应用入口。 */
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
