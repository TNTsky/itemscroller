package fi.dy.masa.itemscroller.recipes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import javax.annotation.Nonnull;
import fi.dy.masa.itemscroller.ItemScroller;
import fi.dy.masa.itemscroller.Reference;
import fi.dy.masa.itemscroller.util.Constants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ContainerScreen;
import net.minecraft.client.options.ServerEntry;
import net.minecraft.container.Slot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.integrated.IntegratedServer;

public class RecipeStorage
{
    private final RecipePattern[] recipes;
    private final int recipeCount;
    private final boolean global;
    private int selected;
    private boolean dirty;

    public RecipeStorage(int recipeCount, boolean global)
    {
        this.recipes = new RecipePattern[recipeCount];
        this.recipeCount = recipeCount;
        this.global = global;
        this.initRecipes();
    }

    private void initRecipes()
    {
        for (int i = 0; i < this.recipes.length; i++)
        {
            this.recipes[i] = new RecipePattern();
        }
    }

    public int getSelection()
    {
        return this.selected;
    }

    public void changeSelectedRecipe(int index)
    {
        if (index >= 0 && index < this.recipes.length)
        {
            this.selected = index;
            this.dirty = true;
        }
    }

    public void scrollSelection(boolean forward)
    {
        this.changeSelectedRecipe(this.selected + (forward ? 1 : -1));
    }

    public int getRecipeCount()
    {
        return this.recipeCount;
    }

    /**
     * Returns the recipe for the given index.
     * If the index is invalid, then the first recipe is returned, instead of null.
     */
    @Nonnull
    public RecipePattern getRecipe(int index)
    {
        if (index >= 0 && index < this.recipes.length)
        {
            return this.recipes[index];
        }

        return this.recipes[0];
    }

    @Nonnull
    public RecipePattern getSelectedRecipe()
    {
        return this.getRecipe(this.getSelection());
    }

    public void storeCraftingRecipeToCurrentSelection(Slot slot, ContainerScreen<?> gui, boolean clearIfEmpty)
    {
        this.storeCraftingRecipe(this.getSelection(), slot, gui, clearIfEmpty);
    }

    public void storeCraftingRecipe(int index, Slot slot, ContainerScreen<?> gui, boolean clearIfEmpty)
    {
        this.getRecipe(index).storeCraftingRecipe(slot, gui, clearIfEmpty);
        this.dirty = true;
    }

    public void clearRecipe(int index)
    {
        this.getRecipe(index).clearRecipe();
        this.dirty = true;
    }

    private void readFromNBT(CompoundTag nbt)
    {
        if (nbt == null || nbt.containsKey("Recipes", Constants.NBT.TAG_LIST) == false)
        {
            return;
        }

        for (int i = 0; i < this.recipes.length; i++)
        {
            this.recipes[i].clearRecipe();
        }

        ListTag tagList = nbt.getList("Recipes", Constants.NBT.TAG_COMPOUND);
        int count = tagList.size();

        for (int i = 0; i < count; i++)
        {
            CompoundTag tag = tagList.getCompoundTag(i);

            int index = tag.getByte("RecipeIndex");

            if (index >= 0 && index < this.recipes.length)
            {
                this.recipes[index].readFromNBT(tag);
            }
        }

        this.changeSelectedRecipe(nbt.getByte("Selected"));
    }

    private CompoundTag writeToNBT(@Nonnull CompoundTag nbt)
    {
        ListTag tagRecipes = new ListTag();

        for (int i = 0; i < this.recipes.length; i++)
        {
            if (this.recipes[i].isValid())
            {
                CompoundTag tag = new CompoundTag();
                tag.putByte("RecipeIndex", (byte) i);
                this.recipes[i].writeToNBT(tag);
                tagRecipes.add(tag);
            }
        }

        nbt.put("Recipes", tagRecipes);
        nbt.putByte("Selected", (byte) this.selected);

        return nbt;
    }

    private String getFileName()
    {
        String name = "recipes.nbt";

        if (this.global == false)
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc.isIntegratedServerRunning())
            {
                IntegratedServer server = mc.getServer();

                if (server != null)
                {
                    name = "recipes_" + server.getLevelName() + ".nbt";
                }
            }
            else
            {
                ServerEntry server = mc.getCurrentServerEntry();

                if (server != null)
                {
                    name = "recipes_" + server.address.replace(':', '_') + ".nbt";
                }
            }
        }

        return name;
    }

    private File getSaveDir()
    {
        return new File(MinecraftClient.getInstance().runDirectory, Reference.MOD_ID);
    }

    public void readFromDisk()
    {
        try
        {
            File saveDir = this.getSaveDir();

            if (saveDir != null)
            {
                File file = new File(saveDir, this.getFileName());

                if (file.exists() && file.isFile() && file.canRead())
                {
                    FileInputStream is = new FileInputStream(file);
                    this.readFromNBT(NbtIo.readCompressed(is));
                    is.close();
                    //ItemScroller.logger.info("Read recipes from file '{}'", file.getPath());
                }
            }
        }
        catch (Exception e)
        {
            ItemScroller.logger.warn("Failed to read recipes from file", e);
        }
    }

    public void writeToDisk()
    {
        if (this.dirty)
        {
            try
            {
                File saveDir = this.getSaveDir();

                if (saveDir == null)
                {
                    return;
                }

                if (saveDir.exists() == false)
                {
                    if (saveDir.mkdirs() == false)
                    {
                        ItemScroller.logger.warn("Failed to create the recipe storage directory '{}'", saveDir.getPath());
                        return;
                    }
                }

                File fileTmp  = new File(saveDir, this.getFileName() + ".tmp");
                File fileReal = new File(saveDir, this.getFileName());
                FileOutputStream os = new FileOutputStream(fileTmp);
                NbtIo.writeCompressed(this.writeToNBT(new CompoundTag()), os);
                os.close();

                if (fileReal.exists())
                {
                    fileReal.delete();
                }

                fileTmp.renameTo(fileReal);
                this.dirty = false;
            }
            catch (Exception e)
            {
                ItemScroller.logger.warn("Failed to write recipes to file!", e);
            }
        }
    }
}
