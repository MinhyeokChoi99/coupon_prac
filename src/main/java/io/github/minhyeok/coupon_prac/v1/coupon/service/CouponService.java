package io.github.minhyeok.coupon_prac.v1.coupon.service;

import io.github.minhyeok.coupon_prac.v1.coupon.dto.*;
import io.github.minhyeok.coupon_prac.v1.coupon.entity.*;
import io.github.minhyeok.coupon_prac.v1.coupon.exception.*;
import io.github.minhyeok.coupon_prac.v1.coupon.repository.*;

import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * v1 쿠폰의 수동 적재, 발급, QR 교체, 1회 사용, 조회를 담당하는 단일 서비스.
 *
 * <p>재고는 한 행씩 잠그며 공용 잔여 수량 카운터를 갱신하지 않는다. 발급만 명시적 트랜잭션으로 실행하여 실패 시 롤백이 완료된 뒤 실제 품절을 확인한다.
 */
@Service
public class CouponService {
    /** 캠페인 할인 조건 및 적재 잠금 저장소. */
    private final CampaignRepository campaigns;

    /** 일별 이벤트 저장소. */
    private final CouponEventRepository events;

    /** 행 단위 쿠폰 재고 저장소. */
    private final CouponInventoryRepository inventory;

    /** 쿠폰 발급 사용자 자격 저장소. */
    private final CouponUserRepository users;

    /** 한국 날짜별 사용자 한도 저장소. */
    private final UserCouponDailyLimitRepository limits;

    /** 발급된 소유 쿠폰 저장소. */
    private final UserCouponRepository coupons;

    /** 1회 사용 기록 저장소. */
    private final CouponUsageHistoryRepository history;

    /** 독립 커밋·롤백을 보장하는 READ_COMMITTED 발급 트랜잭션. */
    private final TransactionTemplate issuanceTransaction;

    /**
     * 테이블별 저장소와 READ_COMMITTED 발급 트랜잭션을 준비한다.
     *
     * @param campaigns 할인 조건 및 캠페인 잠금 저장소
     * @param events 날짜별 이벤트 조회 및 적재 잠금 저장소
     * @param inventory 쿠폰 재고 조회 및 SKIP LOCKED 선점 저장소
     * @param users 발급 사용자 상태 조회 저장소
     * @param limits 한국 날짜 기준 일일 발급 한도 저장소
     * @param coupons 발급 쿠폰, QR, 소유권 조회 저장소
     * @param history 쿠폰당 최대 한 건의 사용 기록 저장소
     * @param transactionManager 발급 작업의 독립 커밋·롤백을 수행할 JPA 트랜잭션 관리자
     */
    public CouponService(
            CampaignRepository campaigns,
            CouponEventRepository events,
            CouponInventoryRepository inventory,
            CouponUserRepository users,
            UserCouponDailyLimitRepository limits,
            UserCouponRepository coupons,
            CouponUsageHistoryRepository history,
            PlatformTransactionManager transactionManager) {
        this.campaigns = campaigns;
        this.events = events;
        this.inventory = inventory;
        this.users = users;
        this.limits = limits;
        this.coupons = coupons;
        this.history = history;
        this.issuanceTransaction = new TransactionTemplate(transactionManager);
        this.issuanceTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.issuanceTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 캠페인을 잠그고 한국 영업일에 해당하는 ACTIVE 이벤트와 전체 재고를 한 트랜잭션으로 적재한다.
     *
     * <p>이미 준비된 이벤트는 다시 만들지 않는다. 발급/사용 종료 시간이 시작 시간보다 이르거나 같으면 다음 날로 계산한다. 내부 loadInventory 호출은 이
     * 메서드가 연 트랜잭션을 그대로 사용하므로 실패 시 이벤트와 재고가 함께 롤백된다.
     *
     * @param campaignId 사전에 저장한 캠페인 ID; 캠페인 상태는 ACTIVE여야 한다
     * @param date 캠페인 시작일~종료일 안에 있는 한국 영업일
     * @param issueStart 한국 시간 기준 발급 시작 시각(포함)
     * @param issueEnd 한국 시간 기준 발급 종료 시각(미포함)
     * @param now 생성·수정 시각으로 기록할 UTC 시각
     * @return 기존 또는 새로 저장된 이벤트; 재고까지 준비된 상태
     * @throws IllegalArgumentException 캠페인이 비활성이거나 영업일·기간·수량이 유효하지 않은 경우
     * @throws NoSuchElementException 캠페인 ID가 존재하지 않는 경우
     * @throws CouponException 기존 재고가 부분 적재 상태여서 안전하게 준비할 수 없는 경우
     */
    @Transactional
    public CouponEvent prepareEvent(
            Long campaignId,
            LocalDate date,
            LocalTime issueStart,
            LocalTime issueEnd,
            Instant now) {
        Campaign campaign = campaigns.lockById(campaignId).orElseThrow();
        if (!campaign.allowsIssuance()
                || date.isBefore(campaign.getStartDate())
                || date.isAfter(campaign.getEndDate()))
            throw new IllegalArgumentException("Campaign cannot run on this date");
        Optional<CouponEvent> existing = events.findByCampaignIdAndBusinessDate(campaignId, date);
        if (existing.isPresent()) {
            loadInventory(existing.get().getId(), now);
            return existing.get();
        }
        Instant useStart =
                date.atTime(campaign.getUsableStartTime())
                        .atZone(ZoneId.of("Asia/Seoul"))
                        .toInstant();
        LocalDate endDate =
                campaign.getUsableEndTime().isAfter(campaign.getUsableStartTime())
                        ? date
                        : date.plusDays(1);
        Instant useEnd =
                endDate.atTime(campaign.getUsableEndTime())
                        .atZone(ZoneId.of("Asia/Seoul"))
                        .toInstant();
        LocalDate issueEndDate = issueEnd.isAfter(issueStart) ? date : date.plusDays(1);
        CouponEvent event =
                events.saveAndFlush(
                        CouponEvent.active(
                                campaignId,
                                date,
                                campaign.getIssueQuantity(),
                                date.atTime(issueStart).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                                issueEndDate
                                        .atTime(issueEnd)
                                        .atZone(ZoneId.of("Asia/Seoul"))
                                        .toInstant(),
                                useStart,
                                useEnd,
                                now));
        loadInventory(event.getId(), now);
        return event;
    }

    /**
     * 이벤트 행을 잠그고 설정 수량만큼 AVAILABLE 재고를 적재한다.
     *
     * <p>기존 수량이 정확하면 상태와 무관하게 아무것도 변경하지 않는다. 부분 적재는 거절하며, 신규 적재는 ACTIVE 이벤트에만 허용한다. 모든 INSERT는 하나의
     * 트랜잭션에 속하며 중간 flush는 커밋이 아니다. 앱 시작 시 자동 호출하지 않는다.
     *
     * @param eventId 재고를 준비할 이벤트 ID
     * @param now 재고 생성·수정 시각으로 기록할 UTC 시각
     * @throws CouponException 이벤트가 없거나 비활성/부분 적재 상태인 경우
     */
    @Transactional
    public void loadInventory(Long eventId, Instant now) {
        // Only preparation locks the event. Issuance never updates a shared quantity row.
        CouponEvent event =
                events.lockById(eventId)
                        .orElseThrow(() -> new CouponException(CouponErrorCode.EVENT_NOT_FOUND));
        long existing = inventory.countByEventId(eventId);
        if (existing == event.getCouponQuantity()) return;
        if (existing != 0 || event.getStatus() != CouponEventStatus.ACTIVE)
            throw new CouponException(CouponErrorCode.INVENTORY_NOT_AVAILABLE);
        // All rows commit together. A failed load leaves zero rows, so retries do not skip sequence
        // holes.
        for (int sequence = 1; sequence <= event.getCouponQuantity(); sequence++) {
            inventory.save(CouponInventory.available(event, sequence, now));
            if (sequence % 1000 == 0) inventory.flush();
        }
        inventory.flush();
    }

    /**
     * 사용자에게 이벤트의 쿠폰 한 장을 발급하고, 이미 발급했다면 같은 결과를 반환한다.
     *
     * <p>호출자의 트랜잭션을 일시 중단하고 발급 작업을 READ_COMMITTED 독립 트랜잭션으로 실행한다. 재고 선점에 실패하면 롤백 완료 후 새 조회로 실제 품절과
     * 잠금 경합을 구분한다. 이 메서드 전체에 일반 쓰기 트랜잭션을 걸거나 issueInTransaction을 직접 호출하면 이 경계가 깨진다.
     *
     * @param command 발급 대상 이벤트 ID와 사용자 ID; 동일 이벤트·사용자 조합은 멱등 처리
     * @return DB에 커밋된 신규 쿠폰 또는 기존 쿠폰의 ID·UUID 문자열·UTC 발급 시각
     * @throws CouponException 사용자/이벤트가 비활성, 발급 기간 밖, 일일 한도 초과, 실제 품절 또는 재고 잠금 경합인 경우
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CouponIssueResult issue(IssueCouponCommand command) {
        Optional<UserCoupon> existing =
                coupons.findByEventIdAndUserId(command.eventId(), command.userId());
        if (existing.isPresent()) return issueResult(existing.get());
        try {
            return issuanceTransaction.execute(status -> issueInTransaction(command));
        } catch (CouponException exception) {
            if (exception.getErrorCode() != CouponErrorCode.INVENTORY_BUSY) throw exception;
            // Consistent, non-locking read sees a locked-but-uncommitted row as AVAILABLE.
            if (!inventory.existsByEventIdAndStatus(
                    command.eventId(), CouponInventoryStatus.AVAILABLE))
                throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
            throw exception;
        }
    }

    /**
     * 발급 트랜잭션 안에서 사용자·일일 한도·이벤트를 검사하고 재고 하나와 사용자 쿠폰을 함께 변경한다.
     *
     * <p>한도 행 잠금 이후 중복 발급을 다시 조회한다. 한도 증가 뒤 SKIP LOCKED로 AVAILABLE 재고를 선점하고 현재 시각을 다시 검사한다. 어느 단계든
     * 실패하면 한도 증가·재고 상태·쿠폰 생성이 모두 롤백된다.
     *
     * @param command 동일 트랜잭션으로 확정할 이벤트·사용자 ID
     * @return 커밋 직전의 발급 결과; 외부 issue가 트랜잭션 완료 후 반환한다
     * @throws CouponException 자격·기간·날짜·한도·재고 조건을 만족하지 못한 경우
     */
    private CouponIssueResult issueInTransaction(IssueCouponCommand command) {
        Optional<CouponUser> user = users.findById(command.userId());
        if (user.isEmpty() || !user.get().isActive()) {
            throw new CouponException(CouponErrorCode.USER_NOT_ACTIVE);
        }
        Instant now = Instant.now();
        LocalDate date = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        limits.createIfAbsent(command.userId(), now.truncatedTo(ChronoUnit.SECONDS));

        Optional<UserCoupon> existing =
                coupons.findByEventIdAndUserId(command.eventId(), command.userId());
        if (existing.isPresent()) {
            return issueResult(existing.get());
        }
        now = Instant.now();
        if (!now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().equals(date)) {
            throw new CouponException(CouponErrorCode.INVENTORY_BUSY);
        }
        CouponEvent event =
                events.findById(command.eventId())
                        .orElseThrow(() -> new CouponException(CouponErrorCode.EVENT_NOT_FOUND));
        if (!event.isIssuableAt(now)
                || !campaigns.findById(event.getCampaignId()).orElseThrow().allowsIssuance()) {
            throw new CouponException(CouponErrorCode.EVENT_NOT_ISSUABLE);
        }
        if (limits.incrementIfBelowLimit(
                        command.userId(), date, now.truncatedTo(ChronoUnit.SECONDS))
                != 1) {
            throw new CouponException(CouponErrorCode.DAILY_ISSUANCE_LIMIT_EXCEEDED);
        }
        CouponInventory item =
                inventory
                        .lockFirstAvailableByEventId(command.eventId())
                        .orElseThrow(() -> new CouponException(CouponErrorCode.INVENTORY_BUSY));
        now = Instant.now();
        if (!event.isIssuableAt(now)
                || !now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().equals(date)) {
            throw new CouponException(CouponErrorCode.EVENT_NOT_ISSUABLE);
        }
        item.issue(now);
        return issueResult(coupons.saveAndFlush(UserCoupon.issue(item, command.userId(), now)));
    }

    /**
     * 저장된 사용자 쿠폰을 발급 결과 DTO로 변환한다. 기존 발급 재응답에서도 원래 생성 시각을 유지한다.
     *
     * @param coupon 신규 저장되었거나 멱등 조회로 가져온 사용자 쿠폰
     * @return 쿠폰 ID, UUID 문자열, UTC 생성 시각
     */
    private static CouponIssueResult issueResult(UserCoupon coupon) {
        return new CouponIssueResult(
                coupon.getId(), coupon.getCouponCode().toString(), coupon.getCreatedAt());
    }

    /**
     * 소유 쿠폰을 독점 잠그고 현재 QR 토큰을 새 난수 UUID로 교체한다.
     *
     * <p>잠금 획득 후 현재 시각으로 미사용·사용 기간을 검사한다. 버전이 증가하고 이전 토큰은 즉시 무효다. 만료는 초 단위 현재 시각+60초와 쿠폰 사용 종료 중 빠른
     * 시각이다.
     *
     * @param couponId QR을 교체할 사용자 쿠폰 ID
     * @param userId 해당 쿠폰을 소유한 사용자 ID
     * @return 새 QR 토큰·증가한 버전·UTC 만료 시각
     * @throws CouponException 쿠폰이 없거나 다른 사용자 소유이거나 사용 가능한 상태/기간이 아닌 경우
     */
    @Transactional
    public QrResult rotateQr(Long couponId, Long userId) {
        UserCoupon coupon =
                coupons.lockOwned(couponId, userId)
                        .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        coupon.rotateQr(Instant.now());
        return new QrResult(coupon.getQrToken(), coupon.getQrVersion(), coupon.getQrExpiresAt());
    }

    /**
     * 스캔한 QR로 쿠폰을 한 번 사용하고 최종 할인액과 사용 기록을 같은 트랜잭션에 저장한다.
     *
     * <p>토큰 행을 독점 잠근 뒤 가게·점주·주문 조건을 검사한다. 잠금 대기 후 시각으로 만료를 확인하고 조건부 UPDATE로 USED 전환 및 QR 무효화를 수행한다.
     * 사용 기록 INSERT가 실패해도 전환은 롤백된다.
     *
     * @param token 스캔한 현재 QR UUID; 쿠폰 고정 코드와 다르다
     * @param storeId 사용을 요청하는 가게 ID; 캠페인 가게와 일치해야 한다
     * @param ownerId 사용을 요청하는 점주 ID; 캠페인 소유자와 일치해야 한다
     * @param orderAmount 전체 주문 금액(원), 0보다 커야 한다
     * @param menuId 특정 메뉴 할인 시 대상 메뉴 ID; 전체 할인에서는 null 허용
     * @param menuAmount 특정 메뉴 할인 시 해당 메뉴 금액(원); 전체 주문 금액 이하여야 하며 전체 할인에서는 null 허용
     * @return 사용자 쿠폰 ID·사용 기록 ID·최종 할인액·UTC 사용 시각
     * @throws CouponException QR 만료/교체, 사용 불가, 가게·점주 불일치, 부적합한 주문 또는 중복 사용인 경우
     */
    @Transactional
    public UseResult use(
            UUID token,
            Long storeId,
            Long ownerId,
            int orderAmount,
            Long menuId,
            Integer menuAmount) {
        UserCoupon coupon =
                coupons.lockByQrToken(token)
                        .orElseThrow(() -> new CouponException(CouponErrorCode.INVALID_QR));
        CouponEvent event = events.findById(coupon.getEventId()).orElseThrow();
        Campaign campaign = campaigns.findById(event.getCampaignId()).orElseThrow();
        if (!campaign.getStoreId().equals(storeId) || !campaign.getOwnerId().equals(ownerId))
            throw new CouponException(CouponErrorCode.STORE_MISMATCH);
        int amount = discount(campaign, orderAmount, menuId, menuAmount);
        Instant now = Instant.now(); // after lock wait, not request arrival time
        coupon.requireValidQr(token, now);
        byte[] binaryToken =
                ByteBuffer.allocate(16)
                        .putLong(token.getMostSignificantBits())
                        .putLong(token.getLeastSignificantBits())
                        .array();
        if (coupons.consume(coupon.getId(), binaryToken, now.truncatedTo(ChronoUnit.SECONDS)) != 1)
            throw new CouponException(CouponErrorCode.INVALID_QR);
        CouponUsageHistory usage =
                history.saveAndFlush(CouponUsageHistory.used(coupon.getId(), amount, now));
        return new UseResult(coupon.getId(), usage.getId(), amount, usage.getCreatedAt());
    }

    /**
     * 최소 주문금액과 대상 메뉴를 확인한 뒤 실제 할인액을 계산한다.
     *
     * <p>정률 할인은 원 단위로 내림하고, 정액/정률 모두 할인 대상 금액을 넘지 않도록 제한한다.
     *
     * @param campaign 할인 유형·값·대상 메뉴·최소 주문금액의 원본 캠페인
     * @param orderAmount 전체 주문 금액(원)
     * @param menuId 주문한 할인 대상 메뉴 ID 또는 null
     * @param menuAmount 할인 대상 메뉴 금액(원) 또는 null
     * @return 0 이상이며 할인 대상 금액 이하인 실제 할인액(원)
     * @throws CouponException 주문 금액이나 메뉴 조건이 맞지 않은 경우
     */
    private int discount(Campaign campaign, int orderAmount, Long menuId, Integer menuAmount) {
        if (orderAmount <= 0
                || (campaign.getMinOrderAmount() != null
                        && orderAmount < campaign.getMinOrderAmount()))
            throw new CouponException(CouponErrorCode.INVALID_ORDER);
        int base = orderAmount;
        if (campaign.getTargetMenuId() != null) {
            if (!campaign.getTargetMenuId().equals(menuId)
                    || menuAmount == null
                    || menuAmount <= 0
                    || menuAmount > orderAmount)
                throw new CouponException(CouponErrorCode.INVALID_ORDER);
            base = menuAmount;
        }
        long discount =
                "PERCENT".equals(campaign.getDiscountType())
                        ? (long) base * campaign.getDiscountValue() / 100
                        : campaign.getDiscountValue();
        return (int) Math.min(base, discount);
    }

    /**
     * 사용자 소유 쿠폰을 생성 시각·ID 내림차순으로 페이지 조회한다.
     *
     * <p>읽기 전용 트랜잭션이며 만료 상태를 응답에만 반영한다. QR 토큰은 목록에 노출하지 않는다.
     *
     * @param userId 쿠폰 목록을 조회할 사용자 ID
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지당 행 수; HTTP API에서는 1~100으로 제한
     * @return 쿠폰 목록과 전체 개수·페이지 정보; 내역이 없으면 빈 페이지
     */
    @Transactional(readOnly = true)
    public Page<CouponView> list(Long userId, int page, int size) {
        Instant now = Instant.now();
        return coupons.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size))
                .map(c -> view(c, now));
    }

    /**
     * 쿠폰 ID와 소유자를 함께 검사하고 선택적인 사용 기록을 결합한다. 조회만으로 DB 상태를 바꾸지 않는다.
     *
     * @param id 조회할 사용자 쿠폰 ID
     * @param userId 조회 권한을 확인할 소유 사용자 ID
     * @return 쿠폰 정보와 할인액·사용 시각; 사용 기록이 없으면 두 값은 null
     * @throws CouponException 해당 사용자 소유 쿠폰이 없는 경우
     */
    @Transactional(readOnly = true)
    public CouponDetail detail(Long id, Long userId) {
        UserCoupon coupon =
                coupons.findByIdAndUserId(id, userId)
                        .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        Optional<CouponUsageHistory> usage = history.findByUserCouponId(id);
        return new CouponDetail(
                view(coupon, Instant.now()),
                usage.map(CouponUsageHistory::getDiscountAmount).orElse(null),
                usage.map(CouponUsageHistory::getCreatedAt).orElse(null));
    }

    /**
     * 사용자 쿠폰을 외부 조회 DTO로 변환한다. 조회 시각을 기준으로 만료 상태를 계산하며 QR은 제외한다.
     *
     * @param c 변환할 사용자 쿠폰 엔티티
     * @param now 만료 여부를 판정할 UTC 시각
     * @return 고정 쿠폰 코드·상태·사용 기간·발급 시각을 담은 조회 데이터
     */
    private CouponView view(UserCoupon c, Instant now) {
        return new CouponView(
                c.getId(),
                c.getEventId(),
                c.getCouponCode(),
                c.effectiveStatus(now),
                c.getUsableStartTime(),
                c.getUsableEndTime(),
                c.getCreatedAt());
    }
}
