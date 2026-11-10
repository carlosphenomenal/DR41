package io.github.carlosphenomenal.dr41.k6;

import io.github.carlosphenomenal.dr41.config.AppProperties;
import io.github.carlosphenomenal.dr41.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class K6RuntimeTest {
    @Test
    void versionComparison() {
        assertTrue(K6Runtime.compare("1.0.0", "0.45.0") > 0);
        assertTrue(K6Runtime.compare("0.44.9", "0.45.0") < 0);
        assertEquals(0, K6Runtime.compare("0.45", "0.45.0"));
    }

    @Test
    void missingBinaryGivesInstallGuidance() {
        var base = AppProperties.defaults();
        var props = new AppProperties(new AppProperties.K6("/definitely/not/here/k6", "0.45.0",
                base.k6().versionCheckTimeout(), base.k6().hardTimeoutGrace(), base.k6().gracefulStop()),
                base.limits(), base.storage(), base.monitoring(), base.safety());
        ApiException ex = assertThrows(ApiException.class, () -> new K6Runtime(props).require());
        assertEquals("K6_NOT_INSTALLED", ex.getCode());
        assertFalse(ex.getDetails().isEmpty());
    }
}
