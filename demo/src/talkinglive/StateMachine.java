package talkinglive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * TalkingLive 交互状态机。
 *
 * <p>刻意做成纯逻辑、零 UI 依赖 —— 这是整个项目里少有的
 * 「不需要麦克风、不需要窗口、可以纯单元测试」的部分。
 *
 * <p>状态与事件见 DESIGN.md §2.1。
 */
public class StateMachine {

    public enum State { IDLE, LISTENING, COMMITTING }

    public enum Event {
        /** 听到唤醒词 */
        WAKE,
        /** 听到结束词 */
        END_WORD,
        /** 静音超时 */
        SILENCE,
        /** 前台窗口发生变化 */
        WINDOW_CHANGE,
        /** 用户按 Esc 取消 */
        CANCEL,
        /** 提交完成（注入结束） */
        COMMITTED
    }

    private State state = State.IDLE;
    private final List<BiConsumer<State, State>> listeners = new ArrayList<>();
    private final List<String> ignored = new ArrayList<>();

    public State state() {
        return state;
    }

    /** 被忽略的事件记录，便于排查竞态。 */
    public List<String> ignoredEvents() {
        return ignored;
    }

    public void onTransition(BiConsumer<State, State> listener) {
        listeners.add(listener);
    }

    /**
     * 投递一个事件。
     *
     * <p>非法事件会被<b>静默忽略并记录</b>，不抛异常 —— 因为真实场景里
     * 迟到的事件是常态：引擎在 COMMITTING 阶段还会吐出最后一个 PartialResult、
     * 静音计时可能和结束词同时到达、窗口监听可能重复触发。
     * 状态机的职责是吃掉这些，而不是崩溃。
     *
     * @return 是否发生了状态转移
     */
    public boolean fire(Event event) {
        State from = state;
        State to = next(from, event);
        if (to == null) {
            ignored.add(from + " + " + event);
            return false;
        }
        state = to;
        for (BiConsumer<State, State> l : listeners) {
            l.accept(from, to);
        }
        return true;
    }

    private static State next(State from, Event event) {
        switch (from) {
            case IDLE:
                return event == Event.WAKE ? State.LISTENING : null;

            case LISTENING:
                switch (event) {
                    case END_WORD:
                    case SILENCE:
                    case WINDOW_CHANGE:
                        return State.COMMITTING;
                    case CANCEL:
                        return State.IDLE;
                    default:
                        return null;
                }

            case COMMITTING:
                switch (event) {
                    case COMMITTED:
                    case CANCEL:
                        return State.IDLE;
                    default:
                        return null;
                }
        }
        return null;
    }

    /** 人类可读的状态标签。 */
    public String label() {
        switch (state) {
            case IDLE:       return "IDLE · 待唤醒";
            case LISTENING:  return "LISTENING · 听写中";
            case COMMITTING: return "COMMITTING · 提交中";
        }
        return state.name();
    }
}
