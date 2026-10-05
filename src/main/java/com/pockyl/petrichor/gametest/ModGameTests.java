package com.pockyl.petrichor.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.pockyl.petrichor.Petrichor;

/**
 * In-game tests, run headless by {@code gradlew runGameTestServer}.
 * Tests use the 1x1x1 {@code empty} structure unless they need a prepared one.
 */
@GameTestHolder(Petrichor.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ModGameTests {
    private ModGameTests() {
    }

    @GameTest(template = "empty")
    public static void modLoads(GameTestHelper helper) {
        helper.succeed();
    }
}
