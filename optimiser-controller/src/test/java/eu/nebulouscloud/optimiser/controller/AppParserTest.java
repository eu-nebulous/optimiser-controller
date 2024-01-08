package eu.nebulouscloud.optimiser.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.JSONObject;

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AppParserTest {

    private Path getResourcePath(String name) throws URISyntaxException {
        URL resourceUrl = getClass().getClassLoader().getResource(name);
        return Paths.get(resourceUrl.toURI());
    }

    @Test
    void readValidAppCreationMessage() throws URISyntaxException, IOException {
        String app_message_string = Files.readString(getResourcePath("vela-deployment-app-message.json"),
            StandardCharsets.UTF_8);
        JSONObject msg = new JSONObject(app_message_string);
        NebulousApp app = NebulousApp.newFromAppMessage(msg);
        assertNotNull(app);
        assertTrue(app.validatePaths());
    }

    @Test
    void readInvalidAppCreationMessage() throws IOException, URISyntaxException {
        String app_message_string = Files.readString(getResourcePath("app-message-invalid-path.json"),
            StandardCharsets.UTF_8);
        JSONObject msg = new JSONObject(app_message_string);
        NebulousApp app = NebulousApp.newFromAppMessage(msg);
        assertNotNull(app);
        assertFalse(app.validatePaths());
    }

    @Test
    void readMultipleAppCreationMessages() throws IOException, URISyntaxException {
        String app_message_string = Files.readString(getResourcePath("vela-deployment-app-message.json"),
            StandardCharsets.UTF_8);
        JSONObject msg = new JSONObject(app_message_string);
        String app_message_string_2 = Files.readString(getResourcePath("app-message-2.json"),
            StandardCharsets.UTF_8);
        JSONObject msg2 = new JSONObject(app_message_string_2);
        NebulousApp app = NebulousApp.newFromAppMessage(msg);
        NebulousApp.add(app);
        NebulousApp app2 = NebulousApp.newFromAppMessage(msg2);
        NebulousApp.add(app2);
        assertTrue(NebulousApp.values().size() == 2);
    }
}
