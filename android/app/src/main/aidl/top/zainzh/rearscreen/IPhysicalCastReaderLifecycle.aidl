package top.zainzh.rearscreen;

/** Private durable lifecycle receipts for one physical→virtual replacement operation. */
interface IPhysicalCastReaderLifecycle {
    boolean onState(String operationId, String virtualSessionId, long virtualGeneration,
            String state, String readerId, long readerGeneration, int taskId, int displayId,
            String rearIdentity, long ownerGeneration, String ownerIdentity);
}
