package io.github.minhyeok.coupon_prac.v1.config;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class ClockConfig {
    @Bean public Clock couponClock() { return Clock.systemUTC(); }
}
