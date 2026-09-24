package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import java.time.Instant;

/**
 * HTTP 201 발급 응답. 멱등 재응답도 동일한 쿠폰 정보를 반환한다.
 *
 * @param userCouponId 사용자 쿠폰 기본키
 * @param couponCode 고정 UUID 문자열; QR 토큰과 다르다
 * @param issuedAt 최초 발급 UTC 시각
 */
public record IssueCouponResponse(Long userCouponId, String couponCode, Instant issuedAt) {

    /**
     * 내부 발급 결과를 HTTP 응답으로 변환한다. 기존 발급의 원래 발급 시각을 유지한다.
     *
     * @param result 커밋이 완료된 발급 결과
     * @return 쿠폰 ID·UUID 문자열·UTC 발급 시각
     */
    public static IssueCouponResponse from(CouponIssueResult result) {
        return new IssueCouponResponse(
                result.userCouponId(), result.couponCode(), result.issuedAt());
    }
}
