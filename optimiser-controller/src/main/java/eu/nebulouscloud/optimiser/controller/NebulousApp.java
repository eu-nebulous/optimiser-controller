package eu.nebulouscloud.optimiser.controller;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal representation of a NebulOus app.
 */
public class NebulousApp {
    private static final Logger log = LoggerFactory.getLogger(NebulousApp.class);

    /** Location of the kubevela yaml file in the app creation message (String) */
    private static final JsonPointer kubevela_path = JsonPointer.compile("/kubevela/original");

    /** Location of the variables (optimizable locations) of the kubevela file
     * in the app creation message. (Array of objects) */
    private static final JsonPointer variables_path = JsonPointer.compile("/kubevela/variables");

    /** Location of the UUID in the app creation message (String) */
    private static final JsonPointer uuid_path = JsonPointer.compile("/application/uuid");

    /** The global app registry. */
    // (Putting this here until we find a better place.)
    private static final Map<String, NebulousApp> apps = new ConcurrentHashMap<String, NebulousApp>();

    /** The YAML converter */
    // Note that instantiating this is apparently expensive, so we do it only once
    private static final ObjectMapper yaml_mapper = new ObjectMapper(new YAMLFactory());

    /**
     * Add a new application object to the registry.
     *
     * @param app a fresh NebulousApp instance.  It is an error if the
     *  registry already contains an app with the same uuid.
     */
    public static synchronized void add(NebulousApp app) {
        String uuid = app.getUUID();
        apps.put(uuid, app);
        log.info("Added app {}", uuid);
    }

    /**
     * Lookup the application object with the given uuid.
     *
     * @param uuid the app's UUID
     * @return the application object, or null if not found
     */
    public static synchronized NebulousApp get(String uuid) {
        return apps.get(uuid);
    }

    /**
     * Remove the application object with the given uuid.
     *
     * @param uuid the app object's UUID
     * @return the removed app object
     */
    public static synchronized NebulousApp remove(String uuid) {
        NebulousApp app = apps.remove(uuid);
        if (app != null) {
            log.info("Removed app {}", uuid);
        } else {
            log.error("Trying to remove unknown app with uuid {}", uuid);
        }
        return app;
    }

    /**
     * Return all currently registered apps.
     *
     * @return a collection of all apps
     */
    public static synchronized Collection<NebulousApp> values() {
        return apps.values();
    }

    private String uuid;
    private JsonNode original_app_message;
    private ObjectNode original_kubevela;
    private ArrayNode parameters;

    /**
     * Creates a NebulousApp object.
     *
     * @param app_message The whole app creation message (JSON)
     * @param kubevela A parsed representation of the deployable KubeVela App model (YAML)
     * @param parameters A parameter mapping as a sequence of JSON objects (JSON)
     */
    // Note that example KubeVela and parameter files can be found at
    // optimiser-controller/src/test/resources/
    public NebulousApp(JsonNode app_message, ObjectNode kubevela, ArrayNode parameters) {
        this.original_app_message = app_message;
        this.original_kubevela = kubevela;
        this.parameters = parameters;
        this.uuid = app_message.at(uuid_path).textValue();
    }

    /**
     * Create a NebulousApp object given an app creation message parsed into JSON.
     *
     * @param app_message the app creation message, including valid KubeVela YAML et al
     * @return a NebulousApp object, or null if `app_message` could not be parsed
     */
    public static NebulousApp newFromAppMessage(JsonNode app_message) {
        try {
            String kubevela_string = app_message.at(kubevela_path).textValue();
            JsonNode parameters = app_message.at(variables_path);
            if (kubevela_string == null || !parameters.isArray()) {
                log.error("Could not find kubevela or parameters in app creation message.");
                return null;
            } else {
                return new NebulousApp(app_message,
                    (ObjectNode)yaml_mapper.readTree(kubevela_string),
                    (ArrayNode)parameters);
            }
        } catch (Exception e) {
            log.error("Could not read app creation message: ", e);
            return null;
        }
    }

    /**
     * The UUID of the app.  This is the UUID that identifies a specific
     * application's ActiveMQ messages.
     *
     * @return the UUID of the app
     */
    public String getUUID() {
        return uuid;
    }

    /**
     * Check that all parameters have a name, type and path, and that the
     * target path can be found in the original KubeVela file.
     *
     * @return true if all requirements hold, false otherwise
     */
    public boolean validatePaths() {
        for (final Object p : parameters) {
            ObjectNode param = (ObjectNode) p;
            String param_name = param.get("key").textValue();
            if (param_name == null || param_name.equals("")) return false;
            String param_type = param.get("type").textValue();
            if (param_type == null || param_type.equals("")) return false;
            // TODO: also validate types, upper and lower bounds, etc.
            String target_path = param.get("path").textValue();
            if (target_path == null || target_path.equals("")) return false;
            JsonNode target = findPathInKubevela(target_path);
            if (target == null) return false; // must exist
        }
        return true;
    }

    /**
     * Return the location of a path in the application's KubeVela model.
     *
     * @param path the path to the requested node, in yq notation (see <a
     *  href="https://mikefarah.gitbook.io/yq/">https://mikefarah.gitbook.io/yq/</a>)
     * @return the node identified by the given path, or null if the path
     * cannot be followed
     */
    private JsonNode findPathInKubevela(String path) {
        // rewrite ".spec.components[3].properties.edge.cpu" (yq path as
        // delivered in the parameter file) into
        // "spec.components.[3].properties.edge.cpu" (note we omit the leading
        // dot) so we can follow the path just by splitting at '.'.
        String normalizedQuery = path.substring(1).replaceAll("(\\[\\d+\\])", ".$1");
        String[] keysAndIndices = normalizedQuery.split("\\.");
        JsonNode currentNode = original_kubevela;
        for (String keyOrIndex : keysAndIndices) {
            boolean isIndex = keyOrIndex.matches("^\\[(\\d+)\\]$");
            if (isIndex) {
                if (!currentNode.isArray()) return null;
                int index = Integer.parseInt(keyOrIndex.substring(1, keyOrIndex.length() - 1));
                currentNode = currentNode.get(index);
                if (currentNode == null) return null;
            } else {
                if (!currentNode.isObject()) return null;
                currentNode = currentNode.get(keyOrIndex);
                if (currentNode == null) return null;
            }
        }
        return currentNode;
    }

    

    /**
     * Print AMPL code for the app, based on the parameter definition(s).
     */
    public void printAMPL() {
        for (final JsonNode p : parameters) {
            ObjectNode param = (ObjectNode) p;
            String param_name = param.get("key").textValue();
            String param_type = param.get("type").textValue();
            ObjectNode value = (ObjectNode)param.get("value");
            if (param_type.equals("float")) {
                System.out.format("var %s", param_name);
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    if (lower.isDouble()) {
                        System.out.format (" >= %s", lower.doubleValue());
                        separator = ", ";
                    }
                    if (upper.isDouble()) {
                        System.out.format("%s<= %s", separator, upper.doubleValue());
                    }
                }
                System.out.println(";");
            } else if (param_type.equals("int")) {
                System.out.format("var %s integer", param_name);
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    if (lower.isLong()) {
                        System.out.format (" >= %s", lower.longValue());
                        separator = ", ";
                    }
                    if (upper.isLong()) {
                        System.out.format("%s<= %s", separator, upper.longValue());
                    }
                }
                System.out.println(";");
            } else if (param_type.equals("string")) {
                System.out.println("# TODO not sure how to specify a string variable");
                System.out.format("var %s symbolic;%n");
            } else if (param_type.equals("array")) {
                System.out.format("# TODO generate entries for map '%s'%n", param_name);
            }
        }
    }

}
