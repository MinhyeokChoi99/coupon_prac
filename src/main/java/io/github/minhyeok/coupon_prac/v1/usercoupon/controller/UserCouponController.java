package io.github.minhyeok.coupon_prac.v1.usercoupon.controller;

import io.github.minhyeok.coupon_prac.v1.usercoupon.dto.IssueCouponCommand;
import io.github.minhyeok.coupon_prac.v1.usercoupon.dto.IssueCouponRequest;
import io.github.minhyeok.coupon_prac.v1.usercoupon.dto.IssueCouponResponse;
import io.github.minhyeok.coupon_prac.v1.usercoupon.service.UserCouponIssuanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coupon-events")
public class UserCouponController {

    private final UserCouponIssuanceService userCouponIssuanceService;

    public UserCouponController(UserCouponIssuanceService userCouponIssuanceService) {
        this.userCouponIssuanceService = userCouponIssuanceService;
    }

    @PostMapping("/{eventId}/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueCouponResponse issue(
            @PathVariable Long eventId,
            @Valid @RequestBody IssueCouponRequest request
    ) {
        var result = userCouponIssuanceService.issue(new IssueCouponCommand(eventId, request.userId()));
        return IssueCouponResponse.from(result);
    }
}
