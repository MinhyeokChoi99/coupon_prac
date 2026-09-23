package io.github.minhyeok.coupon_prac.v1.usercoupon.repository;
import java.time.Instant;
import java.util.*;
import io.github.minhyeok.coupon_prac.v1.usercoupon.entity.UserCoupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {
    Optional<UserCoupon> findByEventIdAndUserId(Long eventId, Long userId);
    Optional<UserCoupon> findByIdAndUserId(Long id, Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserCoupon u where u.id = :id and u.userId = :userId")
    Optional<UserCoupon> lockOwned(Long id, Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserCoupon u where u.qrToken = :token")
    Optional<UserCoupon> lockByQrToken(UUID token);
    // READ_COMMITTED issuance serializes each user's daily row before this lookup.
    Page<UserCoupon> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
    long countByEventId(Long eventId);
    long countByEventIdIn(Collection<Long> eventIds);
    @Modifying @Query("delete from UserCoupon u where u.eventId = :eventId")
    int deleteAllByEventId(Long eventId);
    // Recheck expiry at the DB update, after all application/lock waits.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE user_coupon SET status = 'USED', qr_token = NULL, qr_expires_at = NULL, updated_at = :now
        WHERE id = :id AND status = 'ISSUED' AND qr_token = :token
          AND usable_start_time <= :now AND usable_end_time > :now AND qr_expires_at > :now
        """, nativeQuery = true)
    int consume(Long id, byte[] token, Instant now);
}
