package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import java.time.LocalDateTime;

/**
 * 1회 사용을 확정한 결과.
 *
 * @param userCouponId 사용 처리된 사용자 쿠폰 ID
 * @param usageHistoryId 생성된 사용 기록 ID
 * @param discountAmount 실제 할인 금액(원)
 * @param usedAt 한국 사용 시각
 */
public record UseResult(
        Long userCouponId, Long usageHistoryId, int discountAmount, LocalDateTime usedAt) {}
