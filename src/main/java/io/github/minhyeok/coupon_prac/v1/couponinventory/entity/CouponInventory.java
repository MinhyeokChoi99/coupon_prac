package io.github.minhyeok.coupon_prac.v1.couponinventory.entity;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import io.github.minhyeok.coupon_prac.v1.couponevent.entity.CouponEvent;
import static io.github.minhyeok.coupon_prac.v1.common.time.CouponTime.seconds;
@Entity @Table(name = "coupon_inventory") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponInventory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long eventId;
    @Column(nullable = false) private int sequenceNo;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(nullable = false, columnDefinition = "BINARY(16)") private UUID couponCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private CouponInventoryStatus status;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant usableStartTime;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant usableEndTime;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant createdAt;
    @Column(nullable = false, columnDefinition = "DATETIME") private Instant updatedAt;
    public static CouponInventory available(CouponEvent event, int sequence, Instant now) {
        var inventory = new CouponInventory();
        inventory.eventId = event.getId(); inventory.sequenceNo = sequence; inventory.couponCode = UUID.randomUUID();
        inventory.status = CouponInventoryStatus.AVAILABLE;
        inventory.usableStartTime = event.getUsableStartTime(); inventory.usableEndTime = event.getUsableEndTime();
        inventory.createdAt = seconds(now); inventory.updatedAt = seconds(now);
        return inventory;
    }
    public void issue(Instant now) {
        if (status != CouponInventoryStatus.AVAILABLE) throw new IllegalStateException("Inventory is not available");
        status = CouponInventoryStatus.ISSUED; updatedAt = seconds(now);
    }
}
