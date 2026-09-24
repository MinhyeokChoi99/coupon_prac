package io.github.minhyeok.coupon_prac.v1.coupon.dto;

/**
 * 쿠폰 발급 서비스에 전달할 입력.
 *
 * @param eventId 발급받을 쿠폰 이벤트 ID
 * @param userId 발급 사용자 ID
 */
public record IssueCouponCommand(Long eventId, Long userId) {}
