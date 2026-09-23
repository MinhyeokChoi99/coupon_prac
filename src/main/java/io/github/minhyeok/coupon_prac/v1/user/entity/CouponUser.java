package io.github.minhyeok.coupon_prac.v1.user.entity;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.seconds;
@Entity @Table(name = "`user`") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 255) private String providerUserId;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant createdAt;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant updatedAt;
    public static CouponUser active(String providerUserId, Instant now) {
        var user = new CouponUser();
        user.providerUserId = providerUserId; user.status = "ACTIVE";
        user.createdAt = seconds(now); user.updatedAt = seconds(now);
        return user;
    }
    public boolean isActive() { return "ACTIVE".equals(status); }
}
