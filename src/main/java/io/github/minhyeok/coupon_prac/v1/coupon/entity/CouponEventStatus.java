package io.github.minhyeok.coupon_prac.v1.coupon.entity;

/** 미리 적재된 쿠폰 이벤트의 운영 상태. 발급 가능 시각은 상태와 별도로 검사한다. */
public enum CouponEventStatus {
    /** 상태상 발급 허용. 실제 시작·종료 시각은 별도 검사한다. */
    ACTIVE,
    /** 운영 중단 상태. */
    PAUSED,
    /** 품절 상태 표현. 현재 v1은 발급 중 이 상태로 자동 전환하지 않는다. */
    SOLD_OUT,
    /** 종료 상태. 자동 종료 스케줄러는 없다. */
    ENDED
}
