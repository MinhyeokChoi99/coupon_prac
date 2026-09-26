package io.github.minhyeok.coupon_prac.v1.coupon.entity;

import io.github.minhyeok.coupon_prac.v1.coupon.exception.*;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/** 사용자에게 발급된 쿠폰 한 장. 쿠폰 코드는 고정하고 사용 인증용 QR 토큰만 교체한다. */
@Entity
@Table(name = "user_coupon")
@Getter
public class UserCoupon {
    /** JPA가 조회한 행을 엔티티로 복원할 때 사용하는 기본 생성자. 직접 생성할 때는 제공된 생성 메서드를 사용한다. */
    protected UserCoupon() {}

    /** 테이블의 숫자 기본키. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 쿠폰 재고 또는 발급이 속한 이벤트 ID. */
    @Column(nullable = false)
    private Long eventId;

    /** 쿠폰을 소유하거나 일일 한도가 적용되는 사용자 ID. */
    @Column(nullable = false)
    private Long userId;

    /** 쿠폰의 고정 UUID 식별자. DB에는 BINARY(16)으로 저장한다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID couponCode;

    /** QR 교체 횟수. 미생성 상태는 0. */
    @Column(nullable = false)
    private int qrVersion;

    /** 현재 사용 가능한 QR 토큰. 최초 발급 시와 사용 완료 후에는 null. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(columnDefinition = "BINARY(16)")
    private UUID qrToken;

    /** QR 사용 만료 한국 시각. 토큰이 있으면 함께 설정한다. */
    @Column(columnDefinition = "DATETIME")
    private LocalDateTime qrExpiresAt;

    /** 해당 도메인의 현재 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserCouponStatus status;

    /** 쿠폰 사용 가능 시작. 캠페인은 일일 시각, 이벤트·쿠폰은 한국 시각을 저장한다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime usableStartTime;

    /** 쿠폰 사용 가능 종료. 이 시각부터는 사용할 수 없다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime usableEndTime;

    /** 행 생성 한국 시각. Java 원본 정밀도를 유지하며 DB DATETIME 저장은 기본 정밀도 처리에 맡긴다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    /** 마지막 변경 한국 시각. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime updatedAt;

    /**
     * 선점한 재고의 코드·사용 기간을 복사해 소유 쿠폰을 만든다. QR 버전은 0, 토큰과 만료 시각은 null이다.
     *
     * @param inventory 발급 트랜잭션에서 독점 선점한 재고
     * @param userId 소유 사용자 ID
     * @param now 발급 한국 시각; 생성·수정 시각에 그대로 기록한다
     * @return 아직 저장되지 않은 ISSUED 쿠폰
     */
    public static UserCoupon issue(CouponInventory inventory, Long userId, LocalDateTime now) {
        UserCoupon coupon = new UserCoupon();
        coupon.eventId = inventory.getEventId();
        coupon.userId = userId;
        coupon.couponCode = inventory.getCouponCode();
        coupon.qrVersion = 0;
        coupon.status = UserCouponStatus.ISSUED;
        coupon.usableStartTime = inventory.getUsableStartTime();
        coupon.usableEndTime = inventory.getUsableEndTime();
        coupon.createdAt = now;
        coupon.updatedAt = now;
        return coupon;
    }

    /**
     * ISSUED 상태이며 사용 시작 이상·종료 미만인지 검사한다.
     *
     * @param now 잠금 획득 후 확인한 한국 시각
     * @throws CouponException 미사용 상태가 아니거나 사용 기간 밖인 경우
     */
    public void requireUsable(LocalDateTime now) {
        if (status != UserCouponStatus.ISSUED
                || now.isBefore(usableStartTime)
                || !now.isBefore(usableEndTime))
            throw new CouponException(CouponErrorCode.COUPON_NOT_USABLE);
    }

    /**
     * 고정 쿠폰 코드는 유지하고 QR만 새 난수 UUID로 덮어쓴다. 버전을 증가시키며 이전 토큰은 무효다.
     *
     * <p>만료는 현재 시각+60초와 사용 종료 중 빠른 시각이다.
     *
     * @param now QR 생성 한국 시각
     * @throws CouponException 쿠폰이 사용 불가한 경우
     * @throws ArithmeticException QR 버전이 정수 최댓값을 초과한 경우
     */
    public void rotateQr(LocalDateTime now) {
        requireUsable(now);
        qrToken = UUID.randomUUID();
        qrVersion = Math.incrementExact(qrVersion);
        LocalDateTime expiry = now.plusSeconds(60);
        qrExpiresAt = expiry.isBefore(usableEndTime) ? expiry : usableEndTime;
        updatedAt = now;
    }

    /**
     * 사용 가능 여부를 검사하고 스캔한 QR이 현재 토큰과 같으며 만료 전인지 확인한다.
     *
     * @param token 스캔한 QR UUID
     * @param now 잠금 획득 후 확인한 한국 시각
     * @throws CouponException 사용 불가, 미생성/교체된 토큰 또는 QR 만료인 경우
     */
    public void requireValidQr(UUID token, LocalDateTime now) {
        requireUsable(now);
        if (qrToken == null
                || !qrToken.equals(token)
                || qrExpiresAt == null
                || !now.isBefore(qrExpiresAt))
            throw new CouponException(CouponErrorCode.INVALID_QR);
    }

    /**
     * 미사용 쿠폰의 시간 경과를 조회 응답에만 반영한다. 엔티티와 DB는 변경하지 않는다.
     *
     * @param now 조회 기준 한국 시각
     * @return ISSUED이면서 사용 종료 이상이면 EXPIRED, 그 외 저장된 상태
     */
    public UserCouponStatus effectiveStatus(LocalDateTime now) {
        return status == UserCouponStatus.ISSUED && !now.isBefore(usableEndTime)
                ? UserCouponStatus.EXPIRED
                : status;
    }
}
