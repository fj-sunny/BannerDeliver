package com.bannerdeliver;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.bannerdeliver.mapper")
@SpringBootApplication
public class BannerDeliverApplication {

    public static void main(String[] args) {
        SpringApplication.run(BannerDeliverApplication.class, args);
    }
}
