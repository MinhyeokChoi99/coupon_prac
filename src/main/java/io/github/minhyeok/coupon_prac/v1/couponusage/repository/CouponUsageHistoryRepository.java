package io.github.minhyeok.coupon_prac.v1.couponusage.repository;
import java.util.Optional;
import io.github.minhyeok.coupon_prac.v1.couponusage.entity.CouponUsageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CouponUsageHistoryRepository extends JpaRepository<CouponUsageHistory, Long> {
    Optional<CouponUsageHistory> findByUserCouponId(Long userCouponId);
    long countByUserCouponId(Long userCouponId);
}
