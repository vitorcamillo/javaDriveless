package io.github.selenium.javaDriverless.types;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

final class BrowserFingerprintJsonCodec {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private BrowserFingerprintJsonCodec() {}

    static BrowserFingerprintProfile read(Path path) throws IOException {
        return OBJECT_MAPPER.readValue(path.toFile(), BrowserFingerprintProfile.class);
    }

    static void write(Path path, BrowserFingerprintProfile profile) throws IOException {
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), profile);
    }

    static String writeString(BrowserFingerprintProfile profile) throws IOException {
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
    }

    static BrowserFingerprintProfile copy(BrowserFingerprintProfile profile) {
        return OBJECT_MAPPER.convertValue(profile, BrowserFingerprintProfile.class);
    }
}
