package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import java.time.Instant;

/**
 * 소유 쿠폰 상세와 선택적 사용 정보.
 *
 * @param coupon 쿠폰 기본 정보
 * @param discountAmount 사용 당시 할인액(원); 미사용이면 null
 * @param usedAt UTC 사용 시각; 미사용이면 null
 */
public record CouponDetail(CouponView coupon, Integer discountAmount, Instant usedAt) {}
