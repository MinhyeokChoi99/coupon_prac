package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.UserCouponStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * QR 토큰을 노출하지 않는 쿠폰함 조회 정보.
 *
 * @param id 사용자 쿠폰 ID
 * @param eventId 발급 이벤트 ID
 * @param couponCode 발급 후 바뀌지 않는 고정 UUID
 * @param status 시간 경과를 반영한 조회 상태
 * @param usableStartTime 사용 시작 UTC 시각(포함)
 * @param usableEndTime 사용 종료 UTC 시각(미포함)
 * @param issuedAt 최초 발급 UTC 시각
 */
public record CouponView(
        Long id,
        Long eventId,
        UUID couponCode,
        UserCouponStatus status,
        Instant usableStartTime,
        Instant usableEndTime,
        Instant issuedAt) {}
