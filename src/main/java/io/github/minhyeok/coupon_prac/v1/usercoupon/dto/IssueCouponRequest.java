package io.github.minhyeok.coupon_prac.v1.usercoupon.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record IssueCouponRequest(
        @NotNull @Positive Long userId
) {
}
