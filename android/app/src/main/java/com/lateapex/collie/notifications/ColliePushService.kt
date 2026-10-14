package com.lateapex.collie.notifications

import com.lateapex.collie.CollieApplication
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Connector verifies the distributor/instance and decrypts before this private service runs. */
class ColliePushService : PushService() {
    private val runtime get() = (application as CollieApplication).container.nativePush
    override fun onMessage(message: PushMessage, instance: String) { runtime.receive(message.content, message.decrypted, instance) }
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) { runtime.enqueueEndpoint(endpoint, instance) }
    override fun onRegistrationFailed(reason: FailedReason, instance: String) = Unit
    override fun onUnregistered(instance: String) { runtime.unregistered(instance) }
}
