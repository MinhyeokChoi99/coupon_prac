package io.github.minhyeok.coupon_prac.v1.couponevent.service;
import java.time.*;
import io.github.minhyeok.coupon_prac.v1.campaign.repository.CampaignRepository;
import io.github.minhyeok.coupon_prac.v1.couponevent.entity.CouponEvent;
import io.github.minhyeok.coupon_prac.v1.couponevent.repository.CouponEventRepository;
import io.github.minhyeok.coupon_prac.v1.couponinventory.service.CouponInventoryLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.BUSINESS_ZONE;
@Service @RequiredArgsConstructor
public class CouponEventPreparationService {
    private final CampaignRepository campaigns;
    private final CouponEventRepository events;
    private final CouponInventoryLoader loader;
    @Transactional
    public CouponEvent prepare(Long campaignId, LocalDate date, LocalTime issueStart, LocalTime issueEnd, Instant now) {
        var campaign = campaigns.lockById(campaignId).orElseThrow();
        if (!campaign.allowsIssuance() || date.isBefore(campaign.getStartDate()) || date.isAfter(campaign.getEndDate()))
            throw new IllegalArgumentException("Campaign cannot run on this date");
        var existing = events.findByCampaignIdAndBusinessDate(campaignId, date);
        if (existing.isPresent()) { loader.load(existing.get().getId(), now); return existing.get(); }
        var useStart = date.atTime(campaign.getUsableStartTime()).atZone(BUSINESS_ZONE).toInstant();
        var endDate = campaign.getUsableEndTime().isAfter(campaign.getUsableStartTime()) ? date : date.plusDays(1);
        var useEnd = endDate.atTime(campaign.getUsableEndTime()).atZone(BUSINESS_ZONE).toInstant();
        var issueEndDate = issueEnd.isAfter(issueStart) ? date : date.plusDays(1);
        var event = events.saveAndFlush(CouponEvent.scheduled(campaignId, date, campaign.getIssueQuantity(),
                date.atTime(issueStart).atZone(BUSINESS_ZONE).toInstant(),
                issueEndDate.atTime(issueEnd).atZone(BUSINESS_ZONE).toInstant(), useStart, useEnd, now));
        loader.load(event.getId(), now);
        return event;
    }
}
