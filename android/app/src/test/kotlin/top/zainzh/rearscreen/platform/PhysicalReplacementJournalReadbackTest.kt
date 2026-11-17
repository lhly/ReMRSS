package top.zainzh.rearscreen.platform

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.zainzh.rearscreen.ControlledPhysicalCastOperations
import top.zainzh.rearscreen.VirtualCastTerminalStatus

class PhysicalReplacementJournalReadbackTest {
    private val journal = LegacyDisplayOccupancy.PhysicalReplacementJournal(
        "op-9", "ACCEPTED_VIRTUAL", 41, "a.pkg", "a.pkg/.Main", 4, "4:rear",
        7L, true, 42, "b.pkg", "b.pkg/.Main", 8L, "virtual-b-9", true,
        9L, "owner-b", 12L, readerState = "RELEASED", readerId = "reader-b-1", readerGeneration = 3L,
        decision = "ACCEPT")

    private fun status(id: String = "virtual-b-9", op: String = "op-9") = JSONObject().apply {
        put("id", id); put("state", "stopped"); put("coordinatorOperationId", op)
        put("ownerGeneration", 9L); put("ownerIdentity", "owner-b")
        put("generation", 12L); put("task", 42); put("package", "b.pkg"); put("baseComponent", "b.pkg/.Main")
        put("intendedBaseComponent", "b.pkg/.Main")
        put("rearDisplayId", 4); put("rearIdentity", "4:rear")
        put("outputAvailable", false); put("readerLifecycle", "RELEASED")
        put("readerId", "reader-b-1"); put("readerGeneration", 3L)
        put("display", 18); put("input", JSONObject().put("state", "stopped")
            .put("id", "reader-b-1").put("generation", 3L)
            .put("outputAvailable", false).put("task", 42).put("display", 18))
    }

    @Test fun onlyPostInvocationDispatchReceiptMakesAMoveMarkerUsableForRecovery() {
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveMarkerUsable(
            false, "A_RETURNING", "NONE", false, true)) // crash after PENDING
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveMarkerUsable(
            false, "A_RETURNING", "NONE", false, false)) // explicit not-sent cleanup ack lost
        assertTrue(PhysicalReplacementLifecycle.physicalAReturnMoveMarkerUsable(
            false, "A_RETURNING", "NONE", true, false)) // SENT/MAY_HAVE_BEEN_SENT promoted
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveMarkerUsable(
            false, "A_RETURNING", "NONE", true, true))
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveMarkerUsable(
            false, "PREPARING", "NONE", true, false))
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveMarkerUsable(
            true, "A_RETURNING", "NONE", true, false))
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveMarkerUsable(
            false, "A_RETURNING", "ROLLBACK", true, false))
    }

    @Test fun mixedHandoffRequiresAlreadyClaimedOldKeeperUntilTheFirstAMove() {
        for (phase in listOf("PREPARING", "B_READY", "A_RETURNING")) {
            val expected = journal.copy(phase = phase, decision = "NONE")
            fun permits(live: LegacyDisplayOccupancy.PhysicalReplacementJournal? = expected,
                        exactOwner: Boolean = true, pending: Boolean = false, claimed: Boolean = true,
                        closing: Boolean = false) =
                PhysicalReplacementLifecycle.claimedOldOwnerAllowsP2v(expected, live, exactOwner, pending, claimed, closing)
            assertTrue(permits())
            assertFalse(permits(pending = true, claimed = false))
            assertFalse(permits(pending = true))
            assertFalse(permits(claimed = false))
            assertFalse(permits(closing = true))
            assertFalse(permits(exactOwner = false))
            assertFalse(permits(live = null))
            assertFalse(permits(live = expected.copy(oldGeneration = expected.oldGeneration + 1)))
            assertFalse(permits(live = expected.copy(readerGeneration = expected.readerGeneration + 1)))
            assertFalse(permits(live = expected.copy(decision = "ROLLBACK")))
        }
        assertFalse(PhysicalReplacementLifecycle.claimedOldOwnerAllowsP2v(journal, journal, true, false, true, false))
    }

    @Test fun explicitExitOfFailedMixedHandoffRequiresCandidateReallyStopped() {
        val rollback = journal.copy(phase = "PREPARING", decision = "ROLLBACK")
        val idle = JSONObject().put("state", "stopped").put("managerResourcesStopped", true)
            .put("managerInput", JSONObject().put("state", "stopped")
                .put("resourcesStopped", true).put("outputAvailable", false))
        val allocated = status().put("managerResourcesStopped", true).put("managerInput", idle.getJSONObject("managerInput"))
        val unallocated = rollback.copy(newSessionId = "", newBackendGeneration = 0L, readerState = "NOT_REQUESTED",
            readerId = "", readerGeneration = 0L)
        fun allowed(saved: LegacyDisplayOccupancy.PhysicalReplacementJournal, state: JSONObject = idle) =
            ControlledPhysicalCastOperations.hasTerminalCandidateForExplicitRollbackClose(saved, state)

        assertTrue(allowed(unallocated))
        assertTrue(allowed(rollback, allocated))
        assertFalse(allowed(rollback))
        assertFalse(allowed(rollback, JSONObject(allocated.toString()).put("id", "foreign")))
        assertFalse(allowed(rollback, JSONObject(allocated.toString()).put("managerResourcesStopped", false)))
        assertFalse(allowed(unallocated, JSONObject(idle.toString()).put("state", "starting")))
        assertFalse(allowed(unallocated, JSONObject(idle.toString()).put("managerInput", JSONObject().put("state", "running"))))
        assertFalse(allowed(unallocated.copy(readerState = "REQUESTED")))
        assertFalse(allowed(unallocated.copy(newBackendGeneration = 1L)))
        assertFalse(allowed(unallocated.copy(phase = "B_READY")))
        assertFalse(allowed(unallocated.copy(decision = "NONE")))
        assertFalse(allowed(unallocated.copy(newVirtual = false)))
        assertFalse(allowed(journal, allocated))
    }

    @Test fun noReaderTerminalIgnoresHistoricalGlobalReaderOnlyWhenSessionProvesNoReader() {
        val noReaderJournal = journal.copy(readerState = "NO_READER", readerId = "", readerGeneration = 0L)
        val terminal = status().put("readerLifecycle", "NO_READER").put("readerId", "")
            .put("readerGeneration", 0L).put("input", VirtualCastTerminalStatus.physicalReaderStatus(
                "NO_READER", "", 0L, 42, 18,
                JSONObject().put("id", "historical-reader").put("task", 99).put("state", "stopped")))
        assertTrue(ControlledPhysicalCastOperations.matchesVirtualTerminal(terminal, noReaderJournal))
        assertFalse(ControlledPhysicalCastOperations.matchesVirtualTerminal(
            terminal.put("readerLifecycle", "UNKNOWN"), noReaderJournal))
        assertFalse(ControlledPhysicalCastOperations.matchesVirtualTerminal(
            terminal.put("readerId", "old-reader"), noReaderJournal))
    }

    @Test fun exactVirtualTerminalRetainsSameIdAndRequiresAllJournalIdentity() {
        assertTrue(ControlledPhysicalCastOperations.matchesVirtualTerminal(status(), journal))
        assertFalse(ControlledPhysicalCastOperations.matchesVirtualTerminal(status(id = "other"), journal))
        assertFalse(ControlledPhysicalCastOperations.matchesVirtualTerminal(status(op = "other-op"), journal))
        assertFalse(ControlledPhysicalCastOperations.matchesVirtualTerminal(
            status().put("ownerGeneration", 8L), journal))
    }

    @Test fun acceptedReplacementCloseCannotRouteBackToA() {
        assertEquals("CLOSE_NEW_OWNER", LegacyDisplayOccupancy.replacementCloseRoute(
            "ACCEPTED_B", "physical:42:8", "physical:41:7", "physical:42:8"))
        assertEquals("STALE_OWNER", LegacyDisplayOccupancy.replacementCloseRoute(
            "ACCEPTED_B", "physical:41:7", "physical:41:7", "physical:42:8"))
        assertEquals("STALE_OWNER", LegacyDisplayOccupancy.replacementCloseRoute(
            "ACCEPTED_VIRTUAL", "physical:41:7", "physical:41:7", "virtual-b-9"))
        assertEquals("RECOVER_PENDING", LegacyDisplayOccupancy.replacementCloseRoute(
            "B_READY", "physical:41:7", "physical:41:7", ""))
    }

    @Test fun acceptedVirtualCloseRetryIsExactAndSurvivesStoppingOrStoppedRuntime() {
        assertTrue(LegacyDisplayOccupancy.acceptedVirtualCloseRetryMatches(
            journal, "virtual-b-9", 12L, 9L, "owner-b", 42, "b.pkg/.Main", 4, "4:rear"))
        assertFalse(LegacyDisplayOccupancy.acceptedVirtualCloseRetryMatches(
            journal.copy(phase = "B_READY"), "virtual-b-9", 12L, 9L, "owner-b", 42, "b.pkg/.Main", 4, "4:rear"))
        assertFalse(LegacyDisplayOccupancy.acceptedVirtualCloseRetryMatches(
            journal, "virtual-b-9", 13L, 9L, "owner-b", 42, "b.pkg/.Main", 4, "4:rear"))
        assertFalse(LegacyDisplayOccupancy.acceptedVirtualCloseRetryMatches(
            journal, "virtual-b-9", 12L, 9L, "other-owner", 42, "b.pkg/.Main", 4, "4:rear"))
    }

    @Test fun acceptAndRollbackUseOneFirstWriterDecisionAndRollbackRetryIsTicketBound() {
        assertEquals("ACCEPT", PhysicalReplacementLifecycle.nextDecision("NONE", "ACCEPT"))
        assertEquals(null, PhysicalReplacementLifecycle.nextDecision("ACCEPT", "ROLLBACK"))
        assertEquals("ROLLBACK", PhysicalReplacementLifecycle.nextDecision("NONE", "ROLLBACK"))
        assertEquals(null, PhysicalReplacementLifecycle.nextDecision("ROLLBACK", "ACCEPT"))
        assertEquals("ROLLBACK", PhysicalReplacementLifecycle.nextDecision("ROLLBACK", "ROLLBACK"))
        val exact = PhysicalReplacementLifecycle.exactRollbackTicket("op-9", "op-9",
            "virtual-b-9", "virtual-b-9", 12L, 12L, 9L, 9L, "owner-b", "owner-b",
            42, 42, "b.pkg/.Main", "b.pkg/.Main", 4, 4, "4:rear", "4:rear")
        assertTrue(exact)
        assertFalse(PhysicalReplacementLifecycle.exactRollbackTicket("old-token", "op-9",
            "virtual-b-9", "virtual-b-9", 12L, 12L, 9L, 9L, "owner-b", "owner-b",
            42, 42, "b.pkg/.Main", "b.pkg/.Main", 4, 4, "4:rear", "4:rear"))
    }

    @Test fun p2vPhaseCasRequiresTheExactLiveSnapshotAndReadyReaderReceipt() {
        val preparing = journal.copy(phase = "PREPARING", decision = "NONE",
            readerState = "READY", readerId = "reader-b-1", readerGeneration = 3L)
        val bReady = preparing.copy(phase = "B_READY")
        assertTrue(PhysicalReplacementLifecycle.p2vPhaseCasAllowed(
            preparing, preparing, "PREPARING", "B_READY"))
        assertTrue(PhysicalReplacementLifecycle.p2vPhaseCasAllowed(
            bReady, bReady, "B_READY", "A_RETURNING"))
        assertFalse(PhysicalReplacementLifecycle.p2vPhaseCasAllowed(
            preparing, bReady, "PREPARING", "B_READY"))
        assertFalse(PhysicalReplacementLifecycle.p2vPhaseCasAllowed(
            preparing, preparing.copy(decision = "ROLLBACK"), "PREPARING", "B_READY"))
        assertFalse(PhysicalReplacementLifecycle.p2vPhaseCasAllowed(
            preparing, preparing.copy(newBackendGeneration = 13L), "PREPARING", "B_READY"))
        assertFalse(PhysicalReplacementLifecycle.p2vPhaseCasAllowed(
            preparing.copy(readerState = "UNKNOWN", readerId = "", readerGeneration = 0L),
            preparing.copy(readerState = "UNKNOWN", readerId = "", readerGeneration = 0L),
            "PREPARING", "B_READY"))
        assertFalse(PhysicalReplacementLifecycle.p2vPhaseCasAllowed(
            preparing, preparing, "PREPARING", "A_RETURNING"))
    }

    @Test fun readyReaderSessionBindingIsIdempotentButCannotOverwriteAnotherSession() {
        assertTrue(PhysicalReplacementLifecycle.physicalReplacementSessionBindingAllowed("", 0L,
            "virtual-b-9", 12L))
        assertTrue(PhysicalReplacementLifecycle.physicalReplacementSessionBindingAllowed("virtual-b-9", 12L,
            "virtual-b-9", 12L))
        assertFalse(PhysicalReplacementLifecycle.physicalReplacementSessionBindingAllowed("virtual-b-9", 12L,
            "other-session", 13L))
        assertFalse(PhysicalReplacementLifecycle.physicalReplacementSessionBindingAllowed("virtual-b-9", 0L,
            "virtual-b-9", 12L))
        assertFalse(PhysicalReplacementLifecycle.physicalReplacementSessionBindingAllowed("", 12L,
            "virtual-b-9", 12L))
        assertFalse(PhysicalReplacementLifecycle.physicalReplacementSessionBindingAllowed("", 0L,
            "", 12L))
    }

    @Test fun onlyExactPhysicalAReturningJournalCanRecordMoveAuthorization() {
        val returning = journal.copy(phase = "A_RETURNING", newVirtual = false,
            newSessionId = "", newBackendGeneration = 0L, decision = "NONE")
        assertTrue(PhysicalReplacementLifecycle.physicalAReturnMoveAuthorizationAllowed(
            returning, returning, exactOldOwner = true, authorizationAlreadyRecorded = false))
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveAuthorizationAllowed(
            returning, returning.copy(phase = "B_READY"), exactOldOwner = true, authorizationAlreadyRecorded = false))
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveAuthorizationAllowed(
            returning, returning.copy(decision = "ROLLBACK"), exactOldOwner = true, authorizationAlreadyRecorded = false))
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveAuthorizationAllowed(
            returning, returning.copy(newVirtual = true), exactOldOwner = true, authorizationAlreadyRecorded = false))
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveAuthorizationAllowed(
            returning, returning, exactOldOwner = true, authorizationAlreadyRecorded = true))
        assertFalse(PhysicalReplacementLifecycle.physicalAReturnMoveAuthorizationAllowed(
            returning, returning, exactOldOwner = false, authorizationAlreadyRecorded = false))
    }

    @Test fun acceptedCloseUsesOneExactStopReadbackDecisionAndNeverReopensA() {
        fun action(phase: String, decision: String, exact: Boolean, state: String, terminal: Boolean) =
            PhysicalReplacementLifecycle.acceptedCloseAction(
                phase, decision, exact, state == "stopped", terminal)
        assertEquals("STOP_EXACT", action("ACCEPTED_VIRTUAL", "ACCEPT", true, "running", false))
        assertEquals("STOP_EXACT", action("ACCEPTED_VIRTUAL", "ACCEPT", true, "stopping", false))
        assertEquals("STOP_EXACT", action("ACCEPTED_VIRTUAL", "ACCEPT", true, "stopped", false))
        assertEquals("FINALIZE", action("ACCEPTED_VIRTUAL", "ACCEPT", true, "stopped", true))
        assertEquals("REJECT", action("OWNER_VIRTUAL", "ROLLBACK", true, "running", false))
        assertEquals("REJECT", action("ACCEPTED_VIRTUAL", "ACCEPT", false, "stopped", true))
        // A repeated exact stop after terminal or lost acknowledgement re-enters the same action;
        // it cannot authorize moving A because ACCEPTED_VIRTUAL remains terminal ownership.
        assertEquals("STOP_EXACT", action("ACCEPTED_VIRTUAL", "ACCEPT", true, "stopped", false))
    }

    @Test fun processDeathRecoveryNeverResurrectsAcceptedBAndRequiresRecordedVirtualIdentity() {
        assertEquals("KEEP_ACCEPTED_B", LegacyDisplayOccupancy.replacementRecoveryRoute(
            "ACCEPTED_B", false, true))
        assertEquals("CLOSE_ACCEPTED_VIRTUAL", LegacyDisplayOccupancy.replacementRecoveryRoute(
            "ACCEPTED_VIRTUAL", true, true))
        assertEquals("UNKNOWN", LegacyDisplayOccupancy.replacementRecoveryRoute(
            "ACCEPTED_VIRTUAL", true, false))
        assertEquals("ROLLBACK_TO_A", LegacyDisplayOccupancy.replacementRecoveryRoute(
            "PREPARING", true, false))
        assertEquals("ROLLBACK_TO_A", LegacyDisplayOccupancy.replacementRecoveryRoute(
            "B_READY", true, true))
        assertEquals("ROLLBACK_TO_A", LegacyDisplayOccupancy.replacementRecoveryRoute(
            "A_RETURNING", true, true))
        assertEquals("UNKNOWN", LegacyDisplayOccupancy.replacementRecoveryRoute(
            "A_RETURNED", true, false))
    }

}
