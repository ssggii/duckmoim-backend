package com.duckmoim.notification.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DLQ 에 간 알림을 장애가 끝난 뒤 다시 보낸다 (DLQ 재처리).
 *
 * <p><b>아웃박스로 되돌리기만 한다.</b> 보내는 것은 평소의 워커다 — 선점(ADR 0008) · 알림함 멱등 검사와 채널 경계(ADR 0010) · 백오프와 DLQ
 * (NT-03)를 그대로 지난다. DLQ 에서 직접 보내면 그 기계를 한 벌 더 만들어야 한다. 되살린 건이 또 실패하면 같은 규칙으로 DLQ 로 돌아온다.
 *
 * <p><b>사람이 부른다.</b> 원인이 고쳐지지 않은 영구 실패(잘못된 VAPID 키)를 자동으로 되돌리면 DLQ 와 아웃박스 사이를 계속 오간다. 장애가 끝났는지, 설정을
 * 고쳤는지 아는 것은 사람이다.
 *
 * <p><b>구간을 실패 시각으로 받는다.</b> 관리자가 아는 것은 「언제 장애가 있었나」다.
 *
 * <p><b>트랜잭션이 없다.</b> 청크마다 {@link NotificationDlqRequeueWriter} 가 끊는다. 1,000건을 한 트랜잭션에 담으면 그동안 DLQ
 * 행 잠금이 길어진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDlqRequeueService {

  private final NotificationDlqRequeueWriter writer;

  /**
   * 실패 시각이 {@code [fromInUtc, toInUtc)} 인 DLQ 건을 전부 아웃박스로 되돌린다.
   *
   * <p>구간이 빌 때까지 청크를 반복한다. 청크마다 되살린 행이 DLQ 에서 지워지므로 반복이 끝나지 않을 수 없다.
   *
   * @param adminId 누가 되돌렸는지. 로그에만 남긴다 — 감사 로그(AD-05)는 개인정보·비공개 내용 접근과 계정 불이익만 남긴다
   * @return 되살린 건수
   */
  public int requeue(LocalDateTime fromInUtc, LocalDateTime toInUtc, Long adminId) {
    int total = 0;
    int requeued;

    do {
      requeued = writer.requeueChunk(fromInUtc, toInUtc);
      total += requeued;
    } while (requeued > 0);

    log.info(
        "[NotificationDlqRequeueService.requeue] DLQ requeued. adminId={}, count={}, from={},"
            + " to={}",
        adminId,
        total,
        fromInUtc,
        toInUtc);

    return total;
  }
}
