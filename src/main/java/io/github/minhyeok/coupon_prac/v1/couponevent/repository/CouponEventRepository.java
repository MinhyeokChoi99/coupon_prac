package io.github.minhyeok.coupon_prac.v1.couponevent.repository;
import java.time.*;
import java.util.*;
import io.github.minhyeok.coupon_prac.v1.couponevent.entity.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {
    Optional<CouponEvent> findByCampaignIdAndBusinessDate(Long campaignId, LocalDate date);
    List<CouponEvent> findByStatusAndIssueEndAtLessThanEqual(CouponEventStatus status, Instant now);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from CouponEvent e where e.id = :id")
    Optional<CouponEvent> lockById(Long id);
    @Query("select e from CouponEvent e, Campaign c where e.campaignId = c.id and c.notice = :marker order by e.id")
    List<CouponEvent> findFixtures(String marker);
}
