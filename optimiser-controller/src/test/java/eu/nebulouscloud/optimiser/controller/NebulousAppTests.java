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

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        NebulousApp result = NebulousApp.newFromAppMessage(msg, null);
        NebulousApps.add(result);
        return result;
    }

    @Test
    void readValidAppCreationMessage() throws URISyntaxException, IOException {
        NebulousApp app = appFromTestFile("app-creation-message-mercabana.json");
        NebulousApp app2 = appFromTestFile("app-creation-message-complex.json");
        assertNotNull(app);
        assertNotNull(app2);
        assertTrue(app.validatePaths());
        assertTrue(app2.validatePaths());
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
        assertTrue(NebulousApps.values().size() == 2,
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
        assertTrue(replicas.asText().equals("8"));
        JsonNode cpu = kubevela.at("/spec/components/0/properties/requests/cpu");
        JsonNode memory = kubevela.at("/spec/components/0/properties/requests/memory");
        assertTrue(cpu.asText().equals("3.5"));
        assertTrue(memory.asText().equals("4096Mi"));
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
        assertTrue(requirements.size() == kubevela.withArray("/spec/components").size());
    }

    @Test
    void calculateServerlessRequirementsSize() throws IOException, URISyntaxException {
        JsonNode kubevela = KubevelaAnalyzer.parseKubevela(Files.readString(getResourcePath("serverless-deployment.yaml"), StandardCharsets.UTF_8));
        Map<String, List<Requirement>> requirements = KubevelaAnalyzer.getBoundedRequirements(kubevela);
        // We have one serverless component, so we need n-1 VMs
        assertTrue(requirements.size() == kubevela.withArray("/spec/components").size() - 1);
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
        assertTrue(requirements.size() == kubevela1.withArray("/spec/components").size());
    }

    @Test
    void checkComponentPlacements() throws IOException, URISyntaxException {
        NebulousApp app = appFromTestFile("app-creation-message-mercabana-edge.json");
        JsonNode kubevela = app.getOriginalKubevela();
        assertEquals(NebulousAppDeployer.getComponentLocation(kubevela.at("/spec/components/0")), NebulousAppDeployer.ComponentLocationType.EDGE_ONLY); // explicitly specified EDGE
        assertEquals(NebulousAppDeployer.getComponentLocation(kubevela.at("/spec/components/1")), NebulousAppDeployer.ComponentLocationType.CLOUD_ONLY); // explicitly specified CLOUD
        assertEquals(NebulousAppDeployer.getComponentLocation(kubevela.at("/spec/components/2")), NebulousAppDeployer.ComponentLocationType.EDGE_AND_CLOUD); // explicity specified ANY
        assertEquals(NebulousAppDeployer.getComponentLocation(kubevela.at("/spec/components/3")), NebulousAppDeployer.ComponentLocationType.EDGE_AND_CLOUD); // default unspecified
    }

    @Test
    void bug42() throws IOException, URISyntaxException {
        // https://github.com/eu-nebulous/optimiser-controller/issues/42
        NebulousApp app = appFromTestFile("bug-42-app-creation-message.json");
        JsonNode solverMessage = app.calculateAMPLMessage();
        assertEquals(solverMessage.at("/Constants/deployed_memory/Value").asLong(),  8192L);
    }

}
