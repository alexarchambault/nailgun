/*

 Copyright 2004-2012, Martian Software, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 */
package com.martiansoftware.nailgun;

import com.martiansoftware.nailgun.builtins.DefaultNail;
import com.sun.jna.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for new connections from NailGun clients and launches NGSession
 * threads to process them.
 *
 * This class can be run as a standalone server or can be embedded within
 * larger applications as a means of providing command-line interaction with the
 * application.
 *
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
public class NGServer implements Runnable {

    private Logger LOG;

    /**
     * Default size for thread pool
     */
    public static final int DEFAULT_SESSIONPOOLSIZE = 2;

    /**
     * The address on which to listen
     */
    private final NGListeningAddress listeningAddress;

    /**
     * The socket doing the listening
     */
    private ServerSocket serversocket;

    /**
     * True if this NGServer has received instructions to shut down
     */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * True if this NGServer has been started and is accepting connections
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * This NGServer's AliasManager, which maps aliases to classes
     */
    private final AliasManager aliasManager;

    /**
     * If true, fully-qualified classnames are valid commands
     */
    private boolean allowNailsByClassName = true;

    /**
     * The default class to use if an invalid alias or classname is specified by
     * the client.
     */
    private Class defaultNailClass = null;

    /**
     * A pool of NGSessions ready to handle client connections
     */
    private final NGSessionPool sessionPool;

    private final boolean redirectStreams;

    /**
     * <code>System.out</code> at the time of the NGServer's creation
     */
    public PrintStream out;

    /**
     * <code>System.err</code> at the time of the NGServer's creation
     */
    public PrintStream err;

    /**
     * <code>System.in</code> at the time of the NGServer's creation
     */
    public InputStream in;

    /**
     * a collection of all classes executed by this server so far
     */
    private final Map<String, NailStats> allNailStats;

    /**
     * Remember the security manager we start with so we can restore it later
     */
    private SecurityManager originalSecurityManager = null;
    private final int heartbeatTimeoutMillis;

    @FunctionalInterface
    public interface DomainSocketProvider {
        ServerSocket apply() throws IOException;
    }

    private final DomainSocketProvider domainSocketProvider;

    public static DomainSocketProvider defaultDomainSocketProvider(java.util.function.Supplier<String> addr) {
        return () -> {
            if (Platform.isWindows()) {
                boolean requireStrictLength = true;
                return new NGWin32NamedPipeServerSocket(addr.get(), requireStrictLength);
            } else {
                return new NGUnixDomainServerSocket(addr.get());
            }
        };
    }

    /**
     * Creates a new NGServer that will listen at the specified address and on
     * the specified port with the specified session pool size. This does
     * <b>not</b> cause the server to start listening. To do so, create a new
     * <code>Thread</code> wrapping this
     * <code>NGServer</code> and start it.
     *
     * @param addr the address at which to listen, or
     * <code>null</code> to bind to all local addresses
     * @param port the port on which to listen.
     * @param sessionPoolSize the max number of idle sessions allowed by the
     * pool
     */
    public NGServer(InetAddress addr, int port, int sessionPoolSize, int timeoutMillis) {
        this(new NGListeningAddress(addr, port), sessionPoolSize, timeoutMillis, System.in, System.out, System.err);
    }

    /**
     * Creates a new NGServer that will listen at the specified address and on
     * the specified port with the default session pool size. This does
     * <b>not</b> cause the server to start listening. To do so, create a new
     * <code>Thread</code> wrapping this
     * <code>NGServer</code> and start it.
     *
     * @param addr the address at which to listen, or
     * <code>null</code> to bind to all local addresses
     * @param port the port on which to listen.
     */
    public NGServer(InetAddress addr, int port) {
        this(new NGListeningAddress(addr, port), DEFAULT_SESSIONPOOLSIZE, NGConstants.HEARTBEAT_TIMEOUT_MILLIS, System.in, System.out, System.err);
    }

    /**
     * Creates a new NGServer that will listen on the default port (defined in
     * <code>NGConstants.DEFAULT_PORT</code>). This does <b>not</b> cause the
     * server to start listening. To do so, create a new
     * <code>Thread</code> wrapping this
     * <code>NGServer</code> and start it.
     */
    public NGServer() {
        this(new NGListeningAddress(null, NGConstants.DEFAULT_PORT), DEFAULT_SESSIONPOOLSIZE, NGConstants.HEARTBEAT_TIMEOUT_MILLIS, System.in, System.out, System.err);
    }

    public NGServer(NGListeningAddress listeningAddress, int sessionPoolSize, int timeoutMillis, InputStream in, PrintStream out, PrintStream err) {
        this(listeningAddress, sessionPoolSize, timeoutMillis, in, out, err, LoggerFactory.getLogger(NGServer.class.getName()));
    }

    /**
     * Creates a new NGServer that will listen at the specified address and on
     * the specified port with the specified session pool size. This does
     * <b>not</b> cause the server to start listening. To do so, create a new
     * <code>Thread</code> wrapping this
     * <code>NGServer</code> and start it.
     *
     * @param listeningAddress the address at which to listen
     * @param sessionPoolSize the max number of idle sessions allowed by the
     * pool
     * @param timeoutMillis timeout in millis to wait for a heartbeat from the client
     * before disconnecting them
     */
    public NGServer(NGListeningAddress listeningAddress, int sessionPoolSize, int timeoutMillis, InputStream in, PrintStream out, PrintStream err, Logger logger) {
        this(listeningAddress, sessionPoolSize, timeoutMillis, in, out, err, logger, defaultDomainSocketProvider(listeningAddress::getLocalAddress), false);
    }

    /**
     * Creates a new NGServer that will listen at the specified address and on
     * the specified port with the specified session pool size. This does
     * <b>not</b> cause the server to start listening. To do so, create a new
     * <code>Thread</code> wrapping this
     * <code>NGServer</code> and start it.
     *
     * @param listeningAddress the address at which to listen
     * @param sessionPoolSize the max number of idle sessions allowed by the
     * pool
     * @param timeoutMillis timeout in millis to wait for a heartbeat from the client
     * before disconnecting them
     */
    public NGServer(NGListeningAddress listeningAddress, int sessionPoolSize, int timeoutMillis, InputStream in, PrintStream out, PrintStream err, Logger logger, DomainSocketProvider domainSocketProvider, boolean redirectStreams) {
        this.listeningAddress = listeningAddress;
        this.redirectStreams = redirectStreams;
        this.in = in;
        this.out = out;
        this.err = err;
        this.LOG = logger;

        aliasManager = new AliasManager();
        allNailStats = new java.util.HashMap();
        // allow a maximum of 10 idle threads.  probably too high a number
        // and definitely should be configurable in the future
        sessionPool = new NGSessionPool(this, sessionPoolSize, LOG, redirectStreams);
        heartbeatTimeoutMillis = timeoutMillis;

        this.domainSocketProvider = domainSocketProvider;
    }

    /**
     * Sets a flag that determines whether Nails can be executed by class name.
     * If this is false, Nails can only be run via aliases (and you should
     * probably remove ng-alias from the AliasManager).
     *
     * @param allowNailsByClassName true iff Nail lookups by classname are
     * allowed
     */
    public void setAllowNailsByClassName(boolean allowNailsByClassName) {
        this.allowNailsByClassName = allowNailsByClassName;
    }

    /**
     * Returns a flag that indicates whether Nail lookups by classname are
     * allowed. If this is false, Nails can only be run via aliases.
     *
     * @return a flag that indicates whether Nail lookups by classname are
     * allowed.
     */
    public boolean allowsNailsByClassName() {
        return (allowNailsByClassName);
    }

    /**
     * Sets the default class to use for the Nail if no Nails can be found via
     * alias or classname. (may be
     * <code>null</code>, in which case NailGun will use its own default)
     *
     * @param defaultNailClass the default class to use for the Nail if no Nails
     * can be found via alias or classname. (may be
     * <code>null</code>, in which case NailGun will use its own default)
     */
    public void setDefaultNailClass(Class defaultNailClass) {
        this.defaultNailClass = defaultNailClass;
    }

    /**
     * Returns the default class that will be used if no Nails can be found via
     * alias or classname.
     *
     * @return the default class that will be used if no Nails can be found via
     * alias or classname.
     */
    public Class getDefaultNailClass() {
        return ((defaultNailClass == null) ? DefaultNail.class : defaultNailClass);
    }

    /**
     * Returns the current NailStats object for the specified class, creating a
     * new one if necessary
     *
     * @param nailClass the class for which we're gathering stats
     * @return a NailStats object for the specified class
     */
    private NailStats getOrCreateStatsFor(Class nailClass) {
        NailStats result;
        synchronized (allNailStats) {
            String nailClassName = nailClass.getName();
            result = allNailStats.get(nailClassName);
            if (result == null) {
                result = new NailStats(nailClassName);
                allNailStats.put(nailClassName, result);
            }
        }
        return result;
    }

    /**
     * Provides a means for an NGSession to register the starting of a nail
     * execution with the server.
     *
     * @param nailClass the nail class that was launched
     */
    void nailStarted(Class nailClass) {
        NailStats stats = getOrCreateStatsFor(nailClass);
        stats.nailStarted();
    }

    /**
     * Provides a means for an NGSession to register the completion of a nails
     * execution with the server.
     *
     * @param nailClass the nail class that finished
     */
    void nailFinished(Class nailClass) {
        NailStats stats = getOrCreateStatsFor(nailClass);
        stats.nailFinished();
    }

    /**
     * Returns a snapshot of this NGServer's nail statistics. The result is a
     * <code>java.util.Map</code>, keyed by class name, with <a
     * href="NailStats.html">NailStats</a> objects as values.
     *
     * @return a snapshot of this NGServer's nail statistics.
     */
    public Map<String, NailStats> getNailStats() {
        Map<String, NailStats> result = new java.util.TreeMap();
        synchronized (allNailStats) {
            for (Iterator i = allNailStats.keySet().iterator(); i.hasNext();) {
                String nailclass = (String) i.next();
                result.put(nailclass, (NailStats)(allNailStats.get(nailclass)).clone());
            }
        }
        return result;
    }

    /**
     * Returns the AliasManager in use by this NGServer.
     *
     * @return the AliasManager in use by this NGServer.
     */
    public AliasManager getAliasManager() {
        return (aliasManager);
    }

    /**
     * Signal Nailgun server that it needs to stop listening to incoming nail requests and shut down itself after it
     * processes all current nails
     * The function returns immediately, actual shutdown will happen later
     */
    public void signalExit() {
        ForkJoinPool.commonPool().submit(() -> shutdown(true));
    }

    /**
     * Shuts down the server. The server will stop listening and its thread
     * will finish. Any running nails will be allowed to finish.
     *
     * @param exitVM if true, this method will also exit the JVM after calling
     * nailShutdown() on any nails.
     */
    public void shutdown(boolean exitVM) {
        if (shutdown.getAndSet(true)) {
            return;
        }

        try {
            serversocket.close();
        } catch (Throwable ex) {
            LOG.warn("Exception closing server socket on Nailgun server shutdown", ex);
        }

        // close all idle sessions and wait for all running sessions to complete
        try {
            sessionPool.shutdown();
        } catch (Throwable ex) {
            // we are going to die anyways so let's just continue
            LOG.warn("Exception shutting down Nailgun server", ex);
        }

        // restore system streams
        if (redirectStreams) {
            System.setIn(in);
            System.setOut(out);
            System.setErr(err);
        }

        // Security manager is always disabled
        //System.setSecurityManager(originalSecurityManager);

        if (exitVM) {
            System.exit(0);
        }
    }

    /**
     * Returns true iff the server is currently running.
     *
     * @return true iff the server is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the port on which this server is (or will be) listening.
     *
     * @return the port on which this server is (or will be) listening.
     */
    public int getPort() {
        return ((serversocket == null) ? listeningAddress.getInetPort() : serversocket.getLocalPort());
    }

    /**
     * Listens for new connections and launches NGSession threads to process
     * them.
     */
    public void run() {
        running.set(true);
        NGSession sessionOnDeck = null;

        originalSecurityManager = System.getSecurityManager();

        // Remove overhead of security manager https://github.com/facebook/nailgun/issues/134
        //System.setSecurityManager(
        //        new NGSecurityManager(
        //        originalSecurityManager));

        if (redirectStreams) {
            synchronized (System.in) {
                if (!(System.in instanceof ThreadLocalInputStream)) {
                    System.setIn(new ThreadLocalInputStream(in));
                    System.setOut(new ThreadLocalPrintStream(out));
                    System.setErr(new ThreadLocalPrintStream(err));
                }
            }
        }

        try {
            if (listeningAddress.isInetAddress()) {
                if (listeningAddress.getInetAddress() == null) {
                    serversocket = new ServerSocket(listeningAddress.getInetPort());
                } else {
                    serversocket = new ServerSocket(listeningAddress.getInetPort(), 0, listeningAddress.getInetAddress());
                }
            } else {
                serversocket = domainSocketProvider.apply();
            }

            String portDescription;
            if (listeningAddress.isInetAddress() && listeningAddress.getInetPort() == 0) {
                // if the port is 0, it will be automatically determined.
                // add this little wait so the ServerSocket can fully
                // initialize and we can see what port it chose.
                int runningPort = getPort();
                while (runningPort == 0) {
                    try {
                        Thread.sleep(50);
                    } catch (Throwable toIgnore) {
                    }
                    runningPort = getPort();
                }
                portDescription = ", port " + runningPort;
            } else {
                portDescription = "";
            }

            // Only after this point nailgun server is ready to accept connections on all platforms.
            // test_ng.py on *nix relies on reading this line from stdout to start connecting to server.
            err.println("NGServer "
                    + NGConstants.VERSION
                    + " started on "
                    + listeningAddress.toString()
                    + portDescription
                    + ".");

            while (!shutdown.get()) {
                sessionOnDeck = sessionPool.take();
                Socket socket = serversocket.accept();
                sessionOnDeck.run(socket);
            }

        } catch (Throwable t) {
            // if shutdown is called while the accept() method is blocking,
            // an exception will be thrown that we don't care about.  filter
            // those out.
            if (!shutdown.get()) {
                LOG.warn("Caught exception during accept", t);
            }
        }
        if (sessionOnDeck != null) {
            sessionOnDeck.shutdown();
        }
        running.set(false);
    }

    private static void usage() {
        System.err.println("Usage: java com.martiansoftware.nailgun.NGServer");
        System.err.println("   or: java com.martiansoftware.nailgun.NGServer port");
        System.err.println("   or: java com.martiansoftware.nailgun.NGServer IPAddress");
        System.err.println("   or: java com.martiansoftware.nailgun.NGServer IPAddress:port");
        System.err.println("   or: java com.martiansoftware.nailgun.NGServer IPAddress:port timeout");
    }

    /**
     * Creates and starts a new
     * <code>NGServer</code>. A single optional argument is valid, specifying
     * the port on which this
     * <code>NGServer</code> should listen. If omitted,
     * <code>NGServer.DEFAULT_PORT</code> will be used.
     *
     * @param args a single optional argument specifying the port on which to
     * listen.
     * @throws NumberFormatException if a non-numeric port is specified
     */
    public static void main(String[] args) throws NumberFormatException, UnknownHostException {

        if (args.length > 2) {
            usage();
            return;
        }

        // null server address means bind to everything local
        NGListeningAddress listeningAddress;
        int timeoutMillis = NGConstants.HEARTBEAT_TIMEOUT_MILLIS;

        // parse the command line parameters, which
        // may be an inetaddress to bind to, a port number,
        // an inetaddress followed by a port, separated
        // by a colon, or the string "local:/path/to/socket"
        // for a Unix domain socket or Windows named pipe.
        // if a second parameter is provided it
        // is interpreted as the number of milliseconds to
        // wait between heartbeats before considering the
        // client to have disconnected.
        if (args.length != 0) {
            String[] argParts = args[0].split(":");
            String addrPart = null;
            String portPart = null;
            if (argParts.length == 2) {
                addrPart = argParts[0];
                portPart = argParts[1];
            } else if (argParts[0].indexOf('.') >= 0) {
                addrPart = argParts[0];
            } else {
                portPart = argParts[0];
            }
            if ("local".equals(addrPart) && portPart != null) {
                // Treat the port part as a path to a local Unix domain socket
                // or Windows named pipe.
                listeningAddress = new NGListeningAddress(portPart);
            } else if (addrPart != null && portPart != null) {
                listeningAddress = new NGListeningAddress(
                    InetAddress.getByName(addrPart), Integer.parseInt(portPart));
            } else if (addrPart != null && portPart == null) {
                listeningAddress = new NGListeningAddress(
                    InetAddress.getByName(addrPart), NGConstants.DEFAULT_PORT);
            } else {
                listeningAddress = new NGListeningAddress(null, Integer.parseInt(portPart));
            }
            if (args.length == 2) {
                timeoutMillis = Integer.parseInt(args[1]);
            }
        } else {
            listeningAddress = new NGListeningAddress(null, NGConstants.DEFAULT_PORT);
        }

        NGServer server = new NGServer(listeningAddress, DEFAULT_SESSIONPOOLSIZE, timeoutMillis, System.in, System.out, System.err);
        Thread t = new Thread(server);
        t.setName("NGServer(" + listeningAddress.toString() + ")");
        t.start();

        Runtime.getRuntime().addShutdownHook(new NGServerShutdowner(server));
    }

    public int getHeartbeatTimeout() {
        return heartbeatTimeoutMillis;
    }

    /**
     * A shutdown hook that will cleanly bring down the NGServer if it is
     * interrupted.
     *
     * @author <a href="http://www.martiansoftware.com/contact.html">Marty
     * Lamb</a>
     */
    private static class NGServerShutdowner extends Thread {

        private NGServer server = null;

        NGServerShutdowner(NGServer server) {
            this.server = server;
        }

        public void run() {

            int count = 0;
            server.shutdown(false);

            // give the server up to five seconds to stop.  is that enough?
            // remember that the shutdown will call nailShutdown in any
            // nails as well
            while (server.isRunning() && (count < 50)) {

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
                ++count;
            }

            if (server.isRunning()) {
                System.err.println("Unable to cleanly shutdown server.  Exiting JVM Anyway.");
            } else {
                System.out.println("NGServer shut down.");
            }
        }
    }
}
