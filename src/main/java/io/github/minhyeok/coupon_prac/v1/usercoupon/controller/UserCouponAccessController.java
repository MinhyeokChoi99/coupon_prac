package io.github.minhyeok.coupon_prac.v1.usercoupon.controller;
import java.util.UUID;
import io.github.minhyeok.coupon_prac.v1.usercoupon.service.UserCouponAccessService;
import io.github.minhyeok.coupon_prac.v1.usercoupon.service.UserCouponAccessService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @RequiredArgsConstructor
public class UserCouponAccessController {
    private final UserCouponAccessService service;
    // Practice API: userId/ownerId must come from authenticated principals in production.
    @PostMapping("/user-coupons/{id}/qr")
    public QrResult qr(@PathVariable @Positive Long id, @Valid @RequestBody QrRequest request) {
        return service.rotateQr(id, request.userId());
    }
    @PostMapping("/coupon-usages")
    public UseResult use(@Valid @RequestBody UseRequest request) {
        return service.use(request.qrToken(), request.storeId(), request.ownerId(),
                request.orderAmount(), request.menuId(), request.menuAmount());
    }
    @GetMapping("/users/{userId}/coupons")
    public Page<CouponView> list(@PathVariable @Positive Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(userId, page, size);
    }
    @GetMapping("/user-coupons/{id}")
    public CouponDetail detail(@PathVariable @Positive Long id, @RequestParam @Positive Long userId) {
        return service.detail(id, userId);
    }
    public record QrRequest(@NotNull @Positive Long userId) {}
    public record UseRequest(@NotNull UUID qrToken, @NotNull @Positive Long storeId,
            @NotNull @Positive Long ownerId, @Positive int orderAmount,
            @Positive Long menuId, @Positive Integer menuAmount) {}
}
