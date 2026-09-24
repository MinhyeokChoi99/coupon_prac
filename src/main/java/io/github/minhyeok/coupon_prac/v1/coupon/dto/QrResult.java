package io.github.minhyeok.coupon_prac.v1.coupon.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 새로 교체한 QR의 인증 정보. 토큰을 재발급하면 이전 토큰은 무효다.
 *
 * @param qrToken 현재 유효한 난수 UUID
 * @param qrVersion 최초 생성 시 1, 교체마다 증가하는 버전
 * @param expiresAt UTC 만료 시각; 이 시각부터 사용할 수 없다
 */
public record QrResult(UUID qrToken, int qrVersion, Instant expiresAt) {}
