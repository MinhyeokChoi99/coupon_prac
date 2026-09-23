package io.github.minhyeok.coupon_prac.v1.user.repository;
import io.github.minhyeok.coupon_prac.v1.user.entity.CouponUser;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CouponUserRepository extends JpaRepository<CouponUser, Long> {}
