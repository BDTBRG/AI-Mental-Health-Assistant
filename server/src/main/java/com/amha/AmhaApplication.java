package com.amha;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.amha.mapper")
@EnableAsync
@EnableScheduling
public class AmhaApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmhaApplication.class, args);
    }
}
