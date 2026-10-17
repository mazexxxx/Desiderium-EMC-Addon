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
                continue;
            }

            // One-time migration for old addon versions: those entries used only
            // the exact rarity/stack-size/durability formula below. Restrict this
            // to modded items and create a backup before touching the file.
            ItemStack stack = stacksByKey.get(key);
            if (stack != null && isModdedItem(stack)
                    && entry.has("emc") && entry.get("emc").isJsonPrimitive()) {
                try {
                    if (entry.get("emc").getAsLong() == legacyEstimateValue(stack)) {
                        generatedKeys.add(key);
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

        Map<String, Long> estimatedValues = estimateFromRecipes(server, stacksByKey, knownValues, generatedKeys);
        int recipeDerived = estimatedValues.size();

        // Use a stronger, transparent fallback only for items no usable recipe
        // can price. Recipe-derived values always take priority over heuristics.
        int heuristicValues = 0;
        for (Map.Entry<String, ItemStack> itemEntry : stacksByKey.entrySet()) {
            String key = itemEntry.getKey();
            ItemStack stack = itemEntry.getValue();
            if (stack.isEmpty() || stack.getItem() == Items.AIR
                    || (!generatedKeys.contains(key) && ProjectEAPI.getEMCProxy().hasValue(stack))) {
                continue;
            }
            if (!estimatedValues.containsKey(key)) {
                estimatedValues.put(key, estimateValue(stack));
                heuristicValues++;
            }
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
            Map<String, ItemStack> stacksByKey,
            Map<String, Long> knownValues,
            Set<String> generatedKeys) {
        Map<String, Long> prices = new HashMap<>(knownValues);
        Map<String, Long> estimates = new HashMap<>();
        Collection<Recipe<?>> recipes = server.getRecipeManager().getRecipes();
        boolean changed;
        int passes = 0;

        do {
            changed = false;
            passes++;
            for (Recipe<?> recipe : recipes) {
                try {
                    ItemStack output = recipe.getResultItem();
                    if (output.isEmpty()) {
                        continue;
                    }
                    ItemStack singleOutput = output.copy();
                    singleOutput.setCount(1);
                    String outputKey = NSSItem.createItem(singleOutput).json();
                    if (prices.containsKey(outputKey) && !generatedKeys.contains(outputKey)) {
                        continue;
                    }

                    List<Ingredient> ingredients = recipe.getIngredients();
                    if (ingredients.isEmpty()) {
                        continue;
                    }
                    long total = 0L;
                    boolean resolvable = true;
                    for (Ingredient ingredient : ingredients) {
                        ItemStack[] options = ingredient.getItems();
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
                                if (existing > 0) {
                                    known = existing;
                                    prices.put(ingredientKey, existing);
                                }
                            }
                            if (known != null && known > 0) {
                                cheapest = Math.min(cheapest, known);
                            }
                        }
                        if (cheapest == Long.MAX_VALUE) {
                            resolvable = false;
                            break;
                        }
                        total = safeAdd(total, cheapest);
                    }
                    if (!resolvable || total <= 0) {
                        continue;
                    }
                    long unitValue = Math.max(1L, (total + output.getCount() - 1L) / output.getCount());
                    Long previous = estimates.get(outputKey);
                    if (previous == null || unitValue < previous) {
                        estimates.put(outputKey, unitValue);
                        prices.put(outputKey, unitValue);
                        changed = true;
                    }
                } catch (RuntimeException | LinkageError exception) {
                    LOGGER.debug("Skipping recipe {} during fallback EMC estimation", recipe.getId(), exception);
                }
            }
        } while (changed && passes < 32);

        // Return only values that were missing or owned by an older addon run.
        estimates.entrySet().removeIf(entry ->
                knownValues.containsKey(entry.getKey()) && !generatedKeys.contains(entry.getKey()));
        return estimates;
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
