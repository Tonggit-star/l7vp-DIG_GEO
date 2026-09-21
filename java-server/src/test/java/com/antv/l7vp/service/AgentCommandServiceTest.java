package com.antv.l7vp.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令队列语义（N3/N4/N5/N6 的游标、挂起、幂等、限流）纯内存单测。
 */
class AgentCommandServiceTest {

    private final AgentCommandService service = new AgentCommandService();

    private Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("visible", false);
        return payload;
    }

    private AgentCommandService.Command enqueue(String projectId) {
        return service.enqueue(projectId, "layer.visibility", payload()).getCommand();
    }

    @Test
    void enqueue_then_poll_from_zero_returns_command() {
        AgentCommandService.Command cmd = enqueue("p1");

        AgentCommandService.PollResult polled = service.poll("p1", 0L, 100);

        assertEquals(1, polled.getCommands().size());
        assertEquals(cmd.getCmdId(), polled.getCommands().get(0).getCmdId());
        assertEquals("layer.visibility", polled.getCommands().get(0).getType());
        assertEquals(cmd.getSeq(), polled.getSeq());
    }

    @Test
    void poll_without_since_returns_cursor_only() {
        enqueue("p1");

        AgentCommandService.PollResult polled = service.poll("p1", null, 100);

        // 首次连接只对齐游标，不回放陈旧指令（附录 C 决策 11）
        assertTrue(polled.getCommands().isEmpty());
        assertEquals(1L, polled.getSeq());
    }

    @Test
    void poll_returns_only_commands_after_since() {
        enqueue("p1");
        AgentCommandService.Command second = enqueue("p1");

        AgentCommandService.PollResult polled = service.poll("p1", 1L, 100);

        assertEquals(1, polled.getCommands().size());
        assertEquals(second.getCmdId(), polled.getCommands().get(0).getCmdId());
        assertEquals(2L, polled.getSeq());
    }

    @Test
    void poll_is_scoped_per_project() {
        enqueue("p1");

        AgentCommandService.PollResult polled = service.poll("p2", 0L, 50);

        assertTrue(polled.getCommands().isEmpty());
        assertEquals(0L, polled.getSeq());
    }

    @Test
    void poll_hangs_until_timeout_when_idle() {
        long start = System.currentTimeMillis();

        AgentCommandService.PollResult polled = service.poll("p1", 0L, 300);

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(polled.getCommands().isEmpty());
        assertTrue(elapsed >= 250, "空闲时应挂起至超时，实际 " + elapsed + "ms");
        assertTrue(elapsed < 3000, "不应超过 timeout 太多，实际 " + elapsed + "ms");
    }

    @Test
    void poll_wakes_up_on_enqueue() throws Exception {
        Thread producer = new Thread(() -> {
            sleep(200);
            enqueue("p1");
        });
        producer.start();

        long start = System.currentTimeMillis();
        AgentCommandService.PollResult polled = service.poll("p1", 0L, 5000);
        long elapsed = System.currentTimeMillis() - start;
        producer.join();

        assertEquals(1, polled.getCommands().size());
        assertTrue(elapsed < 3000, "入队应立刻唤醒长轮询，实际 " + elapsed + "ms");
    }

    @Test
    void report_result_returns_false_for_unknown_cmd() {
        assertFalse(service.reportResult("p1", "c_nope", true, null, null));
    }

    @Test
    void report_result_is_idempotent_overwrite() {
        AgentCommandService.Command cmd = enqueue("p1");

        assertTrue(service.reportResult("p1", cmd.getCmdId(), true, null, null));
        assertNotNull(service.find("p1", cmd.getCmdId()).getResult());
        assertTrue(service.find("p1", cmd.getCmdId()).getResult().isOk());
        assertNotNull(service.find("p1", cmd.getCmdId()).getResult().getExecutedAt());

        // 页面重试：后一次覆盖前一次，不报错
        assertTrue(service.reportResult("p1", cmd.getCmdId(), false, "图层不存在", null));
        assertFalse(service.find("p1", cmd.getCmdId()).getResult().isOk());
        assertEquals("图层不存在", service.find("p1", cmd.getCmdId()).getResult().getError());
    }

    @Test
    void await_result_returns_after_page_reports() throws Exception {
        AgentCommandService.Command cmd = enqueue("p1");
        Thread page = new Thread(() -> {
            sleep(200);
            service.reportResult("p1", cmd.getCmdId(), true, null, null);
        });
        page.start();

        AgentCommandService.CommandResult result = service.awaitResult("p1", cmd.getCmdId(), 5000);
        page.join();

        assertNotNull(result);
        assertTrue(result.isOk());
    }

    @Test
    void await_result_times_out_when_page_absent() {
        AgentCommandService.Command cmd = enqueue("p1");
        long start = System.currentTimeMillis();

        AgentCommandService.CommandResult result = service.awaitResult("p1", cmd.getCmdId(), 200);

        long elapsed = System.currentTimeMillis() - start;
        assertNull(result);
        assertTrue(elapsed >= 150, "页面不在时应等满超时，实际 " + elapsed + "ms");
    }

    @Test
    void queue_cap_drops_oldest() {
        for (int i = 0; i < AgentCommandService.MAX_QUEUE + 5; i++) {
            enqueue("p1");
        }

        AgentCommandService.PollResult polled = service.poll("p1", 0L, 10);

        assertEquals(AgentCommandService.MAX_QUEUE, polled.getCommands().size());
        // 最旧的 5 条被挤出，第一条可见指令的 seq 是 6
        assertEquals(6L, polled.getCommands().get(0).getSeq());
        assertEquals(AgentCommandService.MAX_QUEUE + 5L, polled.getSeq());
    }

    @Test
    void rate_limited_after_max_per_minute() {
        for (int i = 0; i < AgentCommandService.MAX_PER_MINUTE; i++) {
            enqueue("p1");
        }

        assertThrows(AgentCommandService.RateLimited.class, () -> enqueue("p1"));
        // 限流是按项目计的，别的项目不受影响
        assertNotNull(enqueue("p2"));
    }

    // ==================== v1.4：能力分组与过滤取令 ====================

    private AgentCommandService.Command enqueueTyped(String projectId, String type) {
        return service.enqueue(projectId, type, new LinkedHashMap<>()).getCommand();
    }

    private static java.util.Set<String> only(String capability) {
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(capability));
    }

    @Test
    void capabilityOf_known_types_and_unknown_default() {
        assertEquals(AgentCommandService.CAPABILITY_RUNTIME, AgentCommandService.capabilityOf("layer.visibility"));
        assertEquals(AgentCommandService.CAPABILITY_RUNTIME, AgentCommandService.capabilityOf("map.focus"));
        assertEquals(AgentCommandService.CAPABILITY_CONFIG, AgentCommandService.capabilityOf("dataset.create"));
        assertEquals(AgentCommandService.CAPABILITY_CONFIG, AgentCommandService.capabilityOf("layer.update"));
        // 未登记的类型算 runtime：新增 runtime 类型时忘了登记也不会漏执行
        assertEquals(AgentCommandService.CAPABILITY_RUNTIME, AgentCommandService.capabilityOf("某天新加的指令"));
    }

    @Test
    void allCapabilities_has_no_duplicates() {
        assertEquals(2, AgentCommandService.allCapabilities().size());
        assertTrue(AgentCommandService.allCapabilities().contains(AgentCommandService.CAPABILITY_RUNTIME));
        assertTrue(AgentCommandService.allCapabilities().contains(AgentCommandService.CAPABILITY_CONFIG));
    }

    /**
     * 核心不变式：取令过滤<b>不能把另一侧的指令取走</b>——取走却执行不了 = 白白消耗一条指令。
     */
    @Test
    void filtered_poll_leaves_other_capability_in_queue() {
        enqueueTyped("p1", "dataset.create");
        enqueueTyped("p1", "layer.visibility");

        AgentCommandService.PollResult runtime =
                service.poll("p1", 0L, 10, only(AgentCommandService.CAPABILITY_RUNTIME));
        assertEquals(1, runtime.getCommands().size());
        assertEquals("layer.visibility", runtime.getCommands().get(0).getType());

        // config 侧用自己的游标从 0 起，仍能拿到那条 dataset.create（seq 是全局口径，不受 runtime 侧游标影响）
        AgentCommandService.PollResult config =
                service.poll("p1", 0L, 10, only(AgentCommandService.CAPABILITY_CONFIG));
        assertEquals(1, config.getCommands().size());
        assertEquals("dataset.create", config.getCommands().get(0).getType());

        // 两边都取过之后再取，各自都不该重复拿到指令
        assertTrue(service.poll("p1", runtime.getSeq(), 10,
                only(AgentCommandService.CAPABILITY_RUNTIME)).getCommands().isEmpty());
        assertTrue(service.poll("p1", config.getSeq(), 10,
                only(AgentCommandService.CAPABILITY_CONFIG)).getCommands().isEmpty());
    }

    /** 本侧游标越过另一侧的指令后，本侧<b>后续</b>指令仍能被取到（seq 严格递增，不会误跳） */
    @Test
    void cursor_passing_other_capability_does_not_skip_own_later_commands() {
        enqueueTyped("p1", "dataset.create");
        AgentCommandService.PollResult runtime =
                service.poll("p1", 0L, 10, only(AgentCommandService.CAPABILITY_RUNTIME));
        assertTrue(runtime.getCommands().isEmpty());
        assertEquals(1L, runtime.getSeq());

        enqueueTyped("p1", "map.focus");
        AgentCommandService.PollResult again =
                service.poll("p1", runtime.getSeq(), 10, only(AgentCommandService.CAPABILITY_RUNTIME));
        assertEquals(1, again.getCommands().size());
        assertEquals("map.focus", again.getCommands().get(0).getType());
    }

    /** 省略 capabilities = 不过滤，返回全部（向后兼容既有页面） */
    @Test
    void poll_without_capabilities_returns_all() {
        enqueueTyped("p1", "dataset.create");
        enqueueTyped("p1", "layer.visibility");

        AgentCommandService.PollResult polled = service.poll("p1", 0L, 10, null);
        assertEquals(2, polled.getCommands().size());
        assertEquals(2, service.poll("p1", 0L, 10, new java.util.LinkedHashSet<>()).getCommands().size());
    }

    /** 过滤后的空结果也要如实挂起等待，而不是立刻返回空（否则页面会空转轮询） */
    @Test
    void filtered_poll_waits_for_matching_command() {
        enqueueTyped("p1", "dataset.create");
        long start = System.currentTimeMillis();
        AgentCommandService.PollResult runtime =
                service.poll("p1", 0L, 300, only(AgentCommandService.CAPABILITY_RUNTIME));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(runtime.getCommands().isEmpty());
        assertTrue(elapsed >= 250, "应挂起到超时而非立刻返回，实际 " + elapsed + "ms");
    }

    /** 另一侧在自己挂起期间入队，应被唤醒并拿到本侧指令 */
    @Test
    void filtered_poll_wakes_up_when_own_capability_arrives() {
        Thread enqueuer = new Thread(() -> {
            sleep(80);
            enqueueTyped("p1", "layer.update");
        });
        enqueuer.start();

        AgentCommandService.PollResult polled =
                service.poll("p1", 0L, 5000, only(AgentCommandService.CAPABILITY_CONFIG));

        assertEquals(1, polled.getCommands().size());
        assertEquals("layer.update", polled.getCommands().get(0).getType());
        try {
            enqueuer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
