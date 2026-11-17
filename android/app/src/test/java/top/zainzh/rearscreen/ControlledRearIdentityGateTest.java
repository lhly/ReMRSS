package top.zainzh.rearscreen;

import org.junit.Test;
import static org.junit.Assert.*;

/** The controlled gate trusts a fresh exact service identity, not the app's hidden DisplayInfo read. */
public class ControlledRearIdentityGateTest {
    private static class FakeService implements ControlledRearIdentityGate.Service {
        boolean current = true;
        int currentChecks;
        ControlledRearIdentityGate.Selection selection = new ControlledRearIdentityGate.Selection(1, "rear:exact");
        @Override public boolean isCurrent() { currentChecks++; return current; }
        @Override public ControlledRearIdentityGate.Selection readFresh() { return selection; }
    }

    @Test public void unavailableAppDisplayInfoDoesNotBlockExactCurrentServiceIdentity() {
        boolean appLocalDisplayInfoReadable = false;
        FakeService service = new FakeService();
        assertFalse(appLocalDisplayInfoReadable);
        assertEquals(ControlledRearIdentityGate.Result.MATCH,
                ControlledRearIdentityGate.verify(service, 1, "1:rear:exact"));
        assertTrue("authority is checked before and after the fresh read", service.currentChecks >= 2);
    }

    @Test public void replacedBinderAndWrongRearAreRejected() {
        FakeService stale = new FakeService() {
            @Override public ControlledRearIdentityGate.Selection readFresh() {
                current = false; // Binder was replaced while its read was in flight.
                return new ControlledRearIdentityGate.Selection(1, "rear:exact");
            }
        };
        assertEquals(ControlledRearIdentityGate.Result.SERVICE_STALE,
                ControlledRearIdentityGate.verify(stale, 1, "1:rear:exact"));
        assertEquals(ControlledRearIdentityGate.Result.DISPLAY_ID_MISMATCH,
                ControlledRearIdentityGate.verify(new FakeService(), 2, "2:rear:exact"));
        FakeService wrongUnique = new FakeService();
        wrongUnique.selection = new ControlledRearIdentityGate.Selection(1, "rear:other");
        assertEquals(ControlledRearIdentityGate.Result.IDENTITY_MISMATCH,
                ControlledRearIdentityGate.verify(wrongUnique, 1, "1:rear:exact"));
    }

    @Test public void blankExpectedOrServiceUniqueNeverDegradesToIdOnly() {
        FakeService blankServiceUnique = new FakeService();
        blankServiceUnique.selection = new ControlledRearIdentityGate.Selection(1, " ");
        assertEquals(ControlledRearIdentityGate.Result.SERVICE_IDENTITY_INVALID,
                ControlledRearIdentityGate.verify(blankServiceUnique, 1, "1:"));
        assertEquals(ControlledRearIdentityGate.Result.EXPECTED_IDENTITY_INVALID,
                ControlledRearIdentityGate.verify(new FakeService(), 1, " "));
    }
}
