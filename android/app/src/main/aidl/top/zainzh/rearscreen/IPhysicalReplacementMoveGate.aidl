package top.zainzh.rearscreen;

/** Private callback for the exact physical replacement move's final dispatch boundary. */
interface IPhysicalReplacementMoveGate {
    /** Cheap live journal/owner/approval/reservation check before the service reads fresh snapshots. */
    boolean isCurrent(String operationId, int oldTaskId, String oldPackage, String oldBase,
            long oldGeneration, int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity);
    /** Commit and read back the one-shot A-move marker after final service checks, immediately before invoke. */
    boolean authorizeDispatch(String operationId, int oldTaskId, String oldPackage, String oldBase,
            long oldGeneration, int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity);
    /** Promote pending intent only after the privileged move invocation was SENT or MAY_HAVE_BEEN_SENT. */
    boolean onMoveMayHaveBeenSent(String operationId, int oldTaskId, String oldPackage, String oldBase,
            long oldGeneration, int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity);
    /** The service proved that no move call was sent; revoke only this exact transaction's marker. */
    boolean onRejectedBeforeSend(String operationId, int oldTaskId, String oldPackage, String oldBase,
            long oldGeneration, int newTaskId, String newPackage, String newBase, long newGeneration,
            long newCoordinatorGeneration, String newOwnerIdentity, int rearDisplayId, String rearIdentity);
}
