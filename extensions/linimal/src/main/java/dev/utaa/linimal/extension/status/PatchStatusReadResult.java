package dev.utaa.linimal.extension.status;

/** status asset の読み取り結果。異常時にも runtime は fail-open を維持します。 */
public final class PatchStatusReadResult {
    public enum State {
        AVAILABLE,
        UNAVAILABLE,
        ERROR
    }

    private final State state;
    private final PatchStatusReport report;
    private final String reason;

    private PatchStatusReadResult(State state, PatchStatusReport report, String reason) {
        this.state = state;
        this.report = report;
        this.reason = reason;
    }

    static PatchStatusReadResult available(PatchStatusReport report) {
        return new PatchStatusReadResult(State.AVAILABLE, report, null);
    }

    static PatchStatusReadResult unavailable(String reason) {
        return new PatchStatusReadResult(State.UNAVAILABLE, null, reason);
    }

    static PatchStatusReadResult error(String reason) {
        return new PatchStatusReadResult(State.ERROR, null, reason);
    }

    public State getState() {
        return state;
    }

    public boolean isAvailable() {
        return state == State.AVAILABLE;
    }

    /** AVAILABLE でない場合は null を返します。 */
    public PatchStatusReport getReport() {
        return report;
    }

    /** UI に表示してよい短い診断理由。 */
    public String getReason() {
        return reason;
    }
}
