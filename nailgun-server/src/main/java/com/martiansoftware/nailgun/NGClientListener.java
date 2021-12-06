package com.martiansoftware.nailgun;

public interface NGClientListener {

    /**
     * Called by an internal nailgun thread when the server detects that the nailgun client has disconnected.
     * NGClientListener-s can be registered using NGContext#addClientListener.
     */
    void clientDisconnected(NGClientDisconnectReason reason);
}
