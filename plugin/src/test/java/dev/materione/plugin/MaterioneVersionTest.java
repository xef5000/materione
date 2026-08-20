package dev.materione.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The generated version constant is wired from Gradle (single source). */
class MaterioneVersionTest {

    @Test
    void versionIsGradleInjected() {
        assertThat(MaterioneVersion.VERSION).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
    }
}
