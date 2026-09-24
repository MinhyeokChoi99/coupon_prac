package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * QR 교체를 요청하는 사용자 정보.
 *
 * @param userId 쿠폰 소유 사용자 ID, 필수 양수
 */
public record QrRequest(@NotNull @Positive Long userId) {}
