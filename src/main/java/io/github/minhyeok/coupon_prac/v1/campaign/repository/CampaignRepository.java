package io.github.minhyeok.coupon_prac.v1.campaign.repository;
import java.util.*;
import io.github.minhyeok.coupon_prac.v1.campaign.entity.Campaign;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> lockById(Long id);
    List<Campaign> findByNoticeOrderByIdAsc(String notice);
}
