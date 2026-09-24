package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.CouponUsageHistory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 쿠폰 한 장에 대응하는 선택적 사용 기록을 조회한다. */
public interface CouponUsageHistoryRepository extends JpaRepository<CouponUsageHistory, Long> {
    /**
     * 쿠폰당 최대 한 건인 선택적 사용 기록을 찾는다.
     *
     * @param userCouponId 사용자 쿠폰 ID
     * @return 사용 기록 또는 미사용이면 빈 Optional
     */
    Optional<CouponUsageHistory> findByUserCouponId(Long userCouponId);

    /**
     * 사용 기록 중복 방지 제약을 검증하기 위해 행 수를 센다.
     *
     * @param userCouponId 검증할 사용자 쿠폰 ID
     * @return DB UNIQUE 제약에 의해 0 또는 1
     */
    long countByUserCouponId(Long userCouponId);
}
