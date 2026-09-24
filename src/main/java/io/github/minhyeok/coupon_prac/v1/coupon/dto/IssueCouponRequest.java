package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 발급 HTTP 요청 본문. 운영에서는 userId를 인증 정보에서 얻어야 한다.
 *
 * @param userId 발급받는 사용자 ID, 필수 양수
 */
public record IssueCouponRequest(@NotNull @Positive Long userId) {}
