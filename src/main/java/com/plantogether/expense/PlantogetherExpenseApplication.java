package com.plantogether.expense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class PlantogetherExpenseApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlantogetherExpenseApplication.class, args);
    }
}
