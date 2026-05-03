package com.plantogether.expense.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FX cache wiring. {@code StringRedisTemplate} is auto-configured by Spring Boot via {@code
 * spring-boot-starter-data-redis}; we just provide a Clock and an ObjectMapper dedicated to FX
 * cache (de)serialisation.
 */
@Configuration
public class RedisConfig {

  @Bean
  public Clock systemClock() {
    return Clock.systemUTC();
  }
}
