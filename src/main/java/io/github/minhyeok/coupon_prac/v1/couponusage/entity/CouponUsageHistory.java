package io.github.minhyeok.coupon_prac.v1.couponusage.entity;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.seconds;
@Entity @Table(name = "coupon_usage_history") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponUsageHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private Long userCouponId;
    @Column(nullable = false) private int discountAmount;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant createdAt;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant updatedAt;
    public static CouponUsageHistory used(Long userCouponId, int amount, Instant now) {
        var history = new CouponUsageHistory();
        history.userCouponId = userCouponId; history.discountAmount = amount;
        history.createdAt = seconds(now); history.updatedAt = seconds(now);
        return history;
    }
}
