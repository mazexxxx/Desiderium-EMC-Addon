package com.desiderium.emc;

import com.simibubi.create.content.contraptions.processing.ProcessingOutput;
import com.simibubi.create.content.contraptions.processing.ProcessingRecipe;
import java.util.List;
import moze_intel.projecte.api.mapper.collector.IMappingCollector;
import moze_intel.projecte.api.mapper.recipe.INSSFakeGroupManager;
import moze_intel.projecte.api.mapper.recipe.RecipeTypeMapper;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import moze_intel.projecte.emc.mappers.recipe.BaseRecipeTypeMapper;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

@RecipeTypeMapper(priority = 1000, requiredMods = {"create"})
public final class CreateRecipeTypeMapper extends BaseRecipeTypeMapper {

    @RecipeTypeMapper.Instance
    public static final CreateRecipeTypeMapper INSTANCE =
            new CreateRecipeTypeMapper();

    private CreateRecipeTypeMapper() {
    }

    @Override
    public String getName() {
        return "DesideriumCreateRecipeMapper";
    }

    @Override
    public String getDescription() {
        return "Maps safe single-output Create processing recipes";
    }

    @Override
    public boolean canHandle(RecipeType<?> type) {
        return true;
    }

    @Override
    public boolean handleRecipe(
            IMappingCollector<NormalizedSimpleStack, Long> mapper,
            Recipe<?> recipe,
            INSSFakeGroupManager fakeGroupManager) {

        if (!(recipe instanceof ProcessingRecipe<?> createRecipe)) {
            return false;
        }

        // Не рассчитываем рецепты с жидкостями в первой версии.
        if (!createRecipe.getFluidIngredients().isEmpty()
                || !createRecipe.getFluidResults().isEmpty()) {
            return true;
        }

        List<ProcessingOutput> outputs = createRecipe.getRollableResults();

        // Несколько результатов могут привести к завышению EMC.
        if (outputs.size() != 1) {
            return true;
        }

        ProcessingOutput output = outputs.get(0);

        // Учитываем только гарантированный, непустой результат.
        if (Float.compare(output.getChance(), 1.0F) != 0
                || output.getStack().isEmpty()) {
            return true;
        }

        return super.handleRecipe(mapper, recipe, fakeGroupManager);
    }
}
