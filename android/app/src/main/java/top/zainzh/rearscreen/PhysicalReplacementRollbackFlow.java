package top.zainzh.rearscreen;

/** One executable ordering seam shared by production P→V rollback and fake lifecycle tests. */
final class PhysicalReplacementRollbackFlow {
    @FunctionalInterface interface Stage { boolean run() throws Exception; }
    private PhysicalReplacementRollbackFlow() { }

    static boolean execute(Stage claim, Stage preStopGate, Stage stopAndVerify,
            Stage restoreAAndKeeper, Stage finish) throws Exception {
        return claim.run() && preStopGate.run() && stopAndVerify.run()
                && restoreAAndKeeper.run() && finish.run();
    }
}
