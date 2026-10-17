package com.duckmoim.common.infra;

import com.duckmoim.common.domain.NotificationOutbox;
import com.duckmoim.common.domain.OutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;

/**
 * 아웃박스의 저장소 (NT-01).
 *
 * <p><b>{@code JpaRepository} 를 상속하지 않는다.</b> {@code AuditLogRepository} 와 같은 판단이다 — 상속하면 이 티켓이 쓰지
 * 않는 문이 전부 열린다. 여기서 여는 것은 {@link #save} 하나다.
 *
 * <p><b>선점은 {@link #findSendableForUpdate} 가 한다</b> (NT-04). 잠그지 않는 {@link #findSendable} 은 남겨 두었다
 * — 「지금 보낼 수 있는 건이 몇 개인가」를 세는 적체 관측(NT-05)이 잠글 이유가 없기 때문이다.
 */
public interface NotificationOutboxRepository extends Repository<NotificationOutbox, Long> {

  /**
   * 아직 못 보낸 건이 몇 개인지 (NT-05).
   *
   * <p><b>잠그지 않는다.</b> 세는 것이 목적이라 값이 한 박자 낡아도 된다 — 적체를 보는 사람이 판단하는 것은 「지금 정확히 몇 개」가 아니라 「늘고 있나」다.
   * 관측이 워커를 기다리게 만들면 관측이 장애 원인이 된다.
   *
   * <p>선점된 건도 {@code PENDING} 이라 함께 센다. 아직 알림함에 들어가지 않았으므로 적체가 맞다.
   */
  long countByStatus(OutboxStatus status);

  /** 보낼 알림 한 건을 덧붙인다. */
  NotificationOutbox save(NotificationOutbox outbox);

  /** 워커가 한 건을 처리하려고 다시 읽는다. 배치가 건네준 번호는 트랜잭션 밖에서 온 값이다. */
  Optional<NotificationOutbox> findById(Long id);

  /**
   * DLQ 로 옮긴 건을 지운다 (NT-03).
   *
   * <p><b>이 표에서 지우는 유일한 문이다.</b> 명세가 「별도 표로 옮기고」라 옮긴 뒤 원본이 남으면 안 된다 — 워커가 10초마다 훑는 표에 죽은 건이 쌓이면 훑는
   * 양이 계속 는다. {@code AuditLogRepository} 가 {@code delete} 를 아예 열지 않은 것과 갈리는 지점이고, 그쪽은 지우지 않는 것이 불변식
   * (I-13)이지만 여기는 옮기는 것이 요구사항이다.
   */
  void delete(NotificationOutbox outbox);

  /**
   * 지금 보낼 수 있는 건을 오래된 순으로 집는다 (NT-02).
   *
   * <p>{@code nextAttemptAt} 이 NULL 인 것은 한 번도 실패하지 않은 건이다 — 발행 직후부터 보낼 수 있어야 하므로 함께 잡는다 (NT-03).
   *
   * <p>정렬이 {@code id} 인 이유는 그것이 곧 발행 순서라서다. 인덱스도 {@code (status, next_attempt_at, id)} 로 그 순서를 따라
   * 두었다 (V802).
   *
   * <p><b>시도를 다 쓴 건을 빼는 조건이 함께 있다.</b> 그 건은 DLQ 로 옮겨지지만 (NT-03) 옮기기 전까지는 이 표에 남아 있고, 조건이 없으면 영원히 다시
   * 집힌다. 인덱스가 이 컬럼까지 덮지는 않는다 — 다 쓴 건은 곧 표에서 빠지므로 걸러낼 양이 적다.
   */
  @Query(
      """
      SELECT o FROM NotificationOutbox o
      WHERE o.status = com.duckmoim.common.domain.OutboxStatus.PENDING
        AND o.attempts < :maxAttempts
        AND (o.nextAttemptAt IS NULL OR o.nextAttemptAt <= :nowInUtc)
      ORDER BY o.id ASC
      """)
  List<NotificationOutbox> findSendable(LocalDateTime nowInUtc, int maxAttempts, Pageable pageable);

  /**
   * 같은 조건을 <b>잠그고</b> 읽는다 (NT-04).
   *
   * <p><b>{@code FOR UPDATE SKIP LOCKED} 다.</b> 잠긴 행을 건너뛰면 두 워커가 같은 건을 집지 않는다. {@code SKIP LOCKED}
   * 가 아니면 뒤에 온 쪽이 잠금을 기다렸다가 「이미 보냈다」로 전부 흘린다 — 결과는 같지만 그 시간이 통째로 낭비다. 마감 배치(PO-14)가 같은 문제를 같은 방식으로
   * 풀었다.
   *
   * <p>힌트 값 {@code -2} 가 Hibernate 의 {@code SKIP_LOCKED} 다. 상수 이름이 아니라 숫자로 넣어야 하는 것은 애노테이션 속성이
   * {@code String} 이기 때문이다.
   *
   * <p><b>이 잠금만으로는 선점이 끝나지 않는다.</b> 잠금은 트랜잭션이 끝나면 풀리는데 발송은 건마다 다른 트랜잭션이라 (실패 기록이 롤백에 함께 지워지면 안 된다)
   * 그 사이에 남이 집을 수 있다. 그래서 이 트랜잭션 안에서 {@code NotificationOutbox#claim} 으로 <b>리스를 적어</b> 잠금이 풀린 뒤에도
   * 표시가 남게 한다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      SELECT o FROM NotificationOutbox o
      WHERE o.status = com.duckmoim.common.domain.OutboxStatus.PENDING
        AND o.attempts < :maxAttempts
        AND (o.nextAttemptAt IS NULL OR o.nextAttemptAt <= :nowInUtc)
      ORDER BY o.id ASC
      """)
  List<NotificationOutbox> findSendableForUpdate(
      LocalDateTime nowInUtc, int maxAttempts, Pageable pageable);

  /**
   * DLQ 에서 되돌린 건을 <b>원래 번호 그대로</b> 다시 넣는다 (DLQ 재처리).
   *
   * <p><b>새 번호로 넣으면 알림함이 두 벌이 된다.</b> 워커의 T1 은 {@code existsByOutboxId} 로 이미 만든 알림을 건너뛰고 유니크 키도
   * {@code notification.outbox_id} 다 (ADR 0010 · V801). 번호가 같아야 알림함이 있는 건은 푸시만 다시 가고, 알림함까지 없는 건은
   * 알림함부터 만든다.
   *
   * <p><b>네이티브 쿼리인 이유</b> — 엔티티가 {@code IDENTITY} 라 JPA 로는 번호를 지정해 저장할 수 없다. 지운 번호라 PK 충돌은 없다.
   *
   * <p>처음 발행된 것처럼 시작한다. {@code attempts} 를 0 으로 두지 않으면 {@code attempts < maxAttempts} 에 걸려 워커가 다시
   * 집지 않는다. 인자를 도메인 객체가 아니라 값으로 받는 것은 {@code common} 이 {@code notification} 을 몰라야 해서다 (도메인-모델링.md
   * 「2. 바운디드 컨텍스트」).
   */
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO notification_outbox
              (id, recipient_id, kind, post_id, comment_id, room_id, message_id,
               status, attempts, next_attempt_at, created_at, updated_at)
          VALUES (:id, :recipientId, :kind, :postId, :commentId, :roomId, :messageId,
                  'PENDING', 0, NULL, :nowInUtc, :nowInUtc)
          """)
  int restore(
      Long id,
      Long recipientId,
      String kind,
      Long postId,
      Long commentId,
      Long roomId,
      Long messageId,
      LocalDateTime nowInUtc);
}
