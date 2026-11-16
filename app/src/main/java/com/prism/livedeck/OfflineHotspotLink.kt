package com.prism.livedeck

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OfflineHotspotLink(
    private val context: Context,
    private val onSignalReceived: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    companion object {
        private const val TCP_PORT = 8765
        private const val UDP_PORT = 45678
        private const val MAGIC_BEACON = "PRISM_DIRECTOR_READY"
        private const val MAGIC_PROBE = "PRISM_CAMERA_PROBE"
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeWriter: BufferedWriter? = null
    @Volatile private var udpSocket: DatagramSocket? = null

    fun startDirector() {
        stop()
        running.set(true)
        onStatusChanged("director_listening")

        // 1. TCP Server waiting for Camera 1
        executor.execute {
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(TCP_PORT))
                serverSocket = srv

                while (running.get()) {
                    val client = srv.accept()
                    attachClientSocket(client)
                }
            } catch (e: Exception) {
                if (running.get()) onStatusChanged("error:${e.message}")
            }
        }

        // 2. UDP Beacon & Probe Responder so Camera 1 finds Director automatically on Hotspot
        executor.execute {
            try {
                val sock = DatagramSocket(UDP_PORT)
                sock.broadcast = true
                sock.soTimeout = 1000
                udpSocket = sock

                // Periodic broadcast + probe listener
                executor.execute {
                    val buf = ByteArray(256)
                    while (running.get()) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                            if (msg == MAGIC_PROBE) {
                                val reply = MAGIC_BEACON.toByteArray(Charsets.UTF_8)
                                sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                val beaconBytes = MAGIC_BEACON.toByteArray(Charsets.UTF_8)
                val bcastAddr = InetAddress.getByName("255.255.255.255")
                while (running.get()) {
                    try {
                        sock.send(DatagramPacket(beaconBytes, beaconBytes.size, bcastAddr, UDP_PORT))
                    } catch (_: Exception) {
                    }
                    Thread.sleep(1000)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun startCamera() {
        stop()
        running.set(true)
        onStatusChanged("camera_searching")

        executor.execute {
            var connected = false
            while (running.get() && !connected) {
                // Strategy A: Try Hotspot Gateway IP directly (works even if UDP broadcast is blocked)
                val gatewayIp = getHotspotGatewayIp()
                if (!gatewayIp.isNullOrBlank()) {
                    connected = tryConnectTcp(gatewayIp)
                }
                // Strategy B: Common Android Hotspot IPs fallback
                if (!connected && running.get()) {
                    for (ip in listOf("192.168.43.1", "192.168.49.1", "192.168.137.1")) {
                        if (!running.get()) break
                        if (tryConnectTcp(ip)) {
                            connected = true
                            break
                        }
                    }
                }
                // Strategy C: UDP Broadcast Probe (works when Camera phone is the Hotspot host)
                if (!connected && running.get()) {
                    val discoveredIp = probeDirectorViaUdp()
                    if (!discoveredIp.isNullOrBlank()) {
                        connected = tryConnectTcp(discoveredIp)
                    }
                }

                if (!connected && running.get()) {
                    Thread.sleep(1200)
                }
            }
        }
    }

    private fun probeDirectorViaUdp(): String? {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 1000
            val probe = MAGIC_PROBE.toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), UDP_PORT))

            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            sock.receive(pkt)
            val reply = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
            if (reply == MAGIC_BEACON) pkt.address.hostAddress else null
        } catch (_: Exception) {
            null
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun getHotspotGatewayIp(): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            @Suppress("DEPRECATION")
            val gw = wm.dhcpInfo?.gateway ?: 0
            if (gw == 0) return null
            "${gw and 0xff}.${(gw shr 8) and 0xff}.${(gw shr 16) and 0xff}.${(gw shr 24) and 0xff}"
        } catch (_: Exception) {
            null
        }
    }

    private fun tryConnectTcp(ip: String): Boolean {
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, TCP_PORT), 900)
            attachClientSocket(sock)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun attachClientSocket(sock: Socket) {
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = sock
        activeWriter = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
        onStatusChanged("peer_connected")

        executor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                while (running.get() && !sock.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        onSignalReceived(line)
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (running.get()) {
                    onStatusChanged("peer_disconnected")
                }
            }
        }
    }

    fun sendSignal(jsonLine: String) {
        executor.execute {
            try {
                val w = activeWriter ?: return@execute
                synchronized(w) {
                    w.write(jsonLine.replace("\n", "").replace("\r", ""))
                    w.newLine()
                    w.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running.set(false)
        try { activeWriter?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        activeWriter = null
        activeSocket = null
        serverSocket = null
        udpSocket = null
    }
}
