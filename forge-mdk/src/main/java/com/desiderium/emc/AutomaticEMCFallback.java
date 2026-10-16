package com.desiderium.emc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moze_intel.projecte.api.ProjectEAPI;
import moze_intel.projecte.api.nss.NSSItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.NonNullList;
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
import java.util.HashSet;
import java.util.Set;

@Mod.EventBusSubscriber(
        modid = "desideriumemc",
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class AutomaticEMCFallback {

    private static final Logger LOGGER =
            LogManager.getLogger("DesideriumEMC");

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

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
        Path file = FMLPaths.CONFIGDIR.get()
                .resolve("ProjectE")
                .resolve("custom_emc.json");

        JsonObject root;

        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(
                        file, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);

                    if (!parsed.isJsonObject()) {
                        LOGGER.error(
                                "ProjectE custom EMC file is not a JSON object; "
                                + "leaving it unchanged."
                        );
                        return;
                    }

                    root = parsed.getAsJsonObject();
                }
            } else {
                root = new JsonObject();
                root.addProperty(
                        "__comment",
                        "Generated fallback EMC entries for Desiderium."
                );
            }
        } catch (Exception exception) {
            LOGGER.error(
                    "Could not read ProjectE custom EMC file. "
                    + "No values were changed.",
                    exception
            );
            return;
        }

        JsonArray entries;

        if (!root.has("entries")) {
            entries = new JsonArray();
            root.add("entries", entries);
        } else if (root.get("entries").isJsonArray()) {
            entries = root.getAsJsonArray("entries");
        } else {
            LOGGER.error(
                    "ProjectE custom EMC entries are not an array; "
                    + "leaving the file unchanged."
            );
            return;
        }

        // Preserve every item already explicitly present in the file.
        Set<String> knownKeys = new HashSet<>();

        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject entry = element.getAsJsonObject();

            if (entry.has("item")
                    && entry.get("item").isJsonPrimitive()) {
                knownKeys.add(entry.get("item").getAsString());
            }
        }

        int added = 0;

        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            if (item == Items.AIR) {
                continue;
            }

            NonNullList<ItemStack> variants = NonNullList.create();

            try {
                item.fillItemCategory(
                        CreativeModeTab.TAB_SEARCH,
                        variants
                );
            } catch (RuntimeException exception) {
                LOGGER.debug(
                        "Could not enumerate variants for {}",
                        ForgeRegistries.ITEMS.getKey(item),
                        exception
                );
            }

            // Include the base item even if it exposes no search-tab variants.
            if (variants.isEmpty()) {
                variants.add(new ItemStack(item));
            }

            for (ItemStack original : variants) {
                if (original.isEmpty()
                        || original.getItem() == Items.AIR) {
                    continue;
                }

                ItemStack stack = original.copy();
                stack.setCount(1);

                // Use ProjectE's own normalized item identifier.
                String key = NSSItem.createItem(stack).json();

                // Do not overwrite existing custom entries.
                if (knownKeys.contains(key)) {
                    continue;
                }

                // Keep any value already calculated by ProjectE.
                if (ProjectEAPI.getEMCProxy().hasValue(stack)) {
                    continue;
                }

                JsonObject entry = new JsonObject();
                entry.addProperty("item", key);
                entry.addProperty("emc", estimateValue(stack));

                entries.add(entry);
                knownKeys.add(key);
                added++;
            }
        }

        if (added == 0) {
            LOGGER.info(
                    "Automatic EMC fallback: no additional values needed."
            );
            return;
        }

        try {
            Files.createDirectories(file.getParent());

            try (Writer writer = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            LOGGER.error(
                    "Could not save fallback EMC values.",
                    exception
            );
            return;
        }

        LOGGER.info(
                "Added {} fallback EMC entries. Reloading server data.",
                added
        );

        // Reload ProjectE's mappings from the updated custom EMC file.
        server.getCommands().performCommand(
                server.createCommandSourceStack().withPermission(4),
                "reload"
        );
    }

    private static long estimateValue(ItemStack stack) {
        long value = switch (stack.getRarity()) {
            case COMMON -> 1L;
            case UNCOMMON -> 8L;
            case RARE -> 64L;
            case EPIC -> 512L;
        };

        // Non-stackable items receive a higher baseline.
        if (stack.getMaxStackSize() <= 1) {
            value *= 4L;
        } else if (stack.getMaxStackSize() <= 16) {
            value *= 2L;
        }

        // Durable items receive a modest additional value.
        int durability = stack.getMaxDamage();

        if (durability > 0) {
            value += Math.max(1L, durability / 10L);
        }

        return Math.max(1L, value);
    }
}
