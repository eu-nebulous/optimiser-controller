package eu.nebulous.optimiser.controller;

import com.amihaiemil.eoyaml.*;

/**
 * Internal representation of a NebulOus app.
 */
public class NebulousApp {
    private YamlMapping original_kubevela;
    private YamlMapping parameters;
    
    /**
     * Creates a NebulousApp object.
     *
     * Example KubeVela and parameter files can be found below {@link
     * optimiser-controller/src/test/resources}
     *
     * @param kubevela A parsed representation of the deployable KubeVela App model
     * @param parameters A parameter mapping
     */
    public NebulousApp(YamlMapping kubevela, YamlMapping parameters) {
        this.original_kubevela = kubevela;
        this.parameters = parameters;
    }

    /**
     * Check that the target paths of all parameters can be found in the
     * original KubeVela file.
     *
     * @return true if all parameter paths match, false otherwise
     */
    public boolean validateMapping() {
        YamlMapping params = parameters.yamlMapping("nebulous_metadata").yamlMapping("optimisation_variables");
        for (final YamlNode param : params.keys()) {
            YamlMapping param_data = params.yamlMapping(param);
            String param_name = param.asScalar().value();
            String target_path = param_data.value("target").asScalar().value();
            YamlNode target = findPathInKubevela(target_path);
            if (target == null) return false;
        }
        return true;
    }

    /**
     * Return the location of a path in the application's KubeVela model.
     *
     * @param path the path to the requested node, in {@link
     * https://mikefarah.gitbook.io/yq/ yq} notation
     * @return the node identified by the given path, or null if the path
     * cannot be followed
     */
    private YamlNode findPathInKubevela(String path) {
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
                //  Sadly, YamlSequence has no method `yamlNode` that returns
                // a bare YamlNode instance; this is how its methods for
                // `YamlMapping` etc. are implemented..
                int count = 0;
                for (final YamlNode node : seq.values()) {
                    if (count == index) {
                        currentNode = node;
                        break;
                    }
                    count = count + 1;
                }
            } else {
                if (currentNode.type() != Node.MAPPING) return null;
                YamlMapping map = currentNode.asMapping();
                currentNode = map.value(keyOrIndex);
                if (currentNode == null) return null;
            }
        }
        return currentNode;
    }

}
