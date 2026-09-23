package io.github.minhyeok.coupon_prac.v1.couponevent.entity;
import java.time.*;
import jakarta.persistence.*;
import lombok.*;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.seconds;
@Entity @Table(name = "coupon_event") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long campaignId;
    @Column(nullable = false) private LocalDate businessDate;
    @Column(nullable = false) private int couponQuantity;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant issueStartAt;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant issueEndAt;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant usableStartTime;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant usableEndTime;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private CouponEventStatus status;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant createdAt;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant updatedAt;
    public static CouponEvent scheduled(Long campaignId, LocalDate date, int quantity,
            Instant issueStart, Instant issueEnd, Instant usableStart, Instant usableEnd, Instant now) {
        if (quantity <= 0 || !issueStart.isBefore(issueEnd) || !usableStart.isBefore(usableEnd)
                || issueEnd.isAfter(usableEnd)) throw new IllegalArgumentException("Invalid event period or quantity");
        var event = new CouponEvent();
        event.campaignId = campaignId; event.businessDate = date; event.couponQuantity = quantity;
        event.issueStartAt = seconds(issueStart); event.issueEndAt = seconds(issueEnd);
        event.usableStartTime = seconds(usableStart); event.usableEndTime = seconds(usableEnd);
        event.status = CouponEventStatus.SCHEDULED; event.createdAt = seconds(now); event.updatedAt = seconds(now);
        return event;
    }
    // ACTIVE means prepared/open; the time predicate still prevents issuing before 11:00.
    public void activate(Instant now) {
        if (status != CouponEventStatus.SCHEDULED) throw new IllegalStateException("Event must be scheduled");
        if (!now.isBefore(issueEndAt)) throw new IllegalStateException("Issue period has ended");
        status = CouponEventStatus.ACTIVE; updatedAt = seconds(now);
    }
    public void end(Instant now) { status = CouponEventStatus.ENDED; updatedAt = seconds(now); }
    public boolean isIssuableAt(Instant now) {
        return status == CouponEventStatus.ACTIVE && !now.isBefore(issueStartAt) && now.isBefore(issueEndAt);
    }
}
