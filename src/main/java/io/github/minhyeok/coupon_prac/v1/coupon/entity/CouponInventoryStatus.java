package io.github.minhyeok.coupon_prac.v1.coupon.entity;

/** 개별 재고의 발급 여부. */
public enum CouponInventoryStatus {
    /** 아직 사용자에게 발급하지 않은 재고. */
    AVAILABLE,
    /** 사용자 쿠폰으로 발급이 확정된 재고. */
    ISSUED
}
