package com.amha;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.amha.mapper")
public class AmhaApplication {
    public static void main(String[] args) {
        SpringApplication.run(AmhaApplication.class, args);
    }
}
