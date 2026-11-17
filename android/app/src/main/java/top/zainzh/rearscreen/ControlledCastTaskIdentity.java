package top.zainzh.rearscreen;

/** Pure exact-task gate used before controlled virtual-display creation or task movement. */
public final class ControlledCastTaskIdentity {
    private ControlledCastTaskIdentity() { }

    public interface Move { boolean run() throws Exception; }

    public static boolean moveIfExact(int expectedTaskId, String expectedBase, int actualTaskId,
                                      String actualBase, boolean onExpectedDisplay, Move move) throws Exception {
        return matchesExact(expectedTaskId, expectedBase, actualTaskId, actualBase, onExpectedDisplay)
                && move != null && move.run();
    }

    public static boolean matchesExact(int expectedTaskId, String expectedBase, int actualTaskId,
                                   String actualBase, boolean onExpectedDisplay) {
        String expected = RearDisplayCastOccupancyReader.canonicalComponent(expectedBase);
        String actual = RearDisplayCastOccupancyReader.canonicalComponent(actualBase);
        return onExpectedDisplay && expectedTaskId > 0 && actualTaskId == expectedTaskId
                && expected != null && expected.equals(actual);
    }
}
