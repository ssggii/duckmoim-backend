package com.duckmoim.notification.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.notification.service.NotificationDlqRequeueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스가 알림 DLQ 에 하는 일 (DLQ 재처리).
 *
 * <p><b>경로는 admin 이지만 클래스는 notification 에 있다.</b> 다루는 것이 알림 아웃박스와 DLQ 이고, 관리자용 경로는 그 도메인 컨텍스트에 둔다
 * (도메인-모델링.md 「2. 바운디드 컨텍스트」). {@code AdminCommentController} 가 같은 자리다.
 *
 * <p><b>권한 판정이 이 클래스에 없다.</b> {@code /api/v1/admin/**} 전체가 {@code SecurityConfig} 한 줄로 ADMIN 이다.
 */
@Tag(name = "백오피스 알림", description = "못 보낸 알림 재처리")
@RestController
@RequestMapping("/api/v1/admin/notifications/dlq")
@RequiredArgsConstructor
public class AdminNotificationDlqController {

  private final NotificationDlqRequeueService notificationDlqRequeueService;

  /**
   * 실패 시각 구간의 DLQ 건을 아웃박스로 되돌린다.
   *
   * <p><b>명령형 엔드포인트라 {@code POST} 다.</b> 모집글 마감({@code POST .../close})·읽음 처리({@code POST
   * .../read})와 같은 모양이다.
   *
   * <p>응답의 건수는 「되돌렸다」이지 「보냈다」가 아니다. 워커가 다음 주기(최대 10초)에 보내고, 또 실패하면 DLQ 로 돌아온다.
   */
  @Operation(
      summary = "알림 DLQ 재처리",
      description = "실패 시각이 [failedFrom, failedTo) 인 못 보낸 알림을 다시 보낼 대기열로 되돌린다.")
  @PostMapping("/requeue")
  public NotificationDlqRequeueResponse requeue(
      @Valid @RequestBody NotificationDlqRequeueRequest request,
      @AuthenticationPrincipal AuthUser authUser) {

    int requeued =
        notificationDlqRequeueService.requeue(
            request.fromInUtc(), request.toInUtc(), authUser.userId());

    return new NotificationDlqRequeueResponse(requeued);
  }
}
