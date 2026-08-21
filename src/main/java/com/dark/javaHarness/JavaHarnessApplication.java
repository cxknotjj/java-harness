package com.dark.javaHarness;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.dark.javaHarness.core.memory")
public class JavaHarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaHarnessApplication.class, args);
    }

}
