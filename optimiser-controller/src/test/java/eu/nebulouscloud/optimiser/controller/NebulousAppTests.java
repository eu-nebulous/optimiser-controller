package eu.nebulouscloud.optimiser.controller;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NebulousAppTests {

    private ObjectMapper mapper = new ObjectMapper();

    private Path getResourcePath(String name) throws URISyntaxException {
        URL resourceUrl = getClass().getClassLoader().getResource(name);
        return Paths.get(resourceUrl.toURI());
    }

    private NebulousApp appFromTestFile(String filename) throws IOException, URISyntaxException {
        String app_message_string = Files.readString(getResourcePath(filename),
            StandardCharsets.UTF_8);
        JsonNode msg = mapper.readTree(app_message_string);
        return NebulousApp.newFromAppMessage(msg);
    }

    @Test
    void readValidAppCreationMessage() throws URISyntaxException, IOException {
        NebulousApp app = appFromTestFile("vela-deployment-app-message.json");
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
        NebulousApp app = appFromTestFile("vela-deployment-app-message.json");
        NebulousApps.add(app);
        NebulousApp app2 = appFromTestFile("app-message-2.json");
        NebulousApps.add(app2);
        assertTrue(NebulousApps.values().size() == 2);
    }

    @Test
    void replaceValueInKubevela() throws IOException, URISyntaxException {
        NebulousApp app = appFromTestFile("vela-deployment-app-message.json");
        String solution_string = Files.readString(getResourcePath("vela-deployment-sample-solution.json"),
            StandardCharsets.UTF_8);
        JsonNode solutions = mapper.readTree(solution_string);
        ObjectNode replacements = solutions.withObject("VariableValues");
        ObjectNode kubevela = app.rewriteKubevela(replacements);
        assertTrue(kubevela.at("/spec/components/3/properties/edge/cpu").asText().equals("2.7"));
        assertTrue(kubevela.at("/spec/components/3/properties/edge/memory").asText().equals("1024Mi"));
    }
}
