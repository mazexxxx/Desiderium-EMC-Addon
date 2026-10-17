package com.desiderium.emc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moze_intel.projecte.api.ProjectEAPI;
import moze_intel.projecte.api.nss.NSSItem;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(
        modid = "desideriumemc",
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class AutomaticEMCFallback {

    private static final Logger LOGGER = LogManager.getLogger("DesideriumEMC");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String GENERATED_MARKER = "desiderium_generated";

    private AutomaticEMCFallback() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!ModList.get().isLoaded("projecte")) {
            return;
        }
        MinecraftServer server = event.getServer();
        server.execute(() -> fillMissingValues(server));
    }

    private static void fillMissingValues(MinecraftServer server) {
        Path file = FMLPaths.CONFIGDIR.get().resolve("ProjectE").resolve("custom_emc.json");
        JsonObject root;

        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (!parsed.isJsonObject()) {
                        LOGGER.error("ProjectE custom EMC file is not a JSON object; leaving it unchanged.");
                        return;
                    }
                    root = parsed.getAsJsonObject();
                }
            } else {
                root = new JsonObject();
                root.addProperty("__comment", "ProjectE custom EMC entries, including Desiderium-generated fallback values.");
            }
        } catch (Exception exception) {
            LOGGER.error("Could not read ProjectE custom EMC file. No values were changed.", exception);
            return;
        }

        JsonArray entries;
        if (!root.has("entries")) {
            entries = new JsonArray();
            root.add("entries", entries);
        } else if (root.get("entries").isJsonArray()) {
            entries = root.getAsJsonArray("entries");
        } else {
            LOGGER.error("ProjectE custom EMC entries are not an array; leaving the file unchanged.");
            return;
        }

        // Enumerate item variants once, so we can safely migrate entries written by
        // older addon versions (which did not mark generated entries).
        Map<String, ItemStack> stacksByKey = collectItemStacks();
        Set<String> generatedKeys = new HashSet<>();
        Set<String> legacyGeneratedKeys = new HashSet<>();
        Map<String, Long> previousGeneratedValues = new HashMap<>();
        int generatedEntryCount = 0;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("item") || !entry.get("item").isJsonPrimitive()) {
                continue;
            }
            String key = entry.get("item").getAsString();
            if (entry.has(GENERATED_MARKER) && entry.get(GENERATED_MARKER).getAsBoolean()) {
                generatedKeys.add(key);
                generatedEntryCount++;
                if (entry.has("emc") && entry.get("emc").isJsonPrimitive()) {
                    try {
                        previousGeneratedValues.put(key, entry.get("emc").getAsLong());
                    } catch (RuntimeException ignored) {
                        // Invalid values are repaired by the next generated write.
                    }
                }
                continue;
            }

            // One-time migration for old addon versions: those entries used only
            // the exact rarity/stack-size/durability formula below. Restrict this
            // to modded items and create a backup before touching the file.
            ItemStack stack = stacksByKey.get(key);
            if (stack != null && isModdedItem(stack)
                    && entry.has("emc") && entry.get("emc").isJsonPrimitive()) {
                try {
                    long oldValue = entry.get("emc").getAsLong();
                    if (oldValue == legacyEstimateValue(stack)) {
                        generatedKeys.add(key);
                        legacyGeneratedKeys.add(key);
                        previousGeneratedValues.put(key, oldValue);
                        generatedEntryCount++;
                    }
                } catch (RuntimeException ignored) {
                    // Malformed user-authored entries are preserved.
                }
            }
        }

        if (!generatedKeys.isEmpty()) {
            Path backup = file.resolveSibling("custom_emc.json.before_desiderium_migration.bak");
            try {
                Files.createDirectories(file.getParent());
                if (!Files.exists(backup) && Files.exists(file)) {
                    Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
                    LOGGER.warn("Backed up custom EMC configuration to {}", backup);
                }
            } catch (IOException exception) {
                LOGGER.error("Could not create a backup of custom_emc.json; refusing to migrate existing entries.", exception);
                return;
            }
            JsonArray retainedEntries = new JsonArray();
            for (JsonElement element : entries) {
                boolean remove = false;
                if (element.isJsonObject()) {
                    JsonObject entry = element.getAsJsonObject();
                    if (entry.has("item") && entry.get("item").isJsonPrimitive()) {
                        remove = generatedKeys.contains(entry.get("item").getAsString());
                    }
                }
                if (!remove) {
                    retainedEntries.add(element);
                }
            }
            root.add("entries", retainedEntries);
            entries = retainedEntries;
        }

        // Start from ProjectE's existing values, but ignore values generated by
        // our previous versions so they cannot feed themselves into new estimates.
        Map<String, Long> knownValues = new HashMap<>();
        for (Map.Entry<String, ItemStack> itemEntry : stacksByKey.entrySet()) {
            String key = itemEntry.getKey();
            ItemStack stack = itemEntry.getValue();
            if (generatedKeys.contains(key) || !ProjectEAPI.getEMCProxy().hasValue(stack)) {
                continue;
            }
            long value = ProjectEAPI.getEMCProxy().getValue(stack);
            if (value > 0) {
                knownValues.put(key, value);
            }
        }

        // Seed every missing item with a provisional fallback. This lets recipe
        // chains propagate through items that ProjectE itself does not price.
        Set<String> missingKeys = new HashSet<>(generatedKeys);
        Map<String, Long> fallbackValues = new HashMap<>();
        for (Map.Entry<String, ItemStack> itemEntry : stacksByKey.entrySet()) {
            String key = itemEntry.getKey();
            if (!knownValues.containsKey(key)) {
                missingKeys.add(key);
                fallbackValues.put(key, estimateValue(itemEntry.getValue()));
            }
        }

        Map<String, Long> estimatedValues = estimateFromRecipes(
                server, knownValues, missingKeys, fallbackValues, generatedKeys);
        int recipeDerived = estimatedValues.size();

        // Items with no usable item-input recipe keep the heuristic fallback.
        int heuristicValues = 0;
        for (Map.Entry<String, ItemStack> itemEntry : stacksByKey.entrySet()) {
            String key = itemEntry.getKey();
            ItemStack stack = itemEntry.getValue();
            if (stack.isEmpty() || stack.getItem() == Items.AIR || !missingKeys.contains(key)) {
                continue;
            }
            if (!estimatedValues.containsKey(key)) {
                estimatedValues.put(key, fallbackValues.getOrDefault(key, estimateValue(stack)));
                heuristicValues++;
            }
        }

        // Avoid rewriting the file and running /reload on every server start
        // when all generated entries are already present with the same values.
        boolean generatedValuesUnchanged = !generatedKeys.isEmpty()
                && legacyGeneratedKeys.isEmpty()
                && generatedEntryCount == generatedKeys.size()
                && generatedKeys.equals(estimatedValues.keySet())
                && previousGeneratedValues.size() == estimatedValues.size();
        if (generatedValuesUnchanged) {
            for (Map.Entry<String, Long> estimate : estimatedValues.entrySet()) {
                Long previous = previousGeneratedValues.get(estimate.getKey());
                if (previous == null || previous.longValue() != estimate.getValue()) {
                    generatedValuesUnchanged = false;
                    break;
                }
            }
        }
        if (generatedValuesUnchanged) {
            LOGGER.info("Automatic EMC fallback: {} generated EMC values are unchanged; skipping file write and reload.",
                    estimatedValues.size());
            return;
        }

        if (estimatedValues.isEmpty() && generatedKeys.isEmpty()) {
            LOGGER.info("Automatic EMC fallback: no additional values needed.");
            return;
        }

        int added = 0;
        for (Map.Entry<String, Long> estimate : estimatedValues.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", estimate.getKey());
            entry.addProperty("emc", Math.max(1L, estimate.getValue()));
            entry.addProperty(GENERATED_MARKER, true);
            entries.add(entry);
            added++;
        }

        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save fallback EMC values.", exception);
            return;
        }

        LOGGER.info("Wrote {} generated EMC entries ({} recipe-derived, {} heuristic). Reloading server data.",
                added, recipeDerived, heuristicValues);
        server.getCommands().performCommand(
                server.createCommandSourceStack().withPermission(4),
                "reload"
        );
    }

    private static Map<String, ItemStack> collectItemStacks() {
        Map<String, ItemStack> stacks = new HashMap<>();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            if (item == Items.AIR) {
                continue;
            }
            NonNullList<ItemStack> variants = NonNullList.create();
            try {
                item.fillItemCategory(CreativeModeTab.TAB_SEARCH, variants);
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.debug("Could not enumerate variants for {}", ForgeRegistries.ITEMS.getKey(item), exception);
            }
            if (variants.isEmpty()) {
                variants.add(new ItemStack(item));
            }
            for (ItemStack original : variants) {
                if (original.isEmpty() || original.getItem() == Items.AIR) {
                    continue;
                }
                ItemStack stack = original.copy();
                stack.setCount(1);
                try {
                    stacks.putIfAbsent(NSSItem.createItem(stack).json(), stack);
                } catch (RuntimeException | LinkageError exception) {
                    LOGGER.debug("Could not normalize item {}", ForgeRegistries.ITEMS.getKey(item), exception);
                }
            }
        }
        return stacks;
    }

    private static Map<String, Long> estimateFromRecipes(
            MinecraftServer server,
            Map<String, Long> knownValues,
            Set<String> missingKeys,
            Map<String, Long> fallbackValues,
            Set<String> generatedKeys) {
        // Known ProjectE values are fixed. Missing items start with heuristics,
        // then recipe results replace those provisional seeds where possible.
        Map<String, Long> prices = new HashMap<>(knownValues);
        prices.putAll(fallbackValues);
        Set<String> recipeDerivedKeys = new HashSet<>();
        Map<String, Long> lowestCandidateSeen = new HashMap<>();
        Collection<Recipe<?>> recipes = server.getRecipeManager().getRecipes();

        // Some modded recipes require fluids in addition to item ingredients.
        // Do not pretend those costs are zero when estimating item EMC.
        Set<ResourceLocation> recipesWithFluidComponents = new HashSet<>();
        for (Recipe<?> recipe : recipes) {
            if (hasNonEmptyOptionalComponent(recipe, "getFluidIngredients")
                    || hasNonEmptyOptionalComponent(recipe, "getFluidResults")) {
                recipesWithFluidComponents.add(recipe.getId());
            }
        }

        boolean converged = false;
        int passes = 0;

        // Values can depend on recipes several steps away. Re-evaluate until the
        // prices stabilize; the cap also protects against cyclic/recursive recipes.
        for (int pass = 1; pass <= 64; pass++) {
            passes = pass;
            Map<String, Long> bestCandidates = new HashMap<>();
            boolean addedSeed = false;

            for (Recipe<?> recipe : recipes) {
                if (recipesWithFluidComponents.contains(recipe.getId())) {
                    continue;
                }
                try {
                    ItemStack output = recipe.getResultItem();
                    if (output.isEmpty()) {
                        continue;
                    }

                    ItemStack singleOutput = output.copy();
                    singleOutput.setCount(1);
                    String outputKey = NSSItem.createItem(singleOutput).json();

                    if (!missingKeys.contains(outputKey)) {
                        Long fixed = knownValues.get(outputKey);
                        if (fixed != null && !generatedKeys.contains(outputKey)) {
                            continue;
                        }

                        // Some recipe outputs may not have appeared in the creative
                        // inventory enumeration; seed them safely if they have no
                        // existing ProjectE price.
                        if (!generatedKeys.contains(outputKey)
                                && ProjectEAPI.getEMCProxy().hasValue(singleOutput)) {
                            long existing = ProjectEAPI.getEMCProxy().getValue(singleOutput);
                            if (existing > 0L) {
                                knownValues.put(outputKey, existing);
                                Long previous = prices.put(outputKey, existing);
                                if (previous == null || previous.longValue() != existing) {
                                    addedSeed = true;
                                }
                                continue;
                            }
                        }
                        missingKeys.add(outputKey);
                        long fallback = estimateValue(singleOutput);
                        fallbackValues.putIfAbsent(outputKey, fallback);
                        if (!prices.containsKey(outputKey)) {
                            prices.put(outputKey, fallback);
                            addedSeed = true;
                        }
                    } else if (!prices.containsKey(outputKey)) {
                        long fallback = estimateValue(singleOutput);
                        fallbackValues.putIfAbsent(outputKey, fallback);
                        prices.put(outputKey, fallback);
                        addedSeed = true;
                    }

                    List<Ingredient> ingredients = recipe.getIngredients();
                    if (ingredients.isEmpty()) {
                        continue;
                    }

                    long total = 0L;
                    boolean resolvable = true;
                    boolean hasRealIngredient = false;

                    for (Ingredient ingredient : ingredients) {
                        ItemStack[] options = ingredient.getItems();

                        // Shaped recipes contain empty ingredients for unused grid
                        // slots. They are not ingredients and must not invalidate
                        // the entire recipe.
                        if (options.length == 0) {
                            continue;
                        }
                        hasRealIngredient = true;

                        long cheapest = Long.MAX_VALUE;
                        for (ItemStack option : options) {
                            if (option.isEmpty()) {
                                continue;
                            }

                            ItemStack one = option.copy();
                            one.setCount(1);
                            String ingredientKey = NSSItem.createItem(one).json();
                            Long known = prices.get(ingredientKey);

                            if (known == null && !generatedKeys.contains(ingredientKey)
                                    && ProjectEAPI.getEMCProxy().hasValue(one)) {
                                long existing = ProjectEAPI.getEMCProxy().getValue(one);
                                if (existing > 0L) {
                                    known = existing;
                                    knownValues.put(ingredientKey, existing);
                                    Long previous = prices.put(ingredientKey, existing);
                                    if (previous == null || previous.longValue() != existing) {
                                        addedSeed = true;
                                    }
                                }
                            }

                            if (known == null || known <= 0L) {
                                long fallback = estimateValue(one);
                                fallbackValues.putIfAbsent(ingredientKey, fallback);
                                missingKeys.add(ingredientKey);
                                prices.put(ingredientKey, fallback);
                                known = fallback;
                                addedSeed = true;
                            }

                            if (known > 0L) {
                                cheapest = Math.min(cheapest, known);
                            }
                        }

                        if (cheapest == Long.MAX_VALUE) {
                            resolvable = false;
                            break;
                        }
                        total = safeAdd(total, cheapest);
                    }

                    if (!resolvable || !hasRealIngredient || total <= 0L) {
                        continue;
                    }

                    long unitValue = Math.max(1L, safeCeilingDivide(total, output.getCount()));
                    bestCandidates.merge(outputKey, unitValue, (left, right) -> Math.min(left, right));
                } catch (RuntimeException | LinkageError exception) {
                    LOGGER.debug("Skipping recipe {} during fallback EMC estimation", recipe.getId(), exception);
                }
            }

            Map<String, Long> nextPrices = new HashMap<>(prices);
            boolean changed = addedSeed;
            for (Map.Entry<String, Long> candidate : bestCandidates.entrySet()) {
                String key = candidate.getKey();
                long value = candidate.getValue();
                Long previous = prices.get(key);
                if (previous == null || previous.longValue() != value) {
                    changed = true;
                }
                nextPrices.put(key, value);
                recipeDerivedKeys.add(key);
                lowestCandidateSeen.merge(key, value, (left, right) -> Math.min(left, right));
            }

            prices = nextPrices;
            if (!changed) {
                converged = true;
                break;
            }
        }

        if (!converged) {
            LOGGER.warn("Recipe EMC estimation reached its {}-pass safety limit; using the lowest candidate observed for cyclic chains.", passes);
            // Bidirectional/cyclic conversion recipes can oscillate. Choosing the
            // lowest candidate observed per output is conservative and deterministic.
            for (String key : recipeDerivedKeys) {
                Long lower = lowestCandidateSeen.get(key);
                if (lower != null) {
                    prices.put(key, lower);
                }
            }
        }

        Map<String, Long> estimates = new HashMap<>();
        for (String key : recipeDerivedKeys) {
            Long value = prices.get(key);
            if (value != null && value > 0L) {
                estimates.put(key, value);
            }
        }
        return estimates;
    }

    private static boolean hasNonEmptyOptionalComponent(Recipe<?> recipe, String getterName) {
        try {
            Method getter = recipe.getClass().getMethod(getterName);
            Object result = getter.invoke(recipe);
            if (result instanceof Collection<?> values) {
                return !values.isEmpty();
            }
            if (result instanceof Iterable<?> values) {
                return values.iterator().hasNext();
            }
            // Unknown non-null return types are treated conservatively. This
            // prevents silently ignoring a special input when a mod exposes it
            // through a custom collection type.
            return result != null;
        } catch (NoSuchMethodException ignored) {
            // Ordinary Minecraft recipes do not expose fluid input/output APIs.
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LOGGER.debug("Could not inspect optional component {} for recipe {}",
                    getterName, recipe.getId(), exception);
            // If inspection fails, do not use this recipe to assign an EMC price.
            return true;
        }
    }

    private static long safeCeilingDivide(long value, int divisor) {
        if (value <= 0L) {
            return 1L;
        }
        int safeDivisor = Math.max(1, divisor);
        long quotient = value / safeDivisor;
        return value % safeDivisor == 0L ? quotient : quotient + 1L;
    }

    private static long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static boolean isModdedItem(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && !"minecraft".equals(id.getNamespace());
    }

    private static long legacyEstimateValue(ItemStack stack) {
        long value = switch (stack.getRarity()) {
            case COMMON -> 1L;
            case UNCOMMON -> 8L;
            case RARE -> 64L;
            case EPIC -> 512L;
            default -> 512L;
        };
        if (stack.getMaxStackSize() <= 1) {
            value *= 4L;
        } else if (stack.getMaxStackSize() <= 16) {
            value *= 2L;
        }
        int durability = stack.getMaxDamage();
        if (durability > 0) {
            value += Math.max(1L, durability / 10L);
        }
        return Math.max(1L, value);
    }

    private static long estimateValue(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath().toLowerCase(java.util.Locale.ROOT);
        long value = legacyEstimateValue(stack);

        // Unknown modded items should not all collapse to EMC 1 merely because
        // their names do not contain familiar material keywords. Recipe-derived
        // values can replace this provisional floor when a usable recipe exists.
        if (id != null && !"minecraft".equals(id.getNamespace())) {
            value = Math.max(value, 16L);
        }

        // Registry names provide a useful broad tier when no recipe can be
        // evaluated (e.g. uncraftable curios, machine blocks, and special items).
        if (containsAny(path, "creative", "infinity", "singularity", "ultimate", "chaos", "draconic")) {
            value = Math.max(value, 1_000_000L);
        } else if (containsAny(path, "nether_star", "netherite", "dragon", "wither", "awakening", "stellar", "neutron")) {
            value = Math.max(value, 16_384L);
        } else if (containsAny(path, "diamond", "emerald", "amethyst", "ruby", "sapphire", "uranium", "platinum", "tungsten", "allthemodium", "vibranium", "unobtainium")) {
            value = Math.max(value, 512L);
        } else if (containsAny(path, "ingot", "gem", "crystal", "pearl", "alloy", "plate", "dust", "fragment", "shard", "ore", "raw_")) {
            value = Math.max(value, 64L);
        } else if (containsAny(path, "machine", "generator", "controller", "capacitor", "cell", "storage", "upgrade")) {
            value = Math.max(value, 128L);
        } else if (containsAny(path, "sword", "pickaxe", "axe", "bow", "staff", "wand", "armor", "helmet", "chestplate", "leggings", "boots")) {
            value = Math.max(value, 32L);
        }
        return Math.max(1L, value);
    }

    private static boolean containsAny(String value, String... parts) {
        for (String part : parts) {
            if (value.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
