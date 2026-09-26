package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import java.time.LocalDateTime;

/**
 * 커밋된 발급 처리 결과. 기존 쿠폰을 반환할 때도 최초 발급 정보를 유지한다.
 *
 * @param userCouponId 사용자 쿠폰 기본키
 * @param couponCode 고정 UUID의 문자열 표현
 * @param issuedAt 최초 발급 한국 시각
 */
public record CouponIssueResult(Long userCouponId, String couponCode, LocalDateTime issuedAt) {}
