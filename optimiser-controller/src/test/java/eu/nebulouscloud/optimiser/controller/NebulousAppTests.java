package eu.nebulouscloud.optimiser.controller;

import org.junit.jupiter.api.Test;
import org.ow2.proactive.sal.model.Requirement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NebulousAppTests {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ObjectMapper yaml_mapper = new ObjectMapper(new YAMLFactory());

    private Path getResourcePath(String name) throws URISyntaxException {
        URL resourceUrl = getClass().getClassLoader().getResource(name);
        return Paths.get(resourceUrl.toURI());
    }

    private NebulousApp appFromTestFile(String filename) throws IOException, URISyntaxException {
        String app_message_string = Files.readString(getResourcePath(filename),
            StandardCharsets.UTF_8);
        JsonNode msg = mapper.readTree(app_message_string);
        return NebulousApp.newFromAppMessage(msg, null);
    }

    @Test
    void readValidAppCreationMessage() throws URISyntaxException, IOException {
        NebulousApp app = appFromTestFile("app-creation-message-mercabana.json");
        assertNotNull(app);
        assertTrue(app.validatePaths());
    }

    @Test
    void readInvalidAppCreationMessage() throws IOException, URISyntaxException {
        NebulousApp app = appFromTestFile("app-message-invalid-path.json");
        assertNotNull(app);
        assertFalse(app.validatePaths());
    }

    @Test
    void readMultipleAppCreationMessages() throws IOException, URISyntaxException {
        NebulousApp app = appFromTestFile("app-creation-message-mercabana.json");
        NebulousApps.add(app);
        NebulousApp app2 = appFromTestFile("app-message-2.json");
        NebulousApps.add(app2);
        assertTrue(NebulousApps.values().size() == 2);
    }

    // @Test
    void replaceValueInKubevela() throws IOException, URISyntaxException {
        // TODO reinstate with mercabana app messge, new sample-solution file
        NebulousApp app = appFromTestFile("vela-deployment-app-message.json");
        String solution_string = Files.readString(getResourcePath("vela-deployment-sample-solution.json"),
            StandardCharsets.UTF_8);
        JsonNode solutions = mapper.readTree(solution_string);
        ObjectNode replacements = solutions.withObject("VariableValues");
        ObjectNode kubevela1 = app.rewriteKubevelaWithSolution(replacements);
        // We deserialize and serialize, just for good measure
        String kubevela_str = yaml_mapper.writeValueAsString(kubevela1);
        JsonNode kubevela = yaml_mapper.readTree(kubevela_str);
        JsonNode cpu = kubevela.at("/spec/components/3/properties/edge/cpu");
        JsonNode memory = kubevela.at("/spec/components/3/properties/edge/memory");
        assertTrue(cpu.asText().equals("2.7"));
        assertTrue(memory.asText().equals("1024"));
    }

    @Test
    void calculateNodeRequirements() throws IOException, URISyntaxException {
        String kubevela_str = Files.readString(getResourcePath("vela-deployment-v2.yml"),
            StandardCharsets.UTF_8);
        JsonNode kubevela = yaml_mapper.readTree(kubevela_str);
        Map<String, List<Requirement>> requirements = NebulousAppDeployer.getSalRequirementsFromKubevela(kubevela);
        // We could compare the requirements with what is contained in
        // KubeVela, or compare keys with component names, but this would
        // essentially duplicate the method code--so we just make sure the
        // method runs without error for well-formed KubeVela and returns
        // one requirement for each component.
        assertTrue(requirements.size() == kubevela.withArray("/spec/components").size());
    }

    // @Test
    void calculateRewrittenNodeRequirements() throws IOException, URISyntaxException {
        // TODO: reinstate with `app-creation-message-mercabana.json` after we
        // define a valid sample-solution file
        NebulousApp app = appFromTestFile("vela-deployment-app-message.json");
        String solution_string = Files.readString(getResourcePath("vela-deployment-sample-solution.json"),
            StandardCharsets.UTF_8);
        JsonNode solutions = mapper.readTree(solution_string);
        ObjectNode replacements = solutions.withObject("VariableValues");
        ObjectNode kubevela1 = app.rewriteKubevelaWithSolution(replacements);

        Map<String, List<Requirement>> requirements = NebulousAppDeployer.getSalRequirementsFromKubevela(kubevela1);
        // We could compare the requirements with what is contained in
        // KubeVela, or compare keys with component names, but this would
        // essentially duplicate the method code--so we just make sure the
        // method runs without error for well-formed KubeVela and returns
        // one requirement for each component.
        assertTrue(requirements.size() == kubevela1.withArray("/spec/components").size());
    }

}
