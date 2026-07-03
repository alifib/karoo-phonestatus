package io.alifib.karoophonestatus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.UUID

/**
 * Reads the Karoo companion-phone connection state from the system
 * `io.hammerhead.phoneservice`.
 *
 * WHY THIS EXISTS
 * ---------------
 * The Karoo phone/companion link does NOT run on the standard Android Bluetooth
 * adapter (that adapter reads OFF while the phone is paired — the link is on the
 * Karoo's nRF radio), and karoo-ext exposes no phone event. The only source of
 * truth is `io.hammerhead.phoneservice`, the same service the Karoo status bar's
 * phone icon uses.
 *
 * That service is `exported=true` with no permission gate, so any app can bind
 * it. Its interface is a private, R8-obfuscated, Rx-over-AIDL protocol
 * (`io.hammerhead.aidlrx.IParcelableListener`). This class reproduces just the
 * one subscription we need — the connection-status stream — reverse-engineered
 * from the phoneservice / systemui binaries on Karoo 3 ROM 1.638.x.
 *
 * ⚠️ STABILITY: the transaction codes below are assigned by the phoneservice
 * build (R8) and are NOT a stable API. They are correct for the ROM this was
 * built against; a major Karoo OS update could change them, in which case the
 * overlay degrades to UNKNOWN (gray) rather than crashing. If that happens,
 * re-derive the codes (see the repo README "Re-deriving the phoneservice
 * protocol").
 */
class PhoneServiceClient(
    private val context: Context,
    private val onStatus: (RadioStatus) -> Unit,
) {
    /** Mirrors io.hammerhead.datamodels.phone.PhoneConnectionStatus.RadioStatus. */
    enum class RadioStatus {
        DISABLED, DISCONNECTED, PAIRING_ERROR, ADVERTISING, CONNECTED, BONDED, UNKNOWN
    }

    companion object {
        private const val TAG = "PhoneStatus"

        private const val PHONE_SERVICE_PKG = "io.hammerhead.phoneservice"
        private const val PHONE_SERVICE_CLS = "io.hammerhead.phoneservice.PhoneService"
        private const val CONTROLLER_DESCRIPTOR = "io.hammerhead.phoneservice.PhoneServiceControllerAIDL"
        private const val LISTENER_DESCRIPTOR = "io.hammerhead.aidlrx.IParcelableListener"

        // PhoneServiceControllerAIDL transaction codes (ROM-specific, R8-assigned).
        // 8 = subscribe to PhoneConnectionStatus, 9 = dispose that subscription.
        private const val TXN_SUBSCRIBE_CONNECTION_STATUS = 8
        private const val TXN_DISPOSE_CONNECTION_STATUS = 9

        // IParcelableListener transaction codes (server -> our callback binder).
        private const val TXN_ON_NEXT = 1      // (String txId, String className, byte[] data, boolean last)
        private const val TXN_ON_ERROR = 2     // (int, String)
        private const val TXN_ON_COMPLETE = 3
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val subscriptionId = UUID.randomUUID().toString()

    private var controllerBinder: IBinder? = null
    private var bound = false

    /**
     * Callback binder the phoneservice streams PhoneConnectionStatus onto.
     * We only read the first field of the marshalled object: radioStatus.name().
     */
    private val listener = object : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in 1..0x00FFFFFF) data.enforceInterface(LISTENER_DESCRIPTOR)
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(LISTENER_DESCRIPTOR)
                    true
                }
                TXN_ON_NEXT -> {
                    /* txId    */ data.readString()
                    val className = data.readString()
                    val bytes = data.createByteArray()
                    /* last    */ data.readInt()
                    reply?.writeNoException()
                    handleParcelable(className, bytes)
                    true
                }
                TXN_ON_ERROR -> {
                    val n = data.readInt()
                    val msg = data.readString()
                    Log.w(TAG, "phoneservice onError($n, $msg)")
                    true
                }
                TXN_ON_COMPLETE -> true
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            controllerBinder = service
            Log.i(TAG, "bound to phoneservice; subscribing to connection status")
            subscribe(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            controllerBinder = null
            emit(RadioStatus.UNKNOWN)
        }
    }

    fun start() {
        val intent = Intent().apply {
            component = ComponentName(PHONE_SERVICE_PKG, PHONE_SERVICE_CLS)
        }
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot bind phoneservice", e)
            false
        }
        if (!bound) {
            Log.w(TAG, "phoneservice bind returned false; is this a Karoo?")
            emit(RadioStatus.UNKNOWN)
        }
    }

    fun stop() {
        controllerBinder?.let { dispose(it) }
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        controllerBinder = null
    }

    private fun subscribe(binder: IBinder) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CONTROLLER_DESCRIPTOR)
            data.writeString(subscriptionId)
            data.writeStrongBinder(listener)
            binder.transact(TXN_SUBSCRIBE_CONNECTION_STATUS, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "subscribe failed", e)
            emit(RadioStatus.UNKNOWN)
        } finally {
            data.recycle()
        }
    }

    private fun dispose(binder: IBinder) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CONTROLLER_DESCRIPTOR)
            data.writeString(subscriptionId)
            binder.transact(TXN_DISPOSE_CONNECTION_STATUS, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.d(TAG, "dispose failed (service likely gone)", e)
        } finally {
            data.recycle()
        }
    }

    private fun handleParcelable(className: String?, bytes: ByteArray?) {
        if (bytes == null) return
        // PhoneConnectionStatus.writeToParcel writes radioStatus.name() first.
        val p = Parcel.obtain()
        val status = try {
            p.unmarshall(bytes, 0, bytes.size)
            p.setDataPosition(0)
            val name = p.readString()
            runCatching { RadioStatus.valueOf(name ?: "") }.getOrDefault(RadioStatus.UNKNOWN)
        } catch (e: Exception) {
            Log.w(TAG, "failed to decode $className", e)
            RadioStatus.UNKNOWN
        } finally {
            p.recycle()
        }
        emit(status)
    }

    private fun emit(status: RadioStatus) {
        mainHandler.post { onStatus(status) }
    }
}
