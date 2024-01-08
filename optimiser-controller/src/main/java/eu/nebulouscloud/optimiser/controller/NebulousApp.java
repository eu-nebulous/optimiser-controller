package eu.nebulouscloud.optimiser.controller;

import com.amihaiemil.eoyaml.*;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal representation of a NebulOus app.
 */
public class NebulousApp {
    private static final Logger log = LoggerFactory.getLogger(NebulousApp.class);

    /**
     * Location of the kubevela yaml file in the app creation message.  Should
     * point to a string.
     */
    private static final JSONPointer kubevela_path = new JSONPointer("/kubevela/original");

    /**
     * Location of the modifiable locations of the kubevela file in the app
     * creation message.  Should point to an array of objects.
     */
    private static final JSONPointer variables_path = new JSONPointer("/kubevela/variables");

    private static final JSONPointer uuid_path = new JSONPointer("/application/uuid");

    /** The global app registry. */
    // (Putting this here until we find a better place.)
    private static final Map<String, NebulousApp> apps = new ConcurrentHashMap<String, NebulousApp>();

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
    private JSONObject original_app_message;
    private YamlMapping original_kubevela;
    private JSONArray parameters;

    /**
     * Creates a NebulousApp object.
     *
     * @param kubevela A parsed representation of the deployable KubeVela App model
     * @param parameters A parameter mapping as a sequence of JSON objects.
     */
    // Note that example KubeVela and parameter files can be found at
    // optimiser-controller/src/test/resources/
    public NebulousApp(JSONObject app_message, YamlMapping kubevela, JSONArray parameters) {
        this.original_app_message = app_message;
        this.original_kubevela = kubevela;
        this.parameters = parameters;
        this.uuid = (String)uuid_path.queryFrom(app_message);
    }

    /**
     * Create a NebulousApp object given an app creation message parsed into JSON.
     *
     * @param app_message the app creation message, including valid KubeVela YAML et al
     * @return a NebulousApp object, or null if `app_message` could not be parsed
     */
    public static NebulousApp newFromAppMessage(JSONObject app_message) {
        try {
            String kubevela_string = (String)kubevela_path.queryFrom(app_message);
            JSONArray parameters = (JSONArray)variables_path.queryFrom(app_message);
            return new NebulousApp(app_message,
                Yaml.createYamlInput(kubevela_string).readYamlMapping(),
                parameters);
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
            JSONObject param = (JSONObject) p;
            String param_name = param.optString("key");
            if (param_name.equals("")) return false;
            String param_type = param.optString("type");
            if (param_type.equals("")) return false;
            // TODO: also validate types, upper and lower bounds, etc.
            String target_path = param.optString("path");
            if (target_path.equals("")) return false;
            YamlNode target = findPathInKubevela(target_path);
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
    private YamlNode findPathInKubevela(String path) {
        // rewrite ".spec.components[3].properties.edge.cpu" (yq path as
        // delivered in the parameter file) into
        // "spec.components.[3].properties.edge.cpu" (note we omit the leading
        // dot) so we can follow the path just by splitting at '.'.
        String normalizedQuery = path.substring(1).replaceAll("(\\[\\d+\\])", ".$1");
        String[] keysAndIndices = normalizedQuery.split("\\.");
        YamlNode currentNode = original_kubevela;
        for (String keyOrIndex : keysAndIndices) {
            boolean isIndex = keyOrIndex.matches("^\\[(\\d+)\\]$");
            if (isIndex) {
                if (currentNode.type() != Node.SEQUENCE) return null;
                int index = Integer.parseInt(keyOrIndex.substring(1, keyOrIndex.length() - 1));
                YamlSequence seq = currentNode.asSequence();
                if (index < 0 || index > seq.size()) return null;
                currentNode = seq.yamlNode(index);
            } else {
                if (currentNode.type() != Node.MAPPING) return null;
                YamlMapping map = currentNode.asMapping();
                currentNode = map.value(keyOrIndex);
                if (currentNode == null) return null;
            }
        }
        return currentNode;
    }

    /**
     * Print AMPL code for the app, based on the parameter definition(s).
     */
    public void printAMPL() {
        for (final Object p : parameters) {
            JSONObject param = (JSONObject) p;
            String param_name = param.getString("key");
            String param_type = param.getString("type");
            JSONObject value = param.optJSONObject("value");
            if (param_type.equals("float")) {
                System.out.format("var %s", param_name);
                if (value != null) {
                    String separator = "";
                    double lower = value.optDouble("lower_bound");
                    double upper = value.optDouble("upper_bound");
                    if (!Double.isNaN(lower)) {
                        System.out.format (" >= %s", lower);
                        separator = ", ";
                    }
                    if (!Double.isNaN(upper)) {
                        System.out.format("%s<= %s", separator, upper);
                    }
                }
                System.out.println(";");
            } else if (param_type.equals("int")) {
                System.out.format("var %s integer", param_name);
                if (value != null) {
                    String separator = "";
                    Integer lower = value.optIntegerObject("lower_bound", null);
                    Integer upper = value.optIntegerObject("upper_bound", null);
                    if (lower != null) {
                        System.out.format (" >= %s", lower);
                        separator = ", ";
                    }
                    if (upper != null) {
                        System.out.format("%s<= %s", separator, upper);
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
