package com.an.feige;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 飞鸽传书 独立后端入口（JDK8 / SpringBoot）。
 */
@SpringBootApplication
@MapperScan("com.an.feige.**.mapper")
public class FeigeApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(FeigeApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(FeigeApplication.class, args);
    }
}
