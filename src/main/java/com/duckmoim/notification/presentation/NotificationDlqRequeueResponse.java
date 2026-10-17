package com.duckmoim.notification.presentation;

import io.swagger.v3.oas.annotations.media.Schema;

/** DLQ 재처리 결과. 몇 건을 아웃박스로 되돌렸는지다 — 실제 발송은 워커가 다음 주기에 한다. */
public record NotificationDlqRequeueResponse(
    @Schema(description = "아웃박스로 되돌린 건수", example = "430") int requeued) {}
