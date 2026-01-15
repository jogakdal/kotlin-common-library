package com.hunet.common.stdapi.response;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class JavaPayload implements BasePayload {
    public String message;
    public JavaPayload() { this.message = "ok"; }
    public JavaPayload(String message) { this.message = message; }
}

public class StandardResponseJavaTest {

    @DisplayName("빌더 오버로드로 traceid 및 주요 필드 설정 검증")
    @Test
    void build_overload_sets_traceid_and_fields() {
        JavaPayload p = new JavaPayload("hello");
        String tid = UUID.randomUUID().toString();
        StandardResponse<JavaPayload> resp =
                StandardResponse.Companion.build(p, StandardStatus.SUCCESS, "1.0", 5L, tid);

        assertEquals(StandardStatus.SUCCESS, resp.getStatus());
        assertEquals("1.0", resp.getVersion());
        assertNotNull(resp.getDatetime());
        assertEquals(5L, resp.getDuration());
        assertEquals(tid, resp.getTraceid());
        assertEquals("hello", resp.getPayload().message);
    }

    @DisplayName("Supplier 기반 빌더 동작 및 traceid 포함 검증")
    @Test
    void buildWithCallback_supplier_works() {
        String tid = UUID.randomUUID().toString();
        Supplier<StandardCallbackResult<JavaPayload>> supplier =
                () -> new StandardCallbackResult<>(new JavaPayload("cb"), StandardStatus.SUCCESS, "2.0");
        StandardResponse<JavaPayload> resp =
                StandardResponse.buildWithCallback(supplier, StandardStatus.SUCCESS, "2.0", 10L, tid);

        assertEquals("cb", resp.getPayload().message);
        assertEquals("2.0", resp.getVersion());
        assertEquals(tid, resp.getTraceid());
        assertEquals(StandardStatus.SUCCESS, resp.getStatus());
    }

    @DisplayName("TypeReference 제네릭 역직렬화에서 traceid와 payload 복원")
    @Test
    void deserialize_typeReference_java_generic_payload() {
        String tid = UUID.randomUUID().toString();
        String json = "{" +
                "\"status\":\"SUCCESS\"," +
                "\"version\":\"1.0\"," +
                "\"datetime\":\"2025-11-30T00:00:00Z\"," +
                "\"duration\":1," +
                "\"traceid\":\"" + tid + "\"," +
                "\"payload\": { \"message\": \"hi\" }" +
                "}";
        TypeReference<JavaPayload> typeRef = new TypeReference<>(){};
        StandardResponse<JavaPayload> resp = StandardResponse.Companion.deserialize(json, typeRef);
        assertEquals("hi", resp.getPayload().message);
        assertEquals(tid, resp.getTraceid());
    }

    @DisplayName("PageImpl 기반 fromPageJava 매핑 검증")
    @Test
    void pageableList_fromPageJava_maps_items() {
        List<JavaPayload> list = List.of(new JavaPayload("A"), new JavaPayload("B"));
        Page<JavaPayload> page = new PageImpl<>(list);
        PageableList<BasePayloadImpl> pl = PageableList.Companion.fromPageJava(page, e -> new BasePayloadImpl());
        assertEquals(2, pl.getItems().getList().size());
        assertEquals(2L, pl.getItems().getCurrent());
    }

    @DisplayName("toJson 브릿지로 snake/kebab 케이스 변환 응답 키 확인")
    @Test
    void toJson_case_conversion_snake_and_kebab() {
        JavaPayload p = new JavaPayload("case");
        StandardResponse<JavaPayload> resp =
                StandardResponse.Companion.build(p, StandardStatus.SUCCESS, "1.0", 1L, UUID.randomUUID().toString());
        String snake = StandardResponseJsonBridge.toJson(resp, CaseConvention.SNAKE_CASE, false);
        assertTrue(snake.contains("\"status\""));
        assertTrue(snake.contains("\"duration\""));
        assertTrue(snake.contains("\"payload\""));
        assertTrue(snake.contains("\"trace_id\"") || snake.contains("\"traceid\""));
        String kebab = StandardResponseJsonBridge.toJson(resp, CaseConvention.KEBAB_CASE, false);
        assertTrue(kebab.contains("\"trace-id\"") || kebab.contains("\"traceid\""));
    }

    @DisplayName("Class 기반 역직렬화 동작 검증")
    @Test
    void deserialize_class_based_payload() {
        String json = "{" +
                "\"status\":\"SUCCESS\"," +
                "\"version\":\"1.0\"," +
                "\"datetime\":\"2025-11-30T00:00:00Z\"," +
                "\"duration\":2," +
                "\"payload\": { \"message\": \"class\" }" +
                "}";
        StandardResponse<JavaPayload> resp = StandardResponse.Companion.deserialize(json, JavaPayload.class);
        assertEquals("class", resp.getPayload().message);
    }

    @DisplayName("혼합 대소문자/alias 키 입력의 정상화 및 traceid 복원")
    @Test
    void mixed_case_keys_alias_normalization_in_java_deserialize() {
        String tid = UUID.randomUUID().toString();
        String json = "{" +
                "\"StAtUs\":\"SUCCESS\"," +
                "\"VERsion\":\"1.0\"," +
                "\"DATE_TIME\":\"2025-12-01T00:00:00Z\"," +
                "\"DuRaTiOn\":3," +
                "\"TrAcE_Id\":\"" + tid + "\"," +
                "\"PAY_load\": { \"mEsSaGe\": \"alias\" }" +
                "}";
        StandardResponse<JavaPayload> resp = StandardResponse.Companion.deserialize(json, JavaPayload.class);
        assertEquals("alias", resp.getPayload().message);
        assertEquals(tid, resp.getTraceid());
    }

    @DisplayName("PageListPayload 제네릭 역직렬화 동작 검증")
    @Test
    void pageListPayload_generic_typeReference_deserialize() {
        String json = "{" +
                "\"status\":\"SUCCESS\"," +
                "\"version\":\"1.0\"," +
                "\"datetime\":\"2025-12-02T00:00:00Z\"," +
                "\"duration\":5," +
                "\"payload\": {" +
                "  \"pageable\": {" +
                "    \"page\": { \"size\": 2, \"current\": 1, \"total\": 1 }," +
                "    \"order\": null," +
                "    \"items\": { \"total\": 2, \"current\": 2, \"list\": [" +
                "      { \"message\": \"A\" }, { \"message\": \"B\" }" +
                "    ] }" +
                "  }" +
                " }" +
                "}";
        TypeReference<PageListPayload<JavaPayload>> typeRef = new TypeReference<>(){};
        StandardResponse<PageListPayload<JavaPayload>> resp = StandardResponse.Companion.deserialize(json, typeRef);
        assertEquals(2, resp.getPayload().getPageable().getItems().getList().size());
        assertEquals("A", resp.getPayload().getPageable().getItems().getList().get(0).message);
        assertEquals("B", resp.getPayload().getPageable().getItems().getList().get(1).message);
    }
}
