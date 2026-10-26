package dev.toka.rewards;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/** Button labels carry no colour code, except a leading red one on close/cancel. */
public class ButtonColourTest {
    private static final String[] BUTTON_KEYS = {"ui.claim", "ui.voteButton", "ui.admin.btnSponsor", "ui.admin.btnGrant",
            "ui.admin.btnRetry", "ui.admin.btnReload", "ui.admin.accept", "ui.close", "ui.cancel"};

    @Test
    @SuppressWarnings("unchecked")
    public void buttonLabelsHaveNoColour() throws IOException {
        try (var in = Files.newBufferedReader(Path.of("src", "main", "resources", "lang", "zh-TW.yml"), StandardCharsets.UTF_8)) {
            Object root = new org.yaml.snakeyaml.Yaml().load(in);
            for (String key : BUTTON_KEYS) {
                Object value = root;
                for (String part : key.split("\\.")) value = ((Map<String, Object>) value).get(part);
                String text = String.valueOf(value);
                String rest = text.startsWith("§c") ? text.substring(2) : text;
                assertTrue(key + " = " + text, !rest.contains("§"));
            }
        }
    }
}
