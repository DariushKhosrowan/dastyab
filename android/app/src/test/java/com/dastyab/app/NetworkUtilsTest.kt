package com.dastyab.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.mockk.mockk
import io.mockk.every
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkUtilsTest {

    @Test
    fun `isOnline returns true when Wi-Fi is available`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true

        // Need to set SDK_INT >= M for the new API path
        // Since Build.VERSION.SDK_INT is final, we test via the code path
        // by setting up mocks that match the M+ branch
        val result = NetworkUtils.isOnline(context)
        assertTrue(result)
    }

    @Test
    fun `isOnline returns true when cellular is available`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true

        val result = NetworkUtils.isOnline(context)
        assertTrue(result)
    }

    @Test
    fun `isOnline returns true when ethernet is available`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns true

        val result = NetworkUtils.isOnline(context)
        assertTrue(result)
    }

    @Test
    fun `isOnline returns false when no network`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns null

        val result = NetworkUtils.isOnline(context)
        assertFalse(result)
    }

    @Test
    fun `isOnline returns false when capabilities are null`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>()
        val network = mockk<Network>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns null

        val result = NetworkUtils.isOnline(context)
        assertFalse(result)
    }

    @Test
    fun `isOnline returns false when no transport matches`() {
        val context = mockk<Context>()
        val cm = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val caps = mockk<NetworkCapabilities>()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false

        val result = NetworkUtils.isOnline(context)
        assertFalse(result)
    }
}