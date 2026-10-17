package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.NotificationOutboxDlq;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;

/**
 * DLQ 의 저장소 (NT-03).
 *
 * <p>여는 것은 넣기 · 세기 · 되돌리기 셋이다. 백오피스 목록 조회는 응답 필드가 정해지는 티켓이 더한다 — 지금 짐작으로 열면 그 티켓이 고쳐야 한다.
 *
 * <p><b>지우는 문은 되돌리기 하나다.</b> 처음에는 「되돌리는 요구가 없고, 있으면 실수로 증거가 사라진다」로 지우는 문을 두지 않았다. 재처리가 들어오며 열었다 —
 * 되살린 건이 다시 실패하면 {@code uq_notification_outbox_dlq_outbox_id} 에 부딪혀 DLQ 로 못 돌아오기 때문이다. 증거는 사라지지
 * 않는다. 지운 행은 같은 트랜잭션에서 같은 번호의 아웃박스 행이 되고, 다시 실패하면 여기로 돌아온다.
 */
public interface NotificationOutboxDlqRepository extends Repository<NotificationOutboxDlq, Long> {

  /** 못 보낸 건 하나를 옮겨 담는다. */
  NotificationOutboxDlq save(NotificationOutboxDlq dlq);

  /**
   * 포기한 건이 몇 개인지 (NT-05).
   *
   * <p><b>조회는 아직 못 열지만 세는 것은 연다.</b> 지금 DLQ 를 볼 수단이 이관 시점의 ERROR 로그 한 줄뿐이라, 운영 중 쌓여도 로그를 뒤지지 않으면
   * 모른다. 건수는 응답 필드를 정할 필요가 없어 짐작으로 여는 문이 아니다.
   */
  long count();

  /**
   * 실패 시각이 {@code [from, to)} 인 건을 잠그고 읽는다 (DLQ 재처리).
   *
   * <p><b>{@code FOR UPDATE SKIP LOCKED} 다.</b> 관리자 둘이 같은 구간을 동시에 누르면 같은 행을 둘이 되살려 아웃박스 PK 에 부딪힌다.
   * 잠긴 행을 건너뛰면 둘이 행을 나눠 갖는다 — 워커 선점(ADR 0008)과 같은 방식이다.
   *
   * <p>정렬이 인덱스 {@code (failed_at, id)} 와 같다 (V809).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      SELECT d FROM NotificationOutboxDlq d
      WHERE d.failedAt >= :fromInUtc AND d.failedAt < :toInUtc
      ORDER BY d.failedAt ASC, d.id ASC
      """)
  List<NotificationOutboxDlq> findRequeueTargetsForUpdate(
      LocalDateTime fromInUtc, LocalDateTime toInUtc, Pageable pageable);

  /** 되살린 건을 지운다. 같은 트랜잭션에서 같은 번호의 아웃박스 행이 생긴다. */
  void delete(NotificationOutboxDlq dlq);
}
