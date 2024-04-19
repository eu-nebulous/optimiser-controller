package eu.nebulouscloud.optimiser.controller;

import eu.nebulouscloud.exn.Connector;
import eu.nebulouscloud.exn.core.Consumer;
import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.core.Handler;
import eu.nebulouscloud.exn.core.Publisher;
import eu.nebulouscloud.exn.core.SyncedPublisher;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;
import eu.nebulouscloud.exn.settings.StaticExnConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.Requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * A class that connects to the EXN middleware and starts listening to
 * messages from the ActiveMQ server.
 *
 * <p>This class will drive the main behavior of the optimiser-controller: the
 * `Consumer` objects created in {@link ExnConnector#ExnConnector} receive
 * incoming messages and react to them, sending out messages in turn.
 *
 * <p>The class also provides methods wrapping the exn-sal middleware
 * endpoints, converting from raw JSON responses to sal-common datatypes where
 * possible.
 */
@Slf4j
public class ExnConnector {

    /** The Connector used to talk with ActiveMQ */
    private final Connector conn;
    /** if non-null, signals after the connector is stopped */
    private CountDownLatch synchronizer = null;

    private static final ObjectMapper mapper = new ObjectMapper();

    /** The topic where we listen for app creation messages. */
    public static final String app_creation_channel = "eu.nebulouscloud.ui.dsl.generic";
    /** The topic with an application's relevant performance indicators. */
    public static final String performance_indicators_channel =
        "eu.nebulouscloud.optimiser.utilityevaluator.performanceindicators";
    /** The topic with incoming solver solution messages.  See
      * https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/2-solvers */
    public static final String solver_solution_channel = "eu.nebulouscloud.optimiser.solver.solution";
    /** The topic where we send AMPL messages */
    // 1 object with key: filename, value: AMPL file (serialized)
    public static final String ampl_message_channel = "eu.nebulouscloud.optimiser.controller.model";

    /** The metrics to send to EMS and Solver */
    public static final String metric_list_channel = "eu.nebulouscloud.optimiser.controller.metric_list";
    /** The status channel for the solvers.  We send out an app's AMPL file on
     * the channel named by {@link #ampl_message_channel} when getting the
     * "started" message from a solver. */
    public static final String solver_status_channel = "eu.nebulouscloud.solver.state";

    /** The per-app status channel, read by at least the UI and the solver. */
    public static final String app_status_channel = "eu.nebulouscloud.optimiser.controller.app_state";

    /**
      * The Message producer for sending AMPL files, shared between all
      * NebulousApp instances.
      *
      * @return the publisher configured to send AMPL files to the solver.
      */
    @Getter
    private final Publisher amplMessagePublisher;

    /** The publisher for sending the metric list to EMS and Solver during app
      * creation. */
    @Getter
    private final Publisher metricListPublisher;

    /**
     * The publisher for broadcasting the current status of each application
     * (new, ready, deploying, running, failed).
     */
    @Getter
    private final Publisher appStatusPublisher;

    // ----------------------------------------
    // Communication with SAL

    /** The findNodeCandidates endpoint.  Should not be used during normal
      * operation--ask the broker instead. */
    public final SyncedPublisher findSalNodeCandidates;
    /** The findNodeCandidates endpoint (Broker's version).  This one adds
      * attributes "score", "rank" to the answer it gets from SAL. */
    public final SyncedPublisher findBrokerNodeCandidates;
    /** The defineCluster endpoint. */
    public final SyncedPublisher defineCluster;
    /** The getCluster endpoint. */
    public final SyncedPublisher getCluster;
    /** The labelNodes endpoint. */
    public final SyncedPublisher labelNodes;
    /** The deployCluster endpoint. */
    public final SyncedPublisher deployCluster;
    /** The deployApplication endpoint. */
    public final SyncedPublisher deployApplication;
    /** The scaleOut endpoint. */
    public final SyncedPublisher scaleOut;
    /** The scaleIn endpoint. */
    public final SyncedPublisher scaleIn;
    /** The deleteCluster endpoint. */
    public final SyncedPublisher deleteCluster;

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
        metricListPublisher = new Publisher("controller_metric_list", metric_list_channel, true, true);
        appStatusPublisher = new Publisher("app_status", app_status_channel, true, true);
        findSalNodeCandidates = new SyncedPublisher("findSalNodeCandidates", "eu.nebulouscloud.exn.sal.nodecandidate.get", true, true);
        findBrokerNodeCandidates = new SyncedPublisher("findBrokerNodeCandidates", "eu.nebulouscloud.cfsb.get_node_candidates", true, true);
        defineCluster = new SyncedPublisher("defineCluster", "eu.nebulouscloud.exn.sal.cluster.define", true, true);
        getCluster = new SyncedPublisher("getCluster", "eu.nebulouscloud.exn.sal.cluster.get", true, true);
        labelNodes = new SyncedPublisher("labelNodes", "eu.nebulouscloud.exn.sal.cluster.label", true, true);
        deployCluster = new SyncedPublisher("deployCluster", "eu.nebulouscloud.exn.sal.cluster.deploy", true, true);
        deployApplication = new SyncedPublisher("deployApplication", "eu.nebulouscloud.exn.sal.cluster.deployapplication", true, true);
        scaleOut = new SyncedPublisher("scaleOut", "eu.nebulouscloud.exn.sal.cluster.scaleout", true, true);
        scaleIn = new SyncedPublisher("scaleIn", "eu.nebulouscloud.exn.sal.cluster.scalein", true, true);
        deleteCluster = new SyncedPublisher("deleteCluster", "eu.nebulouscloud.exn.sal.cluster.delete", true, true);

        conn = new Connector("optimiser_controller",
            callback,
            List.of(
                // asynchronous topics for sending out controller status
                amplMessagePublisher,
                metricListPublisher,
                appStatusPublisher,
                // synchronous communication with SAL via exn-middleware
                findSalNodeCandidates,
                findBrokerNodeCandidates,
                defineCluster,
                getCluster,
                labelNodes,
                deployCluster,
                deployApplication,
                scaleOut,
                scaleIn,
                deleteCluster),
            List.of(
                new Consumer("solver_status", solver_status_channel,
                    new SolverStatusMessageHandler(), true, true),
                new Consumer("ui_app_messages", app_creation_channel,
                    new AppCreationMessageHandler(), true, true),
                new Consumer("performance_indicator_messages", performance_indicators_channel,
                    new PerformanceIndicatorMessageHandler(), true, true),
                new Consumer("solver_solution_messages", solver_solution_channel,
                    new SolverSolutionMessageHandler(), true, true)),
            true,
            true,
            new StaticExnConfig(host, port, name, password, 15, "eu.nebulouscloud"));
    }

    /**
     * Connect to ActiveMQ and activate all publishers and consumers.  It is
     * an error to start the controller more than once.
     *
     * @param synchronizer if non-null, a countdown latch that will be
     *  signaled when the connector is stopped by calling {@link
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
     * #start(CountDownLatch)} method if applicable.
     */
    public synchronized void stop() {
        conn.stop();
        if (synchronizer != null) {
            synchronizer.countDown();
        }
        log.debug("ExnConnector stopped.");
    }

    // ----------------------------------------
    // Message Handlers

    /**
     * A message handler that processes app creation messages coming in via
     * `eu.nebulouscloud.ui.dsl.generic`.  Such messages contain, among
     * others, the KubeVela YAML definition and mapping from KubeVela
     * locations to AMPL variables.
     *
     * <p>When receiving a message, the handler instantiates a `NebulousApp`
     * object.  If we already received the performance indicators from the
     * utility evaluator, perform initial deployment; otherwise, wait.
     */
    // Note that there is another, earlier app creation message sent via the
    // channel `eu.nebulouscloud.ui.application.new`, but its format is not
    // yet defined as of 2024-01-08.
    public class AppCreationMessageHandler extends Handler {
        @Override
        public void onMessage(String key, String address, Map body, Message message, Context context) {
            NebulousApp app = null;
            try {
                Object app_id = message.property("application"); // might be null
                if (app_id == null) app_id = message.subject();
                // if app_id is still null, the filename will look a bit funky but it's not a problem
                log.info("App creation message received", keyValue("appId", app_id));
                JsonNode appMessage = mapper.valueToTree(body);
                Main.logFile("app-message-" + app_id + ".json", appMessage.toPrettyString());
                app = NebulousApp.newFromAppMessage(
                    // TODO create a new ExnConnector here?
                    mapper.valueToTree(body), ExnConnector.this);
                NebulousApps.add(app);
                String appIdFromMessage = app.getUUID();
                if (NebulousApps.relevantPerformanceIndicators.containsKey(appIdFromMessage)) {
                    // If the performance indicators haven't arrived yet, this
                    // will happen in PerformanceIndicatorMessageHandler below.
                    app.setStateReady(NebulousApps.relevantPerformanceIndicators.get(appIdFromMessage));
                    app.deployUnmodifiedApplication();
                    // Not strictly necessary to remove the performance
                    // indicators, but let's not leave unneeded data around
                    NebulousApps.relevantPerformanceIndicators.remove(appIdFromMessage);
                }
            } catch (Exception e) {
                log.error("Error while receiving app creation message", e);
                if (app != null) app.setStateFailed();
            }
        }
    }

    /**
     * A handler that receives the performance indicators that the utility
     * evaluator sends.  If the application object already exists (usually the
     * case), start initial deployment, otherwise store the performance
     * indicators so the initial app creation message can pick them up.
     */
    public class PerformanceIndicatorMessageHandler extends Handler {
        @Override
        public void onMessage(String key, String address, Map body, Message message, Context context) {
            Object appIdObject = null;
            try {
                appIdObject = message.property("application");
                if (appIdObject == null) appIdObject = message.subject();
            } catch (ClientException e) {
                log.error("Received performance indicator message without application property, aborting");
                return;
            }
            String appId = null;
            if (appIdObject == null) {
                log.error("Received performance indicator message without application property, aborting");
            } else {
                appId = appIdObject.toString(); // should be a string already
                log.info("Received performance indicator message", keyValue("appId", appId));
            }
            JsonNode appMessage = mapper.valueToTree(body);
            Main.logFile("performance-indicators-" + appIdObject + ".json", appMessage.toPrettyString());
            NebulousApp app = NebulousApps.get(appId);
            if (app == null) {
                NebulousApps.relevantPerformanceIndicators.put(appId, appMessage);
                log.info("Received performance indicator message for unknown app object, storing",
                    keyValue("appId", appId));
            } else {
                app.setStateReady(appMessage);
                app.deployUnmodifiedApplication();
            }
        }
    }

    /**
     * A handler that detects when the solver has started for a given
     * application.
     */
    public class SolverStatusMessageHandler extends Handler {
        @Override
        public void onMessage(String key, String address, Map body, Message message, Context context) {
            Object appIdObject = null;
            String appId = null;
            try {
                appIdObject = message.property("application");
            } catch (ClientException e) {
                log.error("Received solver ready message {} without application property, aborting", body);
                return;
            }
            if (appIdObject == null) {
                log.error("Received solver ready message {} without application property, aborting", body);
                return;
            } else {
                appId = appIdObject.toString(); // should be a string already
                log.info("Received solver status message {}", body, keyValue("appId", appId));
            }

            JsonNode appMessage = mapper.valueToTree(body);
            String status = appMessage.at("/state").textValue();
            if (status == null || !status.equals("started")) return;

            NebulousApp app = NebulousApps.get(appId);
            if (app == null) {
                log.info("Received solver status message for unknown app object, this should not happen",
                    keyValue("appId", appId));
            } else {
                app.sendAMPL();
                app.sendMetricList(); // re-send for solver
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
                ObjectNode json_body = mapper.convertValue(body, ObjectNode.class);
                String app_id = message.property("application").toString(); // should be string already, but don't want to cast
                if (app_id == null) {
                    log.warn("Received solver solution without 'application' message property, discarding it");
                    return;
                }
                Main.logFile("solver-solution-" + app_id + ".json", json_body.toPrettyString());
                NebulousApp app = NebulousApps.get(app_id);
                if (app == null) {
                    log.warn("Received solver solutions for non-existant application, discarding.",
                        keyValue("appId", app_id));
                    return;
                } else {
                    log.debug("Received solver solutions for application",
                        keyValue("appId", app_id));
                    app.processSolution(json_body);
                }
            } catch (Exception e) {
                log.error("Error while processing solver solutions message", e);
            }
        }
    }

    // ----------------------------------------
    // Communication with SAL

    /**
     * Extract and check the SAL response from an exn-middleware response.
     * The SAL response will be valid JSON encoded as a string in the "body"
     * field of the response.  If the response is of the following form, log
     * an error and return a missing node instead:
     *
     * <pre>{@code
     * {
     *   "key": <known exception key>,
     *   "message": "some error message"
     * }
     * }</pre>
     *
     * @param responseMessage The response from exn-middleware.
     * @param appID The application ID, used for logging only.
     * @param caller Caller information, used for logging only.
     * @return The SAL response as a parsed JsonNode, or a node where {@code
     *  isMissingNode()} will return true if SAL reported an error.
     */
    private static JsonNode extractPayloadFromExnResponse(Map<String, Object> responseMessage, String appID, String caller) {
        JsonNode response = mapper.valueToTree(responseMessage);
        String salRawResponse = response.at("/body").asText(); // it's already a string, asText() is for the type system
        JsonNode metadata = response.at("/metaData");
        JsonNode salResponse = mapper.missingNode(); // the data coming from SAL
        try {
            salResponse = mapper.readTree(salRawResponse);
        } catch (JsonProcessingException e) {
            log.error("Could not read message body as JSON: body = '{}'", salRawResponse,
                keyValue("appId", appID), keyValue("caller", caller), e);
            return mapper.missingNode();
        }
        if (!metadata.at("/status").asText().startsWith("2")) {
            // we only accept 200, 202, numbers of that nature
            log.error("exn-middleware-sal request failed with error code '{}' and message '{}'",
                metadata.at("/status"),
                salResponse.at("/message").asText(),
                keyValue("appId", appID), keyValue("caller", caller));
            return mapper.missingNode();
        }
        return salResponse;
    }

    /**
     * Get list of node candidates from the resource broker that fulfill the
     * given requirements, and sort them by rank and score so that better node
     * candidates come first in the result.
     *
     * <p>A candidate is better than another one if it has a lower rank or, if
     * the rank is equal, a higher score.
     *
     * @param requirements The list of requirements.
     * @param appID The application ID.
     * @return A sorted List containing node candidates, better candidates
     *  first.
     */
    public List<NodeCandidate> findNodeCandidates(List<Requirement> requirements, String appID) {
        Map<String, Object> msg;
        try {
            msg = Map.of(
                "metaData", Map.of("user", "admin"),
                "body", mapper.writeValueAsString(requirements));
        } catch (JsonProcessingException e) {
            log.error("Could not convert requirements list to JSON string (this should never happen)",
                keyValue("appId", appID), e);
            return null;
        }
        Map<String, Object> response = findBrokerNodeCandidates.sendSync(msg, appID, null, false);
        // Note: we do not call extractPayloadFromExnResponse here, since this
        // response does not come from the exn-middleware.
        ObjectNode jsonBody = mapper.convertValue(response, ObjectNode.class);
        // Note: what we would really like to do here is something like:
        //     return Arrays.asList(mapper.readValue(response, NodeCandidate[].class));
        // But since the broker adds two attributes, the array elements cannot
        // be deserialized into org.ow2.proactive.sal.model.NodeCandidate
        // objects.
        List<JsonNode> result = Arrays.asList(mapper.convertValue(jsonBody.withArray("/body"), JsonNode[].class));
        result.sort((JsonNode c1, JsonNode c2) -> {
                long rank1 = c1.at("/rank").longValue();
                long rank2 = c2.at("/rank").longValue();
                double score1 = c1.at("/score").doubleValue();
                double score2 = c2.at("/score").doubleValue();
                int cpu1 = c1.at("/hardware/cores").intValue();
                int cpu2 = c2.at("/hardware/cores").intValue();
                int ram1 = c1.at("/hardware/ram").intValue();
                int ram2 = c2.at("/hardware/ram").intValue();
                // We return < 0 if c1 < c2.  Since we want to sort better
                // candidates first, c1 < c2 if rank is lower or rank is equal
                // and score is higher. (Lower rank = better, higher score =
                // better.)  Afterwards we rank lower hardware requirements
                // better than higher ones.
                if (rank1 != rank2) return Math.toIntExact(rank1 - rank2);
                else if (score2 != score1) return Math.toIntExact(Math.round(score2 - score1));
                else if (cpu1 != cpu2) return cpu1 - cpu2;
                else return ram1 - ram2;
            });
        return result.stream()
            .map(candidate ->
                mapper.convertValue(
                    ((ObjectNode)candidate).deepCopy().remove(List.of("score", "rank")),
                    NodeCandidate.class))
            .collect(Collectors.toList());
    }

    /**
     * Get list of node candidates from the resource broker that fulfil the
     * given requirements.
     *
     * <p>Note that we cannot convert the result to a list containing {@code
     * org.ow2.proactive.sal.model.NodeCandidate} instances, since the broker
     * adds the additional fields {@code score} and {@code ranking}.  Instead
     * we return a JSON {@code ArrayNode} containing {@code ObjectNode}s in
     * the format specified at
     * https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/nodecandidates-endpoints.md#71--filter-node-candidates-endpoint
     * but with these two additional attributes.
     *
     * @param requirements The list of requirements.
     * @param appID The application ID.
     * @return A list containing node candidates, or null in case of error.
     */
    public List<NodeCandidate> findNodeCandidatesFromSal(List<Requirement> requirements, String appID) {
        Map<String, Object> msg;
        try {
            msg = Map.of(
                "metaData", Map.of("user", "admin"),
                "body", mapper.writeValueAsString(requirements));
        } catch (JsonProcessingException e) {
            log.error("Could not convert requirements list to JSON string (this should never happen)",
                keyValue("appId", appID), e);
            return null;
        }
        Map<String, Object> response = findSalNodeCandidates.sendSync(msg, appID, null, false);
        JsonNode payload = extractPayloadFromExnResponse(response, appID, "findNodeCandidatesFromSal");
        if (payload.isMissingNode()) return null;
        if (!payload.isArray()) return null;
        List<NodeCandidate> candidates = Arrays.asList(mapper.convertValue(payload, NodeCandidate[].class));
        // We try to choose candidates with lower hardware requirements; sort by cores, ram
        candidates.sort((NodeCandidate c1, NodeCandidate c2) -> {
                int cpu1 = c1.getHardware().getCores();
                int cpu2 = c2.getHardware().getCores();
                long ram1 = c1.getHardware().getRam();
                long ram2 = c2.getHardware().getRam();
                if (cpu1 != cpu2) return cpu1 - cpu2;
                else return Math.toIntExact(ram1 - ram2);
            });
        return candidates;
    }

    /**
     * Define a cluster with the given name and node list.
     *
     * <p>The cluster is passed in a JSON of the following shape:
     *
     * <pre>{@code
     * {
     *     "name":"485d7-1",
     *     "master-node":"N485d7-1-masternode",
     *     "nodes":[
     *         {
     *             "nodeName":"n485d7-1-masternode",
     *             "nodeCandidateId":"8a7481018e8572f9018e857ed0c50c53",
     *             "cloudId":"demo-cloud"
     *         },
     *         {
     *             "nodeName":"n485d7-1-dummy-app-worker-1-1",
     *             "nodeCandidateId":"8a7481018e8572f9018e857ecfb30c21",
     *             "cloudId":"demo-cloud"
     *         }
     *     ],
     *     "env-var": {
     *         "APPLICATION_ID", "the-application-id"
     *     }
     * }
     * }</pre>
     *
     * <p>Each value for {@code nodeName} has to be globally unique, must
     * start with a letter and contain numbers, letters and hyphens only.
     *
     * <p>The values for {@code nodeCandidateId} and {@code cloudId} come from
     * the return value of a call to {@link #findNodeCandidates()}.
     *
     * @param appID The application's id, used only for logging.
     * @param clusterName The cluster name, used only for logging.
     * @param cluster A JSON object, as detailed above.
     * @return true if the cluster was successfully defined, false otherwise.
     */
    public boolean defineCluster(String appID, String clusterName, ObjectNode cluster) {
        // https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/deployment-manager-sal-1#specification-of-endpoints-being-developed
        Main.logFile("define-cluster-" + appID + ".json", cluster.toPrettyString());
        Map<String, Object> msg;
        try {
            msg = Map.of("metaData", Map.of("user", "admin"),
                "body", mapper.writeValueAsString(cluster));
        } catch (JsonProcessingException e) {
            log.error("Could not convert JSON to string (this should never happen)",
                keyValue("appId", appID), keyValue("clusterName", clusterName), e);
            return false;
        }
        Map<String, Object> response = defineCluster.sendSync(msg, appID, null, false);
        JsonNode payload = extractPayloadFromExnResponse(response, appID, "defineCluster");
        return payload.asBoolean();
    }

    /**
     * Get the definition of a cluster created by {@link #defineCluster}.
     *
     * @param clusterName The cluster name, as given in {@link defineCluster}.
     * @return The cluster definition, or null in case of error.
     */
    public JsonNode getCluster(String clusterName) {
        Map<String, Object> msg = Map.of("metaData", Map.of("user", "admin", "clusterName", clusterName));
        Map<String, Object> response = getCluster.sendSync(msg, clusterName, null, false);
        JsonNode payload = extractPayloadFromExnResponse(response, clusterName, "getCluster");
        return payload.isMissingNode() ? null : payload;
    }

    /**
     * Label the nodes with given names with the given labels.
     *
     * @param appID the application ID.
     * @param clusterID the cluster ID.
     * @param labels A map from node name to label.
     */
    public boolean labelNodes(String appID, String clusterID, JsonNode labels) {
        Map<String, Object> msg;
        try {
            msg = Map.of("metaData", Map.of("user", "admin", "clusterName", clusterID),
                "body", mapper.writeValueAsString(labels));
        } catch (JsonProcessingException e) {
            log.error("Could not convert JSON to string (this should never happen)",
                keyValue("appId", appID), e);
            return false;
        }
        Map<String, Object> response = labelNodes.sendSync(msg, appID, null, false);
        JsonNode payload = extractPayloadFromExnResponse(response, appID, "labelNodes");
        return payload.isMissingNode() ? false : true;
    }

    /**
     * Deploy a cluster created by {@link #defineCluster}.  Note that the call
     * will return before the cluster is ready, i.e., {@link #getCluster} must
     * be checked before trying to call {@link #labelNodes} or {@link
     * #deployApplication}.
     *
     * @param appID The application's id, used for logging only.
     * @param clusterName The name of the cluster.
     * @return true if the cluster was successfully deployed, false otherwise.
     */
    public boolean deployCluster(String appID, String clusterName) {
        // https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/deployment-manager-sal-1#specification-of-endpoints-being-developed
        Map<String, Object> msg = Map.of("metaData",
            Map.of("user", "admin", "clusterName", clusterName));
        Map<String, Object> response = deployCluster.sendSync(msg, appID, null, false);
        JsonNode payload = extractPayloadFromExnResponse(response, appID, "deployCluster");
        return payload.asBoolean();
    }

    /**
     * Submit a KubeVela file to a deployed cluster.
     *
     * @param appID The application's id.
     * @param clusterName The name of the cluster.
     * @param appName The name of the application.
     * @param kubevela The KubeVela file, with node affinity traits
     *  corresponding to the cluster definintion, serialized into a string.
     * @return the ProActive job ID, or -1 in case of failure.
     */
    public long deployApplication(String appID, String clusterName, String appName, String kubevela) {
        ObjectNode body = mapper.createObjectNode()
            .put("appFile", kubevela)
            .put("packageManager", "kubevela")
            .put("appName", appName)
            .put("action", "apply")
            .put("flags", "");
        Main.logFile("deploy-application-" + appID + ".json", body.toPrettyString());
        Map<String, Object> msg;
        try {
            String bodyStr = mapper.writeValueAsString(body);
            msg = Map.of("metaData", Map.of("user", "admin", "clusterName", clusterName),
                "body", bodyStr);
        } catch (JsonProcessingException e) {
            log.error("Could not convert JSON to string (this should never happen)",
                keyValue("appId", appID), keyValue("clusterName", clusterName), e);
            return -1;
        }
        Map<String, Object> response = deployApplication.sendSync(msg, appID, null, false);
        JsonNode payload = extractPayloadFromExnResponse(response, appID, "deployApplication");
        return payload.asLong();
    }

    /**
     * Add new nodes to a deployed cluster.
     *
     * <p>The new nodes are specified in the same way as in {@link
     * #defineCluster()}.
     *
     * @param appID The application's id, used only for logging.
     * @param clusterName the cluster name.
     * @param nodesToAdd The additional nodes to add.
     */
    public void scaleOut(String appID, String clusterName, ArrayNode nodesToAdd) {
        Map<String, Object> msg;
        try {
            msg = Map.of("metaData", Map.of("user", "admin",
                                            "clusterName", clusterName),
                         "body", mapper.writeValueAsString(nodesToAdd));
        } catch (JsonProcessingException e) {
            log.error("Could not convert JSON to string (this should never happen)",
                      keyValue("appId", appID), keyValue("clusterName", clusterName), e);
            return;
        }
        Map<String, Object> response = scaleOut.sendSync(msg, appID, null, false);
        // Called for side-effect only; we want to log errors.  The return
        // value from scaleOut is the same as getCluster, but since we have to
        // poll for cluster status anyway to make sure the new machines are
        // running, we do not return it here.
        JsonNode payload = extractPayloadFromExnResponse(response, appID, "scaleOut");
    }

    /**
     * Remove nodes from a deployed cluster.
     *
     * @param appID The application's id, used only for logging.
     * @param clusterName the cluster name.
     * @param superfluousNodes The names of nodes to be removed.
     * @return true if the call was successful, false otherwise.
     */
    public boolean scaleIn(String appID, String clusterName, List<String> superfluousNodes) {
        // https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/deployment-manager-sal-1#specification-of-endpoints-being-developed
        ArrayNode body = mapper.createArrayNode();
        superfluousNodes.forEach(nodeName -> body.add(nodeName));
        Map<String, Object> msg;
        try {
            msg = Map.of("metaData", Map.of("user", "admin", "clusterName", clusterName),
                "body", mapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            log.error("Could not convert JSON to string (this should never happen)",
                keyValue("appId", appID), keyValue("clusterName", clusterName), e);
            return false;
        }
        Map<String, Object> response = scaleIn.sendSync(msg, appID, null, false);
        JsonNode payload = extractPayloadFromExnResponse(response, appID, "scaleIn");
        return payload.asBoolean();
    }

    /**
     * Delete a cluster created by {@link #defineCluster}.
     *
     * @param appID The application's id, used for logging only.
     * @param clusterName The name of the cluster.
     * @return true if the cluster was successfully deleted, false otherwise.
     */
    public boolean deleteCluster(String appID, String clusterName) {
        // https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/deployment-manager-sal-1#specification-of-endpoints-being-developed
        Map<String, Object> msg = Map.of("metaData",
            Map.of("user", "admin", "clusterName", clusterName));
        Map<String, Object> response = deleteCluster.sendSync(msg, appID, null, false);
        JsonNode payload = extractPayloadFromExnResponse(response, appID, "deleteCluster");
        return payload.asBoolean();
    }

    // ----------------------------------------
    // Other messages

    /**
     * Broadcast an application's state.  Messages are of the following form:
     *
     * <pre>{@code
     * {
     *   "when": "2024-04-17T07:54:00.169580700Z",
     *   "state": "RUNNING"
     * }
     * }</pre>
     *
     * Possible values for the {@code state} field are in the enumeration
     * {@link NebulousApp#State}.  Note that the application id is transmitted
     * in the message property {@code application}.
     *
     * @param appID the application id.
     * @param state the state of the application.
     */
    public void sendAppStatus(String appID, NebulousApp.State state) {
        Map<String, Object> msg = Map.of("state", state.toString());
        // The documented "when": field is added by the middleware since we do
        // not ask for raw messages via the third optional parameter.
        appStatusPublisher.send(msg, appID);
    }

}
