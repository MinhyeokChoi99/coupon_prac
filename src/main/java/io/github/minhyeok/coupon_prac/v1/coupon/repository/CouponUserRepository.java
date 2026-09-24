package io.github.minhyeok.coupon_prac.v1.coupon.repository;

import io.github.minhyeok.coupon_prac.v1.coupon.entity.CouponUser;

import org.springframework.data.jpa.repository.JpaRepository;

/** 쿠폰 발급 자격 확인에 필요한 사용자 정보를 조회한다. */
public interface CouponUserRepository extends JpaRepository<CouponUser, Long> {}
