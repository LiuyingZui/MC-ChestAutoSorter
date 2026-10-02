package com.chestautosorter.gametest;

import com.chestautosorter.CAS;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Consumer;

/**
 * Registers this mod's gametests for 26.3.
 *
 * <p>26.3 removed the {@code @GameTest}/{@code @GameTestHolder} annotations. Tests are now entries in
 * the {@code minecraft:test_instance} registry, created on the mod bus via
 * {@link RegisterGameTestsEvent}.
 *
 * <p>{@code TEST_INSTANCE_TYPE} holds two entries, {@code minecraft:block_based} and
 * {@code minecraft:function}, and is frozen during {@code BuiltInRegistries} static initialisation.
 * NeoForge does re-open the built-in registries for {@link RegisterEvent} afterwards
 * ({@code GameData.unfreezeData}), so a custom type may well be registrable on that path — that has
 * not been tried here. The vanilla {@code minecraft:function} type is used instead because it needs
 * no extra registration, and because the instance must be encodable: a client packs this registry on
 * join. Test bodies therefore live in {@code minecraft:test_function} and each instance is a real
 * {@link FunctionGameTestInstance}.
 *
 * <p>Note that {@code FunctionGameTestInstance.CODEC} cannot be borrowed for a custom subclass — its
 * encoder casts to {@code FunctionGameTestInstance}, and the function key has no getter.
 */
@EventBusSubscriber(modid = CAS.MOD_ID)
public final class CasGameTests {

    private CasGameTests() {}

    /**
     * Test bodies. {@code TEST_FUNCTION} is frozen with the other built-in registries and only
     * re-opened during {@link RegisterEvent}, so the entries are declared here and resolved then.
     */
    static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, CAS.MOD_ID);

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> ALWAYS_PASS =
            FUNCTIONS.register("always_pass", () -> GameTestHelper::succeed);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> MIXED_CHESTS =
            FUNCTIONS.register("mixed_chests_are_redistributed_by_category",
                    () -> SortGameTests::mixedChestsAreRedistributedByCategory);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DOUBLE_CHEST =
            FUNCTIONS.register("double_chest_is_one_container", () -> SortGameTests::doubleChestIsOneContainer);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CONSERVES_COMPONENTS =
            FUNCTIONS.register("commit_conserves_components", () -> SortGameTests::commitConservesComponents);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REJECTS_CHANGED =
            FUNCTIONS.register("commit_rejects_when_inventory_changed",
                    () -> SortGameTests::commitRejectsWhenInventoryChanged);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> ROLLBACK_PARTIAL =
            FUNCTIONS.register("commit_rolls_back_on_partial_write_failure",
                    () -> SortGameTests::commitRollsBackOnPartialWriteFailure);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> MIXED_DISALLOWED =
            FUNCTIONS.register("mixed_disallowed_cancels_and_leaves_chests_untouched",
                    () -> SortGameTests::mixedDisallowedCancelsAndLeavesChestsUntouched);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> ROLLBACK_READBACK =
            FUNCTIONS.register("commit_rolls_back_on_readback_mismatch",
                    () -> SortGameTests::commitRollsBackOnReadbackMismatch);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> ROLLBACK_FAILS =
            FUNCTIONS.register("commit_reports_failure_when_rollback_also_fails",
                    () -> SortGameTests::commitReportsFailureWhenRollbackAlsoFails);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PREVIEW_NO_WRITES =
            FUNCTIONS.register("preview_performs_no_writes", () -> SortGameTests::previewPerformsNoWrites);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> RECOVERY_ROUND_TRIP =
            FUNCTIONS.register("recovery_log_round_trips", () -> SortGameTests::recoveryLogRoundTrips);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> RUNTIME_STACK_LIMIT =
            FUNCTIONS.register("runtime_stack_limit_splits_without_loss",
                    () -> SortGameTests::runtimeStackLimitSplitsWithoutLoss);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> RANGE_RULE =
            FUNCTIONS.register("range_rule_uses_eye_to_logical_bounds",
                    () -> SortGameTests::range_rule_uses_eye_to_logical_bounds);

    /** Binds the deferred test functions to the mod bus; called from the mod constructor. */
    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
    }

    /**
     * Declares one test instance per registered function.
     *
     * <p>Each instance is a genuine {@link FunctionGameTestInstance}, so its codec is the registered
     * {@code minecraft:function} type and registry synchronisation can name the type. All tests share
     * the vanilla {@code minecraft:empty} structure in this mod's default environment.
     */
    @SubscribeEvent
    public static void onRegisterGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(CAS.MOD_ID, "default"),
                new TestEnvironmentDefinition.AllOf(List.of()));

        for (DeferredHolder<Consumer<GameTestHelper>, ? extends Consumer<GameTestHelper>> function : FUNCTIONS.getEntries()) {
            ResourceKey<Consumer<GameTestHelper>> key = function.getKey();
            TestData<Holder<TestEnvironmentDefinition<?>>> data =
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 200, 20, true);
            event.registerTest(key.identifier(), new FunctionGameTestInstance(key, data));
        }
    }
}
