package io.github.minhyeok.coupon_prac.v1.coupon.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 쿠폰을 사용한 결과와 실제 할인액. userCouponId 유니크 제약으로 쿠폰당 최대 한 건만 존재한다. */
@Entity
@Table(name = "coupon_usage_history")
@Getter
public class CouponUsageHistory {
    /** JPA가 조회한 행을 엔티티로 복원할 때 사용하는 기본 생성자. 직접 생성할 때는 제공된 생성 메서드를 사용한다. */
    protected CouponUsageHistory() {}

    /** 테이블의 숫자 기본키. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용 기록이 연결되는 사용자 쿠폰 ID. 중복 사용을 막는 유니크 키. */
    @Column(nullable = false, unique = true)
    private Long userCouponId;

    /** 사용 당시 확정된 할인금액. */
    @Column(nullable = false)
    private int discountAmount;

    /** 행 생성 UTC 시각. DATETIME 정밀도에 맞춰 초 단위로 저장한다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant createdAt;

    /** 마지막 변경 UTC 시각. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant updatedAt;

    /**
     * 실제 할인액과 사용 시각을 기록할 엔티티를 만든다. 쿠폰 사용 전환과 함께 저장한다.
     *
     * @param userCouponId 사용 완료한 사용자 쿠폰 ID; UNIQUE 제약으로 최대 한 건만 허용한다
     * @param amount 확정 할인 금액(원), 0 이상
     * @param now 실제 사용 UTC 시각; 생성·수정 시각에 초 단위로 저장한다
     * @return 아직 저장되지 않은 사용 기록
     */
    public static CouponUsageHistory used(Long userCouponId, int amount, Instant now) {
        CouponUsageHistory history = new CouponUsageHistory();
        history.userCouponId = userCouponId;
        history.discountAmount = amount;
        history.createdAt = now.truncatedTo(ChronoUnit.SECONDS);
        history.updatedAt = now.truncatedTo(ChronoUnit.SECONDS);
        return history;
    }
}
