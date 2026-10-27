package dev.toka.rewards;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.Assert.assertTrue;

/** YAML reads yes, no, on and off as true and false: as a key one of them is never found, so such keys are quoted. */
public class LangKeysTest {
    private static void check(String path, Object node, List<String> bad) {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof Boolean) {
                    bad.add(path + "." + entry.getKey());
                }
                check(path + "." + entry.getKey(), entry.getValue(), bad);
            }
        }
    }

    @Test
    public void noKeyOfALangFileIsABoolean() throws Exception {
        for (String file : List.of("lang/zh-TW.yml", "lang/en-US.yml")) {
            try (InputStream in = LangKeysTest.class.getClassLoader().getResourceAsStream(file)) {
                if (in == null) {
                    continue;
                }
                List<String> bad = new ArrayList<>();
                check("", new Yaml().load(in), bad);
                assertTrue(file + " " + bad, bad.isEmpty());
            }
        }
    }
}
