package top.zainzh.rearscreen;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class NotificationRearReplacementTest {
    @Test public void missingCandidateComponentDoesNotThrowWhileTrackingAnInFlightNotification() {
        // The system resolves the PendingIntent's target after starting the candidate display.
        assertEquals("", VirtualRearHostActivity.canonicalComponent(null));
    }
}
