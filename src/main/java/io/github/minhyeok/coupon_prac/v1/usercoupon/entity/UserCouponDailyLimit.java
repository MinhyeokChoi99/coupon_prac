package io.github.minhyeok.coupon_prac.v1.usercoupon.entity;
import java.time.*;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name = "coupon_daily_limit") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCouponDailyLimit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false) private int issuedCount;
    @Column(insertable = false, updatable = false) private LocalDate limitDate;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant createdAt;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant updatedAt;
}
