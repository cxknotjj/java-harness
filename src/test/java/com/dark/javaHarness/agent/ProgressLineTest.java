package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ProgressLine 线协议编解码单测 */
class ProgressLineTest {

    @Test
    void encode_isProgress_shouldBeTrue() {
        String row = ProgressLine.encode("拆解", "2 个子任务已就绪");
        assertTrue(ProgressLine.isProgress(row));
    }

    @Test
    void encode_decode_shouldRoundTrip() {
        String row = ProgressLine.encode("聚合", "汇总 2 个子任务结果");
        ProgressLine.StageRow p = ProgressLine.decode(row);
        assertEquals("聚合", p.stage());
        assertEquals("汇总 2 个子任务结果", p.detail());
    }

    @Test
    void isProgress_shouldBeFalseForPlainContent() {
        assertFalse(ProgressLine.isProgress("最终回答内容"));
        assertFalse(ProgressLine.isProgress(null));
        assertFalse(ProgressLine.isProgress(""));
    }

    @Test
    void decode_shouldReturnNullForPlainContent() {
        assertNull(ProgressLine.decode("普通 token"));
    }
}