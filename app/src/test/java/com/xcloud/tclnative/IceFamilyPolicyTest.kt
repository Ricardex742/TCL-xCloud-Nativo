package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.IceFamilyPolicy
import com.xcloud.tclnative.settings.IpPreference
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.IceCandidate
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class IceFamilyPolicyTest {
    private val four=IceCandidate("0",0,"candidate:1 1 UDP 100 192.0.2.1 1000 typ host")
    private val six=IceCandidate("0",0,"candidate:2 1 UDP 100 2001:db8::1 1000 typ host")
    @Test fun preferredFailureIsProgressWhileAutomaticFailureStillReachesTheSessionHandler() {
        assertFalse(IceFamilyPolicy.connectionMessage("FAILED",true).contains("FAILED"))
        assertTrue(IceFamilyPolicy.connectionMessage("FAILED",true).contains("IPv4"))
        assertTrue(IceFamilyPolicy.connectionMessage("FAILED",false).contains("FAILED"))
        assertTrue(IceFamilyPolicy.connectionMessage("CLOSED",true).contains("CLOSED"))
    }
    @Test fun anUnreachableIpv6GetsIpv4AfterAtMostTheBoundedHeadStart()=runTest {
        val added=mutableListOf<IceCandidate>()
        val result=IceFamilyPolicy.add(listOf(four,six),true,{ added.add(it);true },{ awaitCancellation() })
        assertTrue(result.ipv6First);assertEquals(listOf(six,four),added)
        assertEquals(750L,currentTime)
    }
    @Test fun rejectedOrFailedIpv6StillGetsIpv4WithoutWaiting()=runTest {
        val added=mutableListOf<IceCandidate>()
        val result=IceFamilyPolicy.add(listOf(four,six),true,{ added.add(it);it===four },{fail("No accepted IPv6")})
        assertEquals(1,result.rejected);assertEquals(listOf(six,four),added);assertEquals(0L,currentTime)
        added.clear()
        IceFamilyPolicy.add(listOf(four,six),true,{ added.add(it);true },{throw IOException("preferred family failed")})
        assertEquals(listOf(six,four),added);assertEquals(0L,currentTime)
    }
    @Test fun automaticOrSingleFamilyAddsImmediatelyAndNeverFiltersValidRoutes()=runTest {
        for((candidates,preference) in listOf(listOf(four,six) to false,listOf(four) to true,listOf(six) to true)) {
            val added=mutableListOf<IceCandidate>()
            val result=IceFamilyPolicy.add(candidates,preference,{ added.add(it);true },{fail("No head start needed")})
            assertEquals(candidates,added);assertFalse(result.ipv6First);assertEquals(0L,currentTime)
        }
    }
    @Test fun sessionCancellationDoesNotAddCandidatesToAClosingPeer()=runTest {
        val added=mutableListOf<IceCandidate>()
        val task=launch { IceFamilyPolicy.add(listOf(four,six),true,{added.add(it);true},{awaitCancellation()}) }
        runCurrent();task.cancelAndJoin();advanceUntilIdle()
        assertEquals(listOf(six),added)
    }
    @Test fun ipv4PreferenceHasTheSameBoundedFallbackAndPreservesIpv6()=runTest {
        val added=mutableListOf<IceCandidate>()
        val result=IceFamilyPolicy.add(listOf(six,four),IpPreference.IPV4,{added.add(it);true},{awaitCancellation()})
        assertEquals(4,result.preferredFamily);assertEquals(listOf(four,six),added);assertEquals(750L,currentTime)
        assertTrue(IceFamilyPolicy.connectionMessage("FAILED",4).contains("tentando IPv6"))
        assertFalse(IceFamilyPolicy.connectionMessage("FAILED",4).contains("FAILED"))
    }
}
