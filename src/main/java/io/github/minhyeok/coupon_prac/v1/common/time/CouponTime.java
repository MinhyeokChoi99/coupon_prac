package io.github.minhyeok.coupon_prac.v1.common.time;
import java.time.*;
import java.time.temporal.ChronoUnit;
public final class CouponTime {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private CouponTime() {}
    public static Instant seconds(Instant value) { return value.truncatedTo(ChronoUnit.SECONDS); }
    public static LocalDate businessDate(Instant value) { return value.atZone(BUSINESS_ZONE).toLocalDate(); }
}
