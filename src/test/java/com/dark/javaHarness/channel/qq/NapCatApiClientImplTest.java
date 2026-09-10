package com.dark.javaHarness.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.dark.javaHarness.channel.qq.dto.ApiResult;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NapCatApiClientImpl 单测（interceptor 桩模拟 NapCat，不依赖网络）：
 * 请求组装（URL/鉴权头/消息段 body）、5xx 重试一次、业务失败不重试、启动自检不外抛。
 */
class NapCatApiClientImplTest {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String OK_BODY = "{\"status\":\"ok\",\"retcode\":0,\"data\":{}}";

    /** 捕获的请求：url / method / authorization / body */
    private final List<String[]> recorded = new java.util.ArrayList<>();

    private NapCatProperties props;
    private NapCatApiClientImpl client;

    @BeforeEach
    void setUp() {
        props = new NapCatProperties();
        props.setEnabled(true);
        props.setApiBaseUrl("http://napcat.local");
        props.setApiToken("napcat123");
        recorded.clear();
    }

    /** interceptor 桩：记录请求并按 codes 顺序返回（末位 code 复用于多余请求） */
    private OkHttpClient fakeHttp(String respBody, int... codes) {
        AtomicInteger idx = new AtomicInteger();
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            Request req = chain.request();
            String body = "";
            if (req.body() != null) {
                Buffer buf = new Buffer();
                req.body().writeTo(buf);
                body = buf.readUtf8();
            }
            recorded.add(new String[]{req.url().toString(), req.method(),
                    req.header("Authorization"), body});
            int code = codes[Math.min(idx.getAndIncrement(), codes.length - 1)];
            return new Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(ResponseBody.create(respBody, JSON))
                    .build();
        }).build();
    }

    private NapCatApiClientImpl newClient(String respBody, int... codes) {
        return new NapCatApiClientImpl(props, new ObjectMapper(), fakeHttp(respBody, codes));
    }

    @Test
    void sendPrivateMsg_buildsAuthorizedPost() {
        client = newClient(OK_BODY, 200);
        client.sendPrivateMsg(10001L, List.of(new MessageSegment("text", java.util.Map.of("text", "hi"))));
        assertEquals(1, recorded.size());
        String[] req = recorded.get(0);
        assertEquals("http://napcat.local/send_private_msg", req[0]);
        assertEquals("POST", req[1]);
        assertEquals("Bearer napcat123", req[2]);
        org.junit.jupiter.api.Assertions.assertTrue(req[3].contains("\"user_id\":10001"), req[3]);
        org.junit.jupiter.api.Assertions.assertTrue(req[3].contains("\"text\":\"hi\""), req[3]);
    }

    @Test
    void sendGroupMsg_buildsGroupIdBody() {
        client = newClient(OK_BODY, 200);
        client.sendGroupMsg(88L, List.of(new MessageSegment("text", java.util.Map.of("text", "群答"))));
        String body = recorded.get(0)[3];
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"group_id\":88"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("user_id"), body);
    }

    @Test
    void getLoginInfo_sendsGetWithAuth() {
        client = newClient(OK_BODY, 200);
        ApiResult result = client.getLoginInfo();
        assertEquals(0, result.retcode());
        String[] req = recorded.get(0);
        assertEquals("http://napcat.local/get_login_info", req[0]);
        assertEquals("GET", req[1]);
        assertEquals("Bearer napcat123", req[2]);
    }

    @Test
    void call_retriesOnceOnHttp500() {
        client = newClient(OK_BODY, 500, 200);
        ApiResult result = client.getLoginInfo();
        assertEquals(0, result.retcode(), "5xx 重试一次后成功");
        assertEquals(2, recorded.size());
    }

    @Test
    void call_businessFailureDoesNotRetry() {
        String fail = "{\"status\":\"failed\",\"retcode\":1,\"data\":null}";
        client = newClient(fail, 200);
        ApiResult result = client.getLoginInfo();
        assertEquals(1, result.retcode(), "业务失败原样返回");
        assertEquals(1, recorded.size(), "retcode != 0 不重试");
    }

    @Test
    void run_selfCheckNeverThrows() {
        client = spy(newClient(OK_BODY, 200));
        assertDoesNotThrow(() -> client.run(null));
        verify(client).getLoginInfo();
    }

    @Test
    void run_disabledSkipsSelfCheck() {
        props.setEnabled(false);
        client = spy(newClient(OK_BODY, 200));
        assertDoesNotThrow(() -> client.run(null));
        verify(client, never()).getLoginInfo();
        verify(client, never()).sendPrivateMsg(org.mockito.ArgumentMatchers.anyLong(), anyList());
    }

    @Test
    void url_baseUrlTrailingSlashIsTrimmed() {
        props.setApiBaseUrl("http://napcat.local/");
        client = newClient(OK_BODY, 200);
        client.getLoginInfo();
        assertEquals("http://napcat.local/get_login_info", recorded.get(0)[0]);
    }
}
