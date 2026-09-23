package io.github.minhyeok.coupon_prac.v1.usercoupon.repository;
import java.time.*;
import java.util.Optional;
import io.github.minhyeok.coupon_prac.v1.usercoupon.entity.UserCouponDailyLimit;
import org.springframework.data.jpa.repository.*;
public interface UserCouponDailyLimitRepository extends JpaRepository<UserCouponDailyLimit, Long> {
    // No INSERT IGNORE: unrelated FK/data errors must not be swallowed.
    @Modifying
    @Query(value = """
        INSERT INTO coupon_daily_limit (user_id, issued_count, created_at, updated_at)
        VALUES (:userId, 0, :now, :now)
        ON DUPLICATE KEY UPDATE id = id
        """, nativeQuery = true)
    int createIfAbsent(Long userId, Instant now);
    @Modifying
    @Query("""
        update UserCouponDailyLimit d set d.issuedCount = d.issuedCount + 1, d.updatedAt = :now
        where d.userId = :userId and d.limitDate = :date and d.issuedCount < 3
        """)
    int incrementIfBelowLimit(Long userId, LocalDate date, Instant now);
    Optional<UserCouponDailyLimit> findByUserIdAndLimitDate(Long userId, LocalDate date);
    @Modifying
    @Query("delete from UserCouponDailyLimit d where d.userId between :startUserId and :endUserId")
    int deleteAllByUserIdRange(long startUserId, long endUserId);
    long countByUserIdBetween(long startUserId, long endUserId);
}
