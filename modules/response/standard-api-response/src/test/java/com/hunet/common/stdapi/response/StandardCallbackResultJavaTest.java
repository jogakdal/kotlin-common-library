package com.hunet.common.stdapi.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class StandardCallbackResultJavaTest {

    @DisplayName("Java 콜백 - 기본값(status=SUCCESS, version=1.0) 적용")
    @Test
    void callback_default_status_version_applied() {
        StandardResponse<StatusPayload> resp = StandardResponse.<StatusPayload>buildWithCallback(
            () -> StandardCallbackResult.of(StatusPayload.Companion.of("OK", "완료", null))
        );
        assertEquals(StandardStatus.SUCCESS, resp.getStatus());
        assertEquals("1.0", resp.getVersion());
        assertEquals("OK", resp.getPayload().getCode());
        assertEquals("", resp.getTraceid());
    }

    @DisplayName("Java 콜백 - status, version 명시 시 오버라이드")
    @Test
    void callback_override_status_version() {
        Supplier<StandardCallbackResult<StatusPayload>> supplier = () -> StandardCallbackResult.of(
            StatusPayload.Companion.of("PING", "OK", null), StandardStatus.FAILURE, "2.0"
        );
        StandardResponse<StatusPayload> resp = StandardResponse.<StatusPayload>buildWithCallback(supplier);
        assertEquals(StandardStatus.FAILURE, resp.getStatus());
        assertEquals("2.0", resp.getVersion());
        assertEquals("PING", resp.getPayload().getCode());
    }

    @DisplayName("Java 빌더 - traceid 전달 및 duration 지정")
    @Test
    void builder_traceid_propagates() {
        String tid = UUID.randomUUID().toString();
        StandardResponse<StatusPayload> resp = StandardResponse.build(
            StatusPayload.Companion.of("OK", "정상", null), StandardStatus.SUCCESS, "1.0", 7L, tid
        );
        assertEquals(tid, resp.getTraceid());
        assertEquals(7L, resp.getDuration());
    }
}
