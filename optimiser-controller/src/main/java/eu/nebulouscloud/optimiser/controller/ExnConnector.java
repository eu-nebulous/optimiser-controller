package eu.nebulouscloud.optimiser.controller;

import eu.nebulouscloud.exn.Connector;
import eu.nebulouscloud.exn.core.Consumer;
import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.core.Handler;
import eu.nebulouscloud.exn.core.Publisher;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;
import eu.nebulouscloud.exn.settings.StaticExnConfig;
import org.apache.qpid.protonj2.client.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A class that connects to the EXN middleware and starts listening to
 * messages from the ActiveMQ server.
 *
 * This class will drive the main behavior of the optimiser-controller: the
 * `Consumer` objects created in {@link ExnConnector#ExnConnector} receive
 * incoming messages and react to them, sending out messages in turn.
 */
public class ExnConnector {

    private static final Logger log = LoggerFactory.getLogger(ExnConnector.class);

    /// The Connector used to talk with ActiveMQ
    private final Connector conn;
    /// if non-null, signals after the connector is stopped
    private CountDownLatch synchronizer = null;

    /**
     * Create a connection to ActiveMQ via the exn middleware, and set up the
     * initial publishers and consumers.
     *
     * @param host the host of the ActiveMQ server (probably "localhost")
     * @param port the port of the ActiveMQ server (usually 5672)
     * @param name the login name to use
     * @param password the login password to use
     * @param callback A ConnectorHandler object.  Its {@link
     *  ConnectorHandler#onReady} method will be called after the {@link
     *  Connector#start} method has connected and set up all handlers.
     */
    public ExnConnector(String host, int port, String name, String password, ConnectorHandler callback) {
        conn = new Connector("optimiser_controller",
                             callback,
                             List.of(new Publisher("config", "config", true)),
                             List.of(new Consumer("ui_all", "eu.nebulouscloud.ui.preferences.>",
                                                  new MyConsumerHandler(), true, true),
                                     new Consumer("ui_all", "eu.nebulouscloud.ui.config.>",
                                                  new MyConsumerHandler(), true, true)),
                             false,
                             false,
                             new StaticExnConfig(host, port, name, password, 15, "eu.nebulouscloud"));
    }

    /**
     * Connect to ActiveMQ and activate all publishers and consumers.  It is
     * an error to start the controller more than once.
     *
     * @param synchronizer if non-null, a countdown latch that will be signaled
     *  when the connector is stopped by calling {@link
     *  CountDownLatch#countDown} once.
     */
    public synchronized void start(CountDownLatch synchronizer) {
        this.synchronizer = synchronizer;
        conn.start();
    }

    /**
     * Disconnect from ActiveMQ and stop all Consumer processes.  Also count
     * down the countdown latch passed in the {@link start} method if
     * applicable.
     */
    public synchronized void stop() {
        conn.stop();
        if (synchronizer != null) {
            synchronizer.countDown();
        }
    }

    /**
     * Sample message handler; we might have different handlers for each topic
     * we listen to instead.
     */
    private class MyConsumerHandler extends Handler {
        // `body` is of type `Map<String, Object>` by default, so can be
        // handled by various JSON libraries directly.
        @Override
        public void onMessage(String key, String address, Map body, Message message, Context context)
        {
            log.info("Message delivered for key {} => {} ({}) = {}",
                key, address, body, message);
        }
    }
}
