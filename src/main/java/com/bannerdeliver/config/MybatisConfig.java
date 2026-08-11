package com.bannerdeliver.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** MyBatis Mapper 接口扫描配置。 */
@Configuration
@MapperScan("com.bannerdeliver.mapper")
public class MybatisConfig {
}
