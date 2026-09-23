package io.github.minhyeok.coupon_prac.v1.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("scheduler")
@EnableScheduling
public class SchedulingConfig {
}
