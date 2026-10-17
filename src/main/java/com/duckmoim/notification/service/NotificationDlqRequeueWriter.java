package com.duckmoim.notification.service;

import com.duckmoim.common.infra.NotificationOutboxRepository;
import com.duckmoim.notification.domain.NotificationOutboxDlq;
import com.duckmoim.notification.infra.NotificationOutboxDlqRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DLQ 한 청크를 아웃박스로 되돌린다. 트랜잭션 하나가 청크 하나다.
 *
 * <p><b>{@link NotificationDlqRequeueService} 와 클래스를 나눈 이유는 프록시다.</b> 청크마다 트랜잭션을 끊으려면 반복문이 이 메서드를
 * 밖에서 불러야 한다. 같은 클래스 안에서 부르면 {@code @Transactional} 이 걸리지 않아 전 구간이 한 트랜잭션이 된다.
 *
 * <p><b>되살리기와 DLQ 삭제가 같은 트랜잭션이다.</b> {@code NotificationDispatchService.moveToDlq} 의 정반대 동작이고, 그래서
 * 한 건은 언제나 아웃박스와 DLQ 중 한 곳에만 있다. 둘 중 하나만 커밋되면 같은 알림이 두 번 되살아나거나 어디에도 없게 된다.
 */
@Service
@RequiredArgsConstructor
public class NotificationDlqRequeueWriter {

  /** 한 트랜잭션이 잠그는 DLQ 행 수. 마감 배치 chunk 와 같은 값이다. */
  static final int CHUNK = 200;

  private final NotificationOutboxDlqRepository dlqRepository;
  private final NotificationOutboxRepository outboxRepository;
  private final Clock clock;

  /**
   * 실패 시각이 {@code [fromInUtc, toInUtc)} 인 DLQ 행을 최대 {@link #CHUNK} 건 되살린다.
   *
   * <p><b>원래 번호 그대로 되살린다.</b> 새 번호면 워커의 T1 이 알림함을 한 벌 더 만든다 ({@code
   * NotificationOutboxRepository#restore} javadoc).
   *
   * @return 되살린 건수. 0 이면 구간이 비었다 — 남이 잠근 행은 건너뛰므로 그쪽이 처리한다
   */
  @Transactional
  public int requeueChunk(LocalDateTime fromInUtc, LocalDateTime toInUtc) {
    List<NotificationOutboxDlq> targets =
        dlqRepository.findRequeueTargetsForUpdate(fromInUtc, toInUtc, PageRequest.ofSize(CHUNK));
    LocalDateTime nowInUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

    for (NotificationOutboxDlq dlq : targets) {
      outboxRepository.restore(
          dlq.getOutboxId(),
          dlq.getRecipientId(),
          dlq.getKind().name(),
          dlq.getPostId(),
          dlq.getCommentId(),
          dlq.getRoomId(),
          dlq.getMessageId(),
          nowInUtc);
      dlqRepository.delete(dlq);
    }

    return targets.size();
  }
}
