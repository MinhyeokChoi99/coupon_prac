package io.github.minhyeok.coupon_prac.v1.usercoupon.entity;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import io.github.minhyeok.coupon_prac.v1.couponinventory.entity.CouponInventory;
import io.github.minhyeok.coupon_prac.v1.common.exception.*;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.seconds;
@Entity @Table(name = "user_coupon") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCoupon {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long eventId;
    @Column(nullable = false) private Long userId;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(nullable = false, columnDefinition = "BINARY(16)") private UUID couponCode;
    @Column(nullable = false) private int qrVersion;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(columnDefinition = "BINARY(16)") private UUID qrToken;
    @Column(columnDefinition = "DATETIME") private Instant qrExpiresAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private UserCouponStatus status;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant usableStartTime;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant usableEndTime;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant createdAt;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant updatedAt;

    public static UserCoupon issue(CouponInventory inventory, Long userId, Instant now) {
        var coupon = new UserCoupon();
        coupon.eventId = inventory.getEventId(); coupon.userId = userId; coupon.couponCode = inventory.getCouponCode();
        coupon.qrVersion = 0; coupon.status = UserCouponStatus.ISSUED;
        coupon.usableStartTime = inventory.getUsableStartTime(); coupon.usableEndTime = inventory.getUsableEndTime();
        coupon.createdAt = seconds(now); coupon.updatedAt = seconds(now);
        return coupon;
    }
    public void requireUsable(Instant now) {
        if (status != UserCouponStatus.ISSUED || now.isBefore(usableStartTime) || !now.isBefore(usableEndTime))
            throw new CouponException(CouponErrorCode.COUPON_NOT_USABLE);
    }
    public void rotateQr(Instant now) {
        requireUsable(now);
        qrToken = UUID.randomUUID(); qrVersion = Math.incrementExact(qrVersion);
        Instant expiry = seconds(now).plusSeconds(60);
        qrExpiresAt = expiry.isBefore(usableEndTime) ? expiry : usableEndTime;
        updatedAt = seconds(now);
    }
    public void requireValidQr(UUID token, Instant now) {
        requireUsable(now);
        if (qrToken == null || !qrToken.equals(token) || qrExpiresAt == null || !now.isBefore(qrExpiresAt))
            throw new CouponException(CouponErrorCode.INVALID_QR);
    }
    public UserCouponStatus effectiveStatus(Instant now) {
        return status == UserCouponStatus.ISSUED && !now.isBefore(usableEndTime) ? UserCouponStatus.EXPIRED : status;
    }
}
