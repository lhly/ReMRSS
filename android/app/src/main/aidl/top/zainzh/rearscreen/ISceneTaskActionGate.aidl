package top.zainzh.rearscreen;

/** Synchronous authorization checks for an owned task operation. */
interface ISceneTaskActionGate {
    boolean isCurrent();
    /** Explicit controlled Cast admission immediately before moving its selected task from main. */
    boolean isCurrentBeforeTargetMove();
}
