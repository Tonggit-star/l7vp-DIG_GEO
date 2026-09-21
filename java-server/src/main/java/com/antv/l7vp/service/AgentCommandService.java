package com.antv.l7vp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 「智能体桥」命令队列（控制腿），见 doc/AGENT_MAP_CONTROL_SKILL.md §4 N3/N4/N5。
 *
 * <p>后端只入队，页面长轮询取令后自行执行——HTTP 请求天生由浏览器发起，
 * 要「操控一个正在运行的页面」只能反过来让页面来拉。
 *
 * <p><b>不变式（务必保持）</b>：命令只存在内存里，<b>绝不写达梦、绝不改项目 application</b>。
 * 进程重启即丢，这正是「智能体只控制运行时视图、刷新即恢复」的语义要求。
 * 后人不要"顺手"给这里加持久化。
 *
 * <p>并发模型：每项目一个 {@link ProjectChannel}，其内部状态（队列/序号/回执表）
 * 全部由 {@code synchronized (channel)} 保护；长轮询用 {@code channel.wait(remain)}
 * 挂起，入队与回执时 {@code notifyAll} 唤醒。项目级 Map 用 {@link ConcurrentHashMap}。
 */
@Component
public class AgentCommandService {

    private static final Logger log = LoggerFactory.getLogger(AgentCommandService.class);

    /** 每项目队列上限，超出丢最旧（§4 N3 实现要点） */
    public static final int MAX_QUEUE = 100;
    /** 每项目保留的可查回执数（超过即从 byId 挤出，查不到返回 404） */
    public static final int MAX_TRACKED = 200;
    /** 长轮询挂起上限（秒），§附录 C 决策 13 */
    public static final long MAX_TIMEOUT_MS = 30000L;
    /** 长轮询默认挂起时长（秒） */
    public static final long DEFAULT_TIMEOUT_MS = 25000L;
    /** 每分钟每项目最多下发条数（§9.5 队列限流） */
    public static final int MAX_PER_MINUTE = 120;

    /** 能力分组：运行时视图（地图预览页的「智能体桥」执行，刷新即恢复） */
    public static final String CAPABILITY_RUNTIME = "runtime";
    /** 能力分组：项目配置（Builder 页的「智能体配置桥」执行，写入编辑器状态 → 自动保存落库） */
    public static final String CAPABILITY_CONFIG = "config";

    /**
     * 指令类型 → 能力分组。
     *
     * <p>页面侧有<b>两个</b>执行器，各自只取自己能执行的类型（§4 N4 的 {@code capabilities} 参数）：
     * <ul>
     *   <li>{@code runtime} —— 只改运行时视图（图层显隐/层级、地图视野、目标选中），
     *       组件在 {@code li-analysis-assets} 的「智能体桥」，地图预览页与 Builder 页都有；</li>
     *   <li>{@code config} —— 会写项目配置（建数据集/图层、改图层属性），
     *       组件在 Builder 页的「智能体配置桥」，因为它必须写<b>编辑器状态</b>才可能被自动保存持久化
     *       （li-sdk 的运行时 store 不会被 li-editor 反写，见 CLAUDE.md「智能体地图控制」）。</li>
     * </ul>
     *
     * <p>取令时按此过滤：不在本执行器能力内的指令<b>留在队列里</b>，等另一侧来取——
     * 否则会被取走却执行不了，白白消耗掉一条指令。
     *
     * <p>未登记的类型一律算 runtime（保持既有行为；新增 runtime 类型时忘了登记也不会漏执行）。
     */
    private static final Map<String, String> CAPABILITY_BY_TYPE = new LinkedHashMap<>();

    static {
        CAPABILITY_BY_TYPE.put("layer.visibility", CAPABILITY_RUNTIME);
        CAPABILITY_BY_TYPE.put("layer.isolate", CAPABILITY_RUNTIME);
        CAPABILITY_BY_TYPE.put("layer.bringToFront", CAPABILITY_RUNTIME);
        CAPABILITY_BY_TYPE.put("layer.sendToBack", CAPABILITY_RUNTIME);
        CAPABILITY_BY_TYPE.put("map.focus", CAPABILITY_RUNTIME);
        CAPABILITY_BY_TYPE.put("map.reset", CAPABILITY_RUNTIME);
        CAPABILITY_BY_TYPE.put("target.select", CAPABILITY_RUNTIME);

        CAPABILITY_BY_TYPE.put("dataset.create", CAPABILITY_CONFIG);
        CAPABILITY_BY_TYPE.put("layer.update", CAPABILITY_CONFIG);
    }

    /** 查指令类型属于哪个能力分组 */
    public static String capabilityOf(String type) {
        String capability = CAPABILITY_BY_TYPE.get(type);
        return capability == null ? CAPABILITY_RUNTIME : capability;
    }

    /** 能力分组全集，供参数校验与错误文案 */
    public static List<String> allCapabilities() {
        return new ArrayList<>(new LinkedHashSet<>(CAPABILITY_BY_TYPE.values()));
    }

    private final ConcurrentMap<String, ProjectChannel> channels = new ConcurrentHashMap<>();

    /** 命令被限流拒绝 */
    public static class RateLimited extends RuntimeException {
        public RateLimited(String msg) {
            super(msg);
        }
    }

    /** 一条地图指令（瞬时对象，不入库） */
    public static class Command {
        private String cmdId;
        private long seq;
        private String type;
        private Map<String, Object> payload;
        /** 页面回执，null = 尚未执行 */
        private CommandResult result;

        public String getCmdId() {
            return cmdId;
        }

        public long getSeq() {
            return seq;
        }

        public String getType() {
            return type;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public CommandResult getResult() {
            return result;
        }
    }

    /** 页面执行回执（N5 上报，N6 读取） */
    public static class CommandResult {
        private boolean ok;
        private String error;
        private Map<String, Object> detail;
        private String executedAt;

        public boolean isOk() {
            return ok;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> getDetail() {
            return detail;
        }

        public String getExecutedAt() {
            return executedAt;
        }
    }

    /** 入队结果 */
    public static class EnqueueResult {
        private final Command command;

        EnqueueResult(Command command) {
            this.command = command;
        }

        public Command getCommand() {
            return command;
        }
    }

    /** 取令结果 */
    public static class PollResult {
        private final long seq;
        private final List<Command> commands;

        PollResult(long seq, List<Command> commands) {
            this.seq = seq;
            this.commands = commands;
        }

        public long getSeq() {
            return seq;
        }

        public List<Command> getCommands() {
            return commands;
        }
    }

    /** 每项目一条逻辑通道；所有字段都由 channel 自身的监视器保护 */
    private static class ProjectChannel {
        final Deque<Command> queue = new ArrayDeque<>();
        /** cmdId → Command（含回执），LRU 挤出；插入序无关，按访问序淘汰即可 */
        final Map<String, Command> byId = new LinkedHashMap<String, Command>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Command> eldest) {
                return size() > MAX_TRACKED;
            }
        };
        long seq = 0L;
        long rateWindowStart = 0L;
        int rateCount = 0;
    }

    private ProjectChannel channel(String projectId) {
        return channels.computeIfAbsent(projectId == null ? "" : projectId, k -> new ProjectChannel());
    }

    // ==================== N3：下发（只入队） ====================

    /**
     * 入队一条指令并唤醒正在长轮询的页面。
     *
     * @param type    指令类型（调用方已过白名单）
     * @param payload 已校验并归一化的载荷（region 模式此时已解析成 bounds）
     */
    public EnqueueResult enqueue(String projectId, String type, Map<String, Object> payload) {
        ProjectChannel ch = channel(projectId);
        synchronized (ch) {
            long now = System.currentTimeMillis();
            if (now - ch.rateWindowStart >= 60000L) {
                ch.rateWindowStart = now;
                ch.rateCount = 0;
            }
            if (ch.rateCount >= MAX_PER_MINUTE) {
                throw new RateLimited("下发过于频繁：每项目每分钟最多 " + MAX_PER_MINUTE + " 条指令");
            }
            ch.rateCount++;

            Command cmd = new Command();
            cmd.cmdId = "c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            cmd.seq = ++ch.seq;
            cmd.type = type;
            cmd.payload = payload;

            ch.queue.addLast(cmd);
            while (ch.queue.size() > MAX_QUEUE) {
                Command dropped = ch.queue.pollFirst();
                log.warn("[AGENT] queue overflow projectId={} drop cmdId={}", projectId, dropped.getCmdId());
            }
            ch.byId.put(cmd.cmdId, cmd);
            ch.notifyAll();
            return new EnqueueResult(cmd);
        }
    }

    // ==================== N4：取令（长轮询） ====================

    /**
     * 取严格大于 {@code since} 的指令；没有新指令时挂起至多 {@code timeoutMs} 后返回空列表。
     *
     * <p><b>省略 since（传 null）→ 不回放任何指令，只返回当前最新 seq</b>：
     * 页面首次连接用它对齐游标，避免执行陈旧指令（§附录 C 决策 11）。
     *
     * <p>多开页面 = 广播（同一项目所有页面都拿到同一条指令），不做抢占——指令均幂等。
     *
     * <p>注意：本方法会在 servlet 线程里阻塞至多 timeoutMs。当前规模（单机演示、页面数个）
     * 可接受；并发页面变多时换 WS/SSE（§10 风险 4）。
     */
    public PollResult poll(String projectId, Long since, long timeoutMs) {
        return poll(projectId, since, timeoutMs, null);
    }

    /**
     * 带能力过滤的取令（v1.4 追加）。
     *
     * @param capabilities 只要这些能力分组的指令；null / 空 = 不过滤（返回全部，向后兼容）
     *
     * <p><b>为什么两个执行器共用一个队列还安全</b>：每个调用方有自己的 since 游标，
     * 返回的 seq 是通道最新序号（全量口径）。被过滤掉的指令属于另一侧，
     * 而本侧的游标越过它不影响本侧后续指令（seq 严格递增）。所以两侧互不干扰、也不会重复执行。
     */
    public PollResult poll(String projectId, Long since, long timeoutMs, Set<String> capabilities) {
        ProjectChannel ch = channel(projectId);
        long timeout = Math.max(0L, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        long deadline = System.currentTimeMillis() + timeout;
        synchronized (ch) {
            if (since == null) {
                return new PollResult(ch.seq, new ArrayList<>());
            }
            List<Command> pending = collectAfter(ch, since, capabilities);
            while (pending.isEmpty()) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    break;
                }
                try {
                    ch.wait(remain);
                } catch (InterruptedException e) {
                    // 客户端断开/容器关闭：恢复中断标志后如实返回空结果
                    Thread.currentThread().interrupt();
                    break;
                }
                pending = collectAfter(ch, since, capabilities);
            }
            return new PollResult(ch.seq, pending);
        }
    }

    private List<Command> collectAfter(ProjectChannel ch, long since, Set<String> capabilities) {
        boolean filter = capabilities != null && !capabilities.isEmpty();
        List<Command> out = new ArrayList<>();
        for (Command cmd : ch.queue) {
            if (cmd.seq <= since) {
                continue;
            }
            if (filter && !capabilities.contains(capabilityOf(cmd.type))) {
                continue;
            }
            out.add(cmd);
        }
        return out;
    }

    // ==================== N5 / N6：回执 ====================

    /**
     * 记录页面回执。重复提交幂等覆盖（页面重试不报错）。
     *
     * @return false 表示 cmdId 未知（已被队列/回执表挤出）
     */
    public boolean reportResult(String projectId, String cmdId, boolean ok, String error,
                               Map<String, Object> detail) {
        ProjectChannel ch = channel(projectId);
        synchronized (ch) {
            Command cmd = ch.byId.get(cmdId);
            if (cmd == null) {
                return false;
            }
            CommandResult r = new CommandResult();
            r.ok = ok;
            r.error = error;
            r.detail = detail;
            r.executedAt = Instant.now().toString();
            cmd.result = r;
            // 唤醒可能正在等回执的 N3(wait=true)
            ch.notifyAll();
            return true;
        }
    }

    /** 查一条指令（含回执）；未知返回 null */
    public Command find(String projectId, String cmdId) {
        ProjectChannel ch = channel(projectId);
        synchronized (ch) {
            return ch.byId.get(cmdId);
        }
    }

    /**
     * 等某条指令的回执，至多 timeoutMs。
     *
     * <p>供 N3 的可选参数 {@code wait=true} 使用：让智能体一次调用就能确认「页面真的执行了」，
     * 而不是发完就走、命中「页面根本没开」的假成功。
     *
     * @return 回执；超时或指令未知返回 null
     */
    public CommandResult awaitResult(String projectId, String cmdId, long timeoutMs) {
        ProjectChannel ch = channel(projectId);
        long timeout = Math.max(0L, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        long deadline = System.currentTimeMillis() + timeout;
        synchronized (ch) {
            Command cmd = ch.byId.get(cmdId);
            if (cmd == null) {
                return null;
            }
            while (cmd.result == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    return null;
                }
                try {
                    ch.wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return cmd.result;
                }
            }
            return cmd.result;
        }
    }
}
