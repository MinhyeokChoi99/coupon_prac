package io.github.minhyeok.coupon_prac.v1.coupon.entity;

/** 사용자 쿠폰의 상태. 조회 시 사용 기간이 지난 ISSUED 쿠폰은 EXPIRED로 표시한다. */
public enum UserCouponStatus {
    /** 발급받았지만 사용하지 않은 쿠폰. */
    ISSUED,
    /** 한 번 사용을 완료한 쿠폰. */
    USED,
    /** 사용 기간이 종료된 쿠폰. v1 조회에서 동적으로 표시한다. */
    EXPIRED,
    /** 제거 상태 표현. v1에서는 제거 API를 제공하지 않는다. */
    REMOVED
}
