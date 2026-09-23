package io.github.minhyeok.coupon_prac.v1.campaign.entity;
import java.time.*;
import jakarta.persistence.*;
import lombok.*;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.seconds;
@Entity @Table(name = "campaign") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long storeId;
    @Column(nullable = false) private Long ownerId;
    @Column(nullable = false, length = 20) private String status;
    @Column(length = 20) private String pausedReason;
    @Column(nullable = false, length = 20) private String discountTargetType;
    @Column(nullable = false, length = 20) private String discountType;
    @Column(nullable = false) private int discountValue;
    private Long targetMenuId;
    @Column(nullable = false) private int issueQuantity;
    @Column(nullable = false) private LocalTime usableStartTime;
    @Column(nullable = false) private LocalTime usableEndTime;
    private Integer minOrderAmount;
    @Column(length = 500) private String notice;
    @Column(nullable = false, length = 10) private String targetRadius;
    @Column(nullable = false, length = 10) private String targetGender;
    @Column(nullable = false, length = 50) private String targetAgeGroups;
    @Column(nullable = false) private int dailyBudget;
    @Column(nullable = false) private LocalDate startDate;
    @Column(nullable = false) private LocalDate endDate;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant createdAt;

    public static Campaign scheduled(Long storeId, Long ownerId, int quantity,
            LocalTime usableStart, LocalTime usableEnd, LocalDate startDate, LocalDate endDate,
            String discountType, int discountValue, Long targetMenuId, Integer minOrderAmount,
            int dailyBudget, String notice, Instant now) {
        if (quantity <= 0 || startDate.isAfter(endDate) || usableStart.equals(usableEnd)
                || dailyBudget < 0 || (minOrderAmount != null && minOrderAmount < 0)
                || discountValue < 0 || !java.util.Set.of("AMOUNT", "PERCENT").contains(discountType)
                || ("PERCENT".equals(discountType) && discountValue > 100)) {
            throw new IllegalArgumentException("Invalid campaign configuration");
        }
        var campaign = new Campaign();
        campaign.storeId = storeId; campaign.ownerId = ownerId; campaign.status = "SCHEDULED";
        campaign.discountTargetType = targetMenuId == null ? "ALL" : "TARGET_MENU";
        campaign.discountType = discountType; campaign.discountValue = discountValue;
        campaign.targetMenuId = targetMenuId; campaign.issueQuantity = quantity;
        campaign.usableStartTime = usableStart; campaign.usableEndTime = usableEnd;
        campaign.minOrderAmount = minOrderAmount; campaign.dailyBudget = dailyBudget;
        campaign.notice = notice; campaign.targetRadius = "1km"; campaign.targetGender = "전체";
        campaign.targetAgeGroups = "전체"; campaign.startDate = startDate; campaign.endDate = endDate;
        campaign.createdAt = seconds(now);
        return campaign;
    }
    public boolean allowsIssuance() { return status.equals("SCHEDULED") || status.equals("ACTIVE"); }
}
