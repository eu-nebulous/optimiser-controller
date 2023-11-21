package eu.nebulous.optimiser.controller;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class AppParserTest {

    private Path getResourcePath(String name) throws URISyntaxException {
        URL resourceUrl = getClass().getClassLoader().getResource(name);
        return Paths.get(resourceUrl.toURI());
    }

    @Test
    void readValidAppCreationMessage() throws URISyntaxException, IOException {
        String kubevela = Files.readString(getResourcePath("vela-deployment.yaml"),
                StandardCharsets.UTF_8);
        String parameters = Files.readString(getResourcePath("vela-deployment-parameters.yaml"),
                StandardCharsets.UTF_8);
        NebulousApp app = AppParser.parseAppCreationMessage(kubevela, parameters);
        assertNotNull(app);
        assertTrue(app.validateMapping());
    }
}
