package com.amha.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.junit.jupiter.api.Assertions.*;

class DataInitializerProfileTest {

    @Test
    void demoSeedRunsOnlyUnderExplicitDemoProfile() {
        Profile profile = DataInitializer.class.getAnnotation(Profile.class);

        assertNotNull(profile, "Demo seed must not be an unconditional application runner");
        assertArrayEquals(new String[]{"demo & !prod"}, profile.value());
    }
}
