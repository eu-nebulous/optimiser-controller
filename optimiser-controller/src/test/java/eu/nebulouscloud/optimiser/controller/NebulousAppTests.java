package eu.nebulouscloud.optimiser.controller;

import org.junit.jupiter.api.Test;
import org.ow2.proactive.sal.model.Requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import eu.nebulouscloud.optimiser.kubevela.KubevelaAnalyzer;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NebulousAppTests {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ObjectMapper yaml_mapper = new ObjectMapper(new YAMLFactory());

    static Path getResourcePath(String name) throws URISyntaxException {
        URL resourceUrl = NebulousAppTests.class.getClassLoader().getResource(name);
        return Paths.get(resourceUrl.toURI());
    }

    static NebulousApp appFromTestFile(String filename) throws IOException, URISyntaxException {
        String app_message_string = Files.readString(getResourcePath(filename),
            StandardCharsets.UTF_8);
        JsonNode msg = mapper.readTree(app_message_string);
        NebulousApp result = NebulousApp.newFromAppMessage(msg, null);
        NebulousApps.add(result);
        return result;
    }

    @Test
    void readValidAppCreationMessage() throws URISyntaxException, IOException {
        NebulousApp app = appFromTestFile("app-creation-message-mercabana.json");
        NebulousApp app2 = appFromTestFile("app-creation-message-complex.json");
        NebulousApp app3 = appFromTestFile("task-787/app-message-with-var-constraint.json");
        assertNotNull(app);
        assertNotNull(app2);
        assertNotNull(app3);
        assertTrue(app.validatePaths());
        assertTrue(app2.validatePaths());
        assertTrue(app3.validatePaths());
    }

    @Test
    void readInvalidAppCreationMessage() throws IOException, URISyntaxException {
        NebulousApp app = appFromTestFile("app-message-invalid-path.json");
        assertNotNull(app);
        assertFalse(app.validatePaths());
    }

    @Test
    void readMultipleAppCreationMessages() throws IOException, URISyntaxException {
        NebulousApps.clear();   // We are not restarted between tests, so app
                                // objects can accumulate; reset state
        NebulousApp app = appFromTestFile("app-creation-message-mercabana.json");
        NebulousApp app2 = appFromTestFile("app-message-2.json");
        assertEquals(2, NebulousApps.values().size(),
            "Expected 2 app objects but got " + NebulousApps.values().size());
    }

    @Test
    void replaceValueInKubevela() throws IOException, URISyntaxException {
        NebulousApp app = appFromTestFile("app-creation-message-complex.json");
        String solution_string = Files.readString(getResourcePath("sample-solution-complex.json"),
            StandardCharsets.UTF_8);
        JsonNode solutions = mapper.readTree(solution_string);
        ObjectNode replacements = solutions.withObject("VariableValues");
        ObjectNode kubevela1 = app.rewriteKubevelaWithSolution(replacements);
        // We deserialize and serialize, just for good measure
        String kubevela_str = yaml_mapper.writeValueAsString(kubevela1);
        JsonNode kubevela = yaml_mapper.readTree(kubevela_str);
        JsonNode replicas = kubevela.at("/spec/components/0/traits/0/properties/replicas");
        assertEquals("8", replicas.asText());
        JsonNode component = kubevela.at("/spec/components/0");
        JsonNode cpu = component.at("/properties/requests/cpu");
        JsonNode memory = component.at("/properties/requests/memory");
        assertEquals("3.5", cpu.asText());
        assertEquals("4096Mi", memory.asText());
        assertEquals(4096, KubevelaAnalyzer.getMemoryRequirement(component, "(component 0)"));
    }

    @Test
    void bug106() throws IOException, URISyntaxException {
        // https://github.com/eu-nebulous/optimiser-controller/issues/106
        NebulousApp app = appFromTestFile("bug-106/app-creation-message.json");
        String solution_string = Files.readString(getResourcePath("bug-106/solver-message.json"),
            StandardCharsets.UTF_8);
        JsonNode solutions = mapper.readTree(solution_string);
        ObjectNode replacements = solutions.withObject("VariableValues");
        ObjectNode kubevela1 = app.rewriteKubevelaWithSolution(replacements);
        // We deserialize and serialize, just for good measure
        String kubevela_str = yaml_mapper.writeValueAsString(kubevela1);
        JsonNode kubevela = yaml_mapper.readTree(kubevela_str);
        JsonNode c = kubevela.at("/spec/components/5");
        JsonNode memory = kubevela.at("/spec/components/5/properties/memory");
        assertEquals("8.0Gi", memory.asText());
        assertEquals(8192, KubevelaAnalyzer.getMemoryRequirement(c, "(component 5)"));
    }



    @Test
    void calculateNodeRequirementsSize() throws IOException, URISyntaxException {
        String kubevela_str = Files.readString(getResourcePath("vela-deployment-v2.yml"),
            StandardCharsets.UTF_8);
        JsonNode kubevela = yaml_mapper.readTree(kubevela_str);
        Map<String, List<Requirement>> requirements = KubevelaAnalyzer.getBoundedRequirements(kubevela);
        // We could compare the requirements with what is contained in
        // KubeVela, or compare keys with component names, but this would
        // essentially duplicate the method code--so we just make sure the
        // method runs without error for well-formed KubeVela and returns
        // one requirement for each component.
        assertEquals(kubevela.withArray("/spec/components").size(), requirements.size());
    }

    @Test
    void calculateServerlessRequirementsSize() throws IOException, URISyntaxException {
        JsonNode kubevela = KubevelaAnalyzer.parseKubevela(Files.readString(getResourcePath("serverless-deployment.yaml"), StandardCharsets.UTF_8));
        Map<String, List<Requirement>> requirements = KubevelaAnalyzer.getBoundedRequirements(kubevela);
        // We have one serverless component, so we need n-1 VMs
        assertEquals(kubevela.withArray("/spec/components").size() - 1, requirements.size());
        // Check that we detect serverless components
        assertTrue(KubevelaAnalyzer.hasServerlessComponents(kubevela));
        // Check that we actually find the serverless platform we want to deploy on
        assertEquals(List.of("serverless-backend"), KubevelaAnalyzer.findServerlessPlatformNames(kubevela));
    }

    @Test
    void checkInvalidServerlessDeployment() throws JsonProcessingException, IOException, URISyntaxException {
        JsonNode kubevela = KubevelaAnalyzer.parseKubevela(Files.readString(getResourcePath("invalid-serverless-deployment.yaml"), StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> NebulousAppDeployer.createDeploymentKubevela(kubevela));
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

        Map<String, List<Requirement>> requirements = KubevelaAnalyzer.getBoundedRequirements(kubevela1);
        // We could compare the requirements with what is contained in
        // KubeVela, or compare keys with component names, but this would
        // essentially duplicate the method code--so we just make sure the
        // method runs without error for well-formed KubeVela and returns
        // one requirement for each component.
        assertEquals(kubevela1.withArray("/spec/components").size(), requirements.size());
    }

    @Test
    void checkComponentPlacements() throws IOException, URISyntaxException {
        NebulousApp app = appFromTestFile("app-creation-message-mercabana-edge.json");
        JsonNode kubevela = app.getOriginalKubevela();
        assertEquals(NebulousAppDeployer.ComponentLocationType.EDGE_ONLY, NebulousAppDeployer.getComponentLocation(kubevela.at("/spec/components/0"))); // explicitly specified EDGE
        assertEquals(NebulousAppDeployer.ComponentLocationType.CLOUD_ONLY, NebulousAppDeployer.getComponentLocation(kubevela.at("/spec/components/1"))); // explicitly specified CLOUD
        assertEquals(NebulousAppDeployer.ComponentLocationType.EDGE_AND_CLOUD, NebulousAppDeployer.getComponentLocation(kubevela.at("/spec/components/2"))); // explicity specified ANY
        assertEquals(NebulousAppDeployer.ComponentLocationType.EDGE_AND_CLOUD, NebulousAppDeployer.getComponentLocation(kubevela.at("/spec/components/3"))); // default unspecified
    }

    @Test
    void parseInvalidCPUValue() throws IOException, URISyntaxException {
        // Check we handle "4.0" for CPU
        NebulousApp app = appFromTestFile("bug-101-string.json");
        assertNotNull(app.calculateAMPLMessage());
        // Check we handle 4.0 for CPU
        app = appFromTestFile("bug-101-float.json");
        assertNotNull(app.calculateAMPLMessage());
        // Check we handle 4 for CPU
        app = appFromTestFile("bug-101-int.json");
        assertNotNull(app.calculateAMPLMessage());
    }

}
