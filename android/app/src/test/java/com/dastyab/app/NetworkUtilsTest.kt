package com.dastyab.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NetworkUtilsTest
 *
 * Note: on the plain JVM, Build.VERSION.SDK_INT defaults to 0, so
 * NetworkUtils.isOnline() takes the pre-M (legacy getActiveNetworkInfo)
 * branch. We therefore mock BOTH the legacy API and the modern API on the
 * ConnectivityManager mock, so the test is valid regardless of which branch
 * the runtime selects.
 */
class NetworkUtilsTest {

    private fun mockContext(cm: ConnectivityManager): Context {
        val context = mockk<Context>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        return context
    }

    private fun mockCm(
        activeNetwork: Network? = null,
        caps: NetworkCapabilities? = null,
        legacyInfo: android.net.NetworkInfo? = null
    ): ConnectivityManager {
        val cm = mockk<ConnectivityManager>(relaxed = true)
        every { cm.activeNetwork } returns activeNetwork
        if (activeNetwork != null) {
            every { cm.getNetworkCapabilities(activeNetwork) } returns caps
        }
        every { cm.activeNetworkInfo } returns legacyInfo
        return cm
    }

    private fun connectedInfo(connected: Boolean): android.net.NetworkInfo {
        val info = mockk<android.net.NetworkInfo>()
        every { info.isConnected } returns connected
        return info
    }

    private fun capsWith(vararg transports: Int): NetworkCapabilities {
        val caps = mockk<NetworkCapabilities>()
        every { caps.hasTransport(any()) } answers {
            transports.contains(firstArg<Int>())
        }
        return caps
    }

    @Test
    fun `isOnline returns true when Wi-Fi is available`() {
        val cm = mockCm(mockk<Network>(), capsWith(NetworkCapabilities.TRANSPORT_WIFI), connectedInfo(true))
        assertTrue(NetworkUtils.isOnline(mockContext(cm)))
    }

    @Test
    fun `isOnline returns true when cellular is available`() {
        val cm = mockCm(mockk<Network>(), capsWith(NetworkCapabilities.TRANSPORT_CELLULAR), connectedInfo(true))
        assertTrue(NetworkUtils.isOnline(mockContext(cm)))
    }

    @Test
    fun `isOnline returns true when ethernet is available`() {
        val cm = mockCm(mockk<Network>(), capsWith(NetworkCapabilities.TRANSPORT_ETHERNET), connectedInfo(true))
        assertTrue(NetworkUtils.isOnline(mockContext(cm)))
    }

    @Test
    fun `isOnline returns false when no network`() {
        val cm = mockCm(activeNetwork = null, caps = null, legacyInfo = null)
        assertFalse(NetworkUtils.isOnline(mockContext(cm)))
    }

    @Test
    fun `isOnline returns false when capabilities are null`() {
        val cm = mockCm(mockk<Network>(), caps = null, legacyInfo = connectedInfo(false))
        assertFalse(NetworkUtils.isOnline(mockContext(cm)))
    }

    @Test
    fun `isOnline returns false when no transport matches`() {
        val cm = mockCm(mockk<Network>(), capsWith(), connectedInfo(false))
        assertFalse(NetworkUtils.isOnline(mockContext(cm)))
    }
}
