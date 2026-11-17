package top.zainzh.rearscreen;

/** Fresh service-side rear identity proof for the controlled host path. */
final class ControlledRearIdentityGate {
    interface Service {
        boolean isCurrent();
        Selection readFresh() throws Exception;
    }

    static final class Selection {
        final int displayId;
        final String uniqueId;
        Selection(int displayId, String uniqueId) {
            this.displayId = displayId;
            this.uniqueId = uniqueId == null ? "" : uniqueId;
        }
        String identity() { return displayId + ":" + uniqueId; }
    }

    enum Result { MATCH, SERVICE_STALE, READ_FAILED, EXPECTED_IDENTITY_INVALID,
        SERVICE_IDENTITY_INVALID, DISPLAY_ID_MISMATCH, IDENTITY_MISMATCH }

    private ControlledRearIdentityGate() { }

    static Result verify(Service service, int expectedDisplayId, String expectedIdentity) {
        if (service == null || !service.isCurrent()) return Result.SERVICE_STALE;
        if (expectedDisplayId <= 0 || expectedIdentity == null || expectedIdentity.isBlank())
            return Result.EXPECTED_IDENTITY_INVALID;
        final Selection current;
        try { current = service.readFresh(); }
        catch (Exception unavailable) { return service.isCurrent() ? Result.READ_FAILED : Result.SERVICE_STALE; }
        if (!service.isCurrent()) return Result.SERVICE_STALE;
        if (current == null || current.displayId <= 0 || current.uniqueId.isBlank())
            return Result.SERVICE_IDENTITY_INVALID;
        if (current.displayId != expectedDisplayId) return Result.DISPLAY_ID_MISMATCH;
        return current.identity().equals(expectedIdentity) ? Result.MATCH : Result.IDENTITY_MISMATCH;
    }
}
