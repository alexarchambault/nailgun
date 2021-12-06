package com.martiansoftware.nailgun;

public interface NGHeartbeatListener {

    /**
     * Called by an internal nailgun thread when the server receives a heartbeat from the client.
     * This can normally be implemented as a no-op handler and is primarily useful for debugging.
     * NGClientListener-s can be registered using NGContext.registerHeartbeatListener.
     */
    void heartbeatReceived();
}
