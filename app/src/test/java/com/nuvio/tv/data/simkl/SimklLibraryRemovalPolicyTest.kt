package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklLibraryRemovalPolicyTest {
    @Test
    fun `plain plan to watch removal is safe`() {
        val entry = SimklLibraryEntry(status = SimklListStatus.PLAN_TO_WATCH)

        assertTrue(entry.destructiveRemovalImpacts().isEmpty())
    }

    @Test
    fun `watched status removal requires history confirmation`() {
        val entry = SimklLibraryEntry(status = SimklListStatus.WATCHING)

        assertEquals(
            setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY),
            entry.destructiveRemovalImpacts()
        )
    }

    @Test
    fun `watch counters protect plan to watch history`() {
        val entry = SimklLibraryEntry(
            status = SimklListStatus.PLAN_TO_WATCH,
            watchedEpisodesCount = 2
        )

        assertEquals(
            setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY),
            entry.destructiveRemovalImpacts()
        )
    }

    @Test
    fun `rating timestamp is protected when numeric rating is absent`() {
        val entry = SimklLibraryEntry(
            status = SimklListStatus.PLAN_TO_WATCH,
            userRatedAt = "2026-07-23T10:00:00Z"
        )

        assertEquals(
            setOf(TrackingMembershipRemovalImpact.RATING),
            entry.destructiveRemovalImpacts()
        )
    }

    @Test
    fun `history and rating impacts are combined`() {
        val entry = SimklLibraryEntry(
            status = SimklListStatus.COMPLETED,
            userRating = 9
        )

        assertEquals(
            setOf(
                TrackingMembershipRemovalImpact.WATCHED_HISTORY,
                TrackingMembershipRemovalImpact.RATING
            ),
            entry.destructiveRemovalImpacts()
        )
    }

    @Test
    fun `safe plan to watch removal is allowed without confirmation`() {
        val current = SimklLibraryEntry(status = SimklListStatus.PLAN_TO_WATCH)

        val result = resolveSimklLibraryMutation(
            currentEntry = current,
            currentDefinition = definition(SimklListStatus.PLAN_TO_WATCH),
            desiredDefinition = null,
            destructiveRemovalConfirmed = false
        )

        assertSame(SimklLibraryMutation.Remove, result)
    }

    @Test
    fun `destructive removal is blocked before confirmation`() {
        val current = SimklLibraryEntry(status = SimklListStatus.COMPLETED)

        assertThrows(SimklDestructiveRemovalRequiredException::class.java) {
            resolveSimklLibraryMutation(
                currentEntry = current,
                currentDefinition = definition(SimklListStatus.COMPLETED),
                desiredDefinition = null,
                destructiveRemovalConfirmed = false
            )
        }
    }

    @Test
    fun `confirmed destructive removal reaches canonical removal path`() {
        val current = SimklLibraryEntry(status = SimklListStatus.COMPLETED)

        val result = resolveSimklLibraryMutation(
            currentEntry = current,
            currentDefinition = definition(SimklListStatus.COMPLETED),
            desiredDefinition = null,
            destructiveRemovalConfirmed = true
        )

        assertSame(SimklLibraryMutation.Remove, result)
    }

    @Test
    fun `status change resolves to destination mutation`() {
        val destination = definition(SimklListStatus.DROPPED)

        val result = resolveSimklLibraryMutation(
            currentEntry = SimklLibraryEntry(status = SimklListStatus.WATCHING),
            currentDefinition = definition(SimklListStatus.WATCHING),
            desiredDefinition = destination,
            destructiveRemovalConfirmed = false
        )

        assertEquals(SimklLibraryMutation.Move(destination.trackingStatus), result)
    }

    private fun definition(status: SimklListStatus): SimklLibraryStatusDefinition =
        simklLibraryStatusDefinitions.single { it.status == status }
}
