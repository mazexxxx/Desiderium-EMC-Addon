package com.desiderium.emc;

import moze_intel.projecte.api.mapper.recipe.RecipeTypeMapper;
import moze_intel.projecte.emc.mappers.recipe.BaseRecipeTypeMapper;
import net.minecraft.world.item.crafting.RecipeType;

@RecipeTypeMapper(priority = -1000, requiredMods = {})
public final class GenericRecipeTypeMapper extends BaseRecipeTypeMapper {

    @RecipeTypeMapper.Instance
    public static final GenericRecipeTypeMapper INSTANCE =
            new GenericRecipeTypeMapper();

    private GenericRecipeTypeMapper() {
    }

    @Override
    public String getName() {
        return "DesideriumGenericRecipeMapper";
    }

    @Override
    public String getDescription() {
        return "Fallback EMC mappings for compatible modded recipes";
    }

    @Override
    public boolean canHandle(RecipeType<?> type) {
        return type != RecipeType.CRAFTING
                && type != RecipeType.SMELTING
                && type != RecipeType.BLASTING
                && type != RecipeType.SMOKING
                && type != RecipeType.CAMPFIRE_COOKING
                && type != RecipeType.STONECUTTING;
    }
}
