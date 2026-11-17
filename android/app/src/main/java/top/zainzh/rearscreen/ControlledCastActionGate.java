package top.zainzh.rearscreen;

/** Internal host-process handle for the existing display action reservation. */
public final class ControlledCastActionGate {
    public static final class Lease {
        private final DisplayActionGate.Reservation reservation;
        private Lease(DisplayActionGate.Reservation reservation) { this.reservation = reservation; }
        public boolean isCurrent() { return RearAnimationManager.isCurrentDisplayAction(reservation); }
        public void close() { RearAnimationManager.completeDisplayAction(reservation); }
    }

    private ControlledCastActionGate() { }
    public static Lease reserve() {
        DisplayActionGate.Reservation reservation = RearAnimationManager.reserveDisplayAction();
        return reservation == null ? null : new Lease(reservation);
    }
}
