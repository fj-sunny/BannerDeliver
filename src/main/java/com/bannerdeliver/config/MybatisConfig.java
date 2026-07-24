package com.bannerdeliver.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.bannerdeliver.mapper")
public class MybatisConfig {
}
