package io.github.minhyeok.coupon_prac.v1.coupon.entity;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 발급 전부터 한 행씩 준비하는 쿠폰 재고. 공통 잔여 수량 대신 이 행의 상태를 AVAILABLE에서 ISSUED로 바꾼다. */
@Entity
@Table(name = "coupon_inventory")
@Getter
public class CouponInventory {
    /** JPA가 조회한 행을 엔티티로 복원할 때 사용하는 기본 생성자. 직접 생성할 때는 제공된 생성 메서드를 사용한다. */
    protected CouponInventory() {}

    /** 테이블의 숫자 기본키. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 쿠폰 재고 또는 발급이 속한 이벤트 ID. */
    @Column(nullable = false)
    private Long eventId;

    /** 이벤트 내 적재 순번. 동시 발급 완료 순서를 의미하지 않는다. */
    @Column(nullable = false)
    private int sequenceNo;

    /** 쿠폰의 고정 UUID 식별자. DB에는 BINARY(16)으로 저장한다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID couponCode;

    /** 해당 도메인의 현재 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponInventoryStatus status;

    /** 쿠폰 사용 가능 시작. 캠페인은 일일 시각, 이벤트·쿠폰은 UTC 시각을 저장한다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant usableStartTime;

    /** 쿠폰 사용 가능 종료. 이 시각부터는 사용할 수 없다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant usableEndTime;

    /** 행 생성 UTC 시각. DATETIME 정밀도에 맞춰 초 단위로 저장한다. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant createdAt;

    /** 마지막 변경 UTC 시각. */
    @Column(nullable = false, columnDefinition = "DATETIME")
    private Instant updatedAt;

    /**
     * 이벤트의 사용 기간을 복사하고 난수 UUID를 가진 미발급 재고 한 행을 만든다.
     *
     * @param event ID가 존재하는 저장된 이벤트
     * @param sequence 이벤트 내 1부터 시작하는 적재 순번; 발급 완료 순서는 아니다
     * @param now 재고 생성·수정 UTC 시각
     * @return 아직 저장되지 않은 AVAILABLE 재고
     */
    public static CouponInventory available(CouponEvent event, int sequence, Instant now) {
        CouponInventory inventory = new CouponInventory();
        inventory.eventId = event.getId();
        inventory.sequenceNo = sequence;
        inventory.couponCode = UUID.randomUUID();
        inventory.status = CouponInventoryStatus.AVAILABLE;
        inventory.usableStartTime = event.getUsableStartTime();
        inventory.usableEndTime = event.getUsableEndTime();
        inventory.createdAt = now.truncatedTo(ChronoUnit.SECONDS);
        inventory.updatedAt = now.truncatedTo(ChronoUnit.SECONDS);
        return inventory;
    }

    /**
     * 독점 선점한 재고를 ISSUED로 바꾼다. 사용자 쿠폰 생성과 같은 트랜잭션에서 호출한다.
     *
     * @param now 수정 UTC 시각
     * @throws IllegalStateException AVAILABLE 상태가 아닌 경우
     */
    public void issue(Instant now) {
        if (status != CouponInventoryStatus.AVAILABLE)
            throw new IllegalStateException("Inventory is not available");
        status = CouponInventoryStatus.ISSUED;
        updatedAt = now.truncatedTo(ChronoUnit.SECONDS);
    }
}
