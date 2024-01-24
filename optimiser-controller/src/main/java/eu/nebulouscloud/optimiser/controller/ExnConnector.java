package eu.nebulouscloud.optimiser.controller;

import eu.nebulouscloud.exn.Connector;
import eu.nebulouscloud.exn.core.Consumer;
import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.core.Handler;
import eu.nebulouscloud.exn.core.Publisher;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;
import eu.nebulouscloud.exn.settings.StaticExnConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.qpid.protonj2.client.Message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
@Slf4j
public class ExnConnector {

    /** The Connector used to talk with ActiveMQ */
    private final Connector conn;
    /** if non-null, signals after the connector is stopped */
    private CountDownLatch synchronizer = null;

    private static final ObjectMapper mapper = new ObjectMapper();

    /** The topic where we listen for app creation messages */
    public static final String app_creation_channel = "eu.nebulouscloud.ui.dsl.generic.>";
    /** The topic where we send AMPL messages */
    // 1 object with key: filename, value: AMPL file (serialized)
    public static final String ampl_message_channel = "eu.nebulouscloud.optimiser.solver.model";

    /**
      * The Message producer for sending AMPL files, shared between all
      * NebulousApp instances.
      *
      * @return the publisher configured to send AMPL files to the solver.
      */
    @Getter
    private final Publisher amplMessagePublisher;

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
        amplMessagePublisher = new Publisher("controller_ampl", ampl_message_channel, true, true);

        conn = new Connector("optimiser_controller",
            callback,
            // List.of(new Publisher("config", "config", true)),
            List.of(amplMessagePublisher),
            List.of(new Consumer("ui_app_messages", app_creation_channel, new AppCreationMessageHandler(), true, true)),
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
        log.debug("ExnConnector started.");
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
        log.debug("ExnConnector stopped.");
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
                JsonNode appMessage = mapper.valueToTree(body);
                Main.logFile("app-message-" + app_id + ".json", appMessage);
                NebulousApp app = NebulousApp.newFromAppMessage(mapper.valueToTree(body), amplMessagePublisher);
                NebulousApps.add(app);
                app.sendAMPL();
            } catch (Exception e) {
                log.error("Error while receiving app creation message", e);
            }
        }
    }

    /**
     * A message handler for incoming messages from the solver, containing
     * mappings from variable names to new values.  This is used to produce an
     * updated KubeVela YAML file.
     */
    public class SolverSolutionMessageHandler extends Handler {
        @Override
        public void onMessage(String key, String address, Map body, Message message, Context context) {
            // We'll talk a lot with SAL etc, so we should maybe fire up a
            // thread so as not to block here.
            try {
                String app_id = message.subject();
                NebulousApp app = NebulousApps.get(app_id);
                if (app == null) {
                    log.warn("Received solver solutions for non-existant app {}, discarding.", app_id);
                    return;
                } else {
                    log.debug("Received solver solutions for app {}", app_id);
                    ObjectNode json_body = mapper.convertValue(body, ObjectNode.class);
                    app.processSolution(json_body);
                }
            } catch (Exception e) {
                log.error("Error while processing solver solutions message", e);
            }
        }
    }
}
