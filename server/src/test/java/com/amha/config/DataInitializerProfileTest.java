package com.amha.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

class DataInitializerProfileTest {

    @Test
    void demoSeedRunsOnlyUnderExplicitDemoProfile() {
        Profile profile = DataInitializer.class.getAnnotation(Profile.class);

        assertNotNull(profile, "Demo seed must not be an unconditional application runner");
        assertArrayEquals(new String[]{"demo & !prod"}, profile.value());
    }

    @Test
    void demoSeedRunnerMustKeepInitializationInsideOneTransaction() throws Exception {
        assertNotNull(
                DataInitializer.class.getMethod("run", String[].class)
                        .getAnnotation(Transactional.class),
                "Concurrent replicas must not observe an uncommitted partial demo seed"
        );
    }

    @Test
    void demoSeedMustUseAConnectionScopedAdvisoryLock() throws Exception {
        Class<?> lockType = Class.forName("com.amha.config.DemoSeedAdvisoryLock");

        assertNotNull(lockType.getDeclaredMethod("runWithLock", Runnable.class));
    }
}
