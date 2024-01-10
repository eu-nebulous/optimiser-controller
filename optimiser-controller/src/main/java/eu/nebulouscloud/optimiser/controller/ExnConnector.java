package eu.nebulouscloud.optimiser.controller;

import eu.nebulouscloud.exn.Connector;
import eu.nebulouscloud.exn.core.Consumer;
import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.core.Handler;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;
import eu.nebulouscloud.exn.settings.StaticExnConfig;
import org.apache.qpid.protonj2.client.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

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

    /** The Connector used to talk with ActiveMQ */
    private final Connector conn;
    /** if non-null, signals after the connector is stopped */
    private CountDownLatch synchronizer = null;

    private static final ObjectMapper mapper = new ObjectMapper();

    /** The channel where we listen for app creation messages */
    public static final String app_creation_channel = "eu.nebulouscloud.ui.dsl.generic.>";

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
            // List.of(new Publisher("config", "config", true)),
            List.of(),
            List.of(
                new Consumer("ui_all", app_creation_channel,
                    new AppCreationMessageHandler(), true, true)),
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
        log.info("ExnConnector started.");
    }

    /**
     * Disconnect from ActiveMQ and stop all Consumer processes.  Also count
     * down the countdown latch passed in the {@link
     * ExnConnector#start(CountDownLatch)} method if applicable.
     */
    public synchronized void stop() {
        conn.stop();
        if (synchronizer != null) {
            synchronizer.countDown();
        }
        log.info("ExnConnector stopped.");
    }

    /**
     * A message handler that processes app creation messages coming in via
     * `eu.nebulouscloud.ui.dsl.generic`.  Such messages contain, among
     * others, the KubeVela YAML definition and mapping from KubeVela
     * locations to AMPL variables.
     *
     * When receiving a message, the handler tries to instantiate a
     * `NebulousApp` object.
     */
    // Note that there is another, earlier app creation message sent via the
    // channel `eu.nebulouscloud.ui.application.new`, but its format is not
    // yet defined as of 2024-01-08.
    public class AppCreationMessageHandler extends Handler {
        @Override
        public void onMessage(String key, String address, Map body, Message message, Context context) {
            try {
                String app_id = message.subject();
                log.info("App creation message received for app {}", app_id);
                NebulousApp app = NebulousApp.newFromAppMessage(mapper.valueToTree(body));
                NebulousApp.add(app);
                // TODO: do more applicaton initialization work here: set up channels, calculate AMPL, etc.
            } catch (Exception e) {
                log.error("Error while receiving app creation message over {}: {}",
                    app_creation_channel, e);
            }
        }
    }
}
