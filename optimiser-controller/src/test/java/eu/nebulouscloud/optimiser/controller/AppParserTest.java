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
        NebulousApp app = AppParser.parseAppCreationMessage(msg);
        assertNotNull(app);
        assertTrue(app.validatePaths());
    }
}
