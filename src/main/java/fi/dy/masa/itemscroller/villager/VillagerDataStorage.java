package fi.dy.masa.itemscroller.villager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

import fi.dy.masa.itemscroller.ItemScroller;
import fi.dy.masa.itemscroller.Reference;
import fi.dy.masa.itemscroller.config.Configs;
import fi.dy.masa.itemscroller.util.Constants;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class VillagerDataStorage
{
    private static final VillagerDataStorage INSTANCE = new VillagerDataStorage();

    private final Map<UUID, VillagerData> data = new HashMap<>();
    private final List<TradeType> globalFavorites = new ArrayList<>();
    private UUID lastInteractedUUID;
    private boolean dirty;

    public static VillagerDataStorage getInstance()
    {
        return INSTANCE;
    }

    public void setLastInteractedUUID(UUID uuid)
    {
        this.lastInteractedUUID = uuid;
    }

    @Nullable
    public VillagerData getDataForLastInteractionTarget()
    {
        return this.getDataFor(this.lastInteractedUUID, true);
    }

    public VillagerData getDataFor(@Nullable UUID uuid, boolean create)
    {
        VillagerData data = uuid != null ? this.data.get(uuid) : null;

        if (data == null && uuid != null && create)
        {
            this.setLastInteractedUUID(uuid);
            data = new VillagerData(uuid);
            this.data.put(uuid, data);
            this.dirty = true;
        }

        return data;
    }

    public void setTradeListPosition(int position)
    {
        VillagerData data = this.getDataFor(this.lastInteractedUUID, true);

        if (data != null)
        {
            data.setTradeListPosition(position);
            this.dirty = true;
        }
    }

    public void toggleFavorite(int tradeIndex)
    {
        VillagerData data = this.getDataFor(this.lastInteractedUUID, true);

        if (data != null)
        {
            data.toggleFavorite(tradeIndex);
            this.dirty = true;
        }
    }

    public void toggleGlobalFavorite(TradeOffer trade)
    {
        TradeType type = TradeType.of(trade);

        if (this.globalFavorites.contains(type))
        {
            this.globalFavorites.remove(type);
        }
        else
        {
            this.globalFavorites.add(type);
        }

        this.dirty = true;
    }

    public FavoriteData getFavoritesForCurrentVillager(MerchantScreenHandler handler)
    {
        return this.getFavoritesForCurrentVillager(((IMerchantScreenHandler) handler).getOriginalList());
    }

    public FavoriteData getFavoritesForCurrentVillager(TradeOfferList originalTrades)
    {
        VillagerData data = this.getDataFor(this.lastInteractedUUID, false);
        IntArrayList favorites = data != null ? data.getFavorites() : null;

        if (favorites != null && favorites.isEmpty() == false)
        {
            return new FavoriteData(favorites, false);
        }

        if (Configs.Generic.VILLAGER_TRADE_USE_GLOBAL_FAVORITES.getBooleanValue() && this.lastInteractedUUID != null)
        {
            return new FavoriteData(VillagerUtils.getGlobalFavoritesFor(originalTrades, this.globalFavorites), true);
        }

        return new FavoriteData(IntArrayList.of(), favorites == null);
    }

    private void readFromNBT(NbtCompound nbt)
    {
        if (nbt == null || nbt.contains("VillagerData", Constants.NBT.TAG_LIST) == false)
        {
            return;
        }

        NbtList tagList = nbt.getList("VillagerData", Constants.NBT.TAG_COMPOUND);
        int count = tagList.size();

        for (int i = 0; i < count; i++)
        {
            NbtCompound tag = tagList.getCompound(i);
            VillagerData data = VillagerData.fromNBT(tag);

            if (data != null)
            {
                this.data.put(data.getUUID(), data);
            }
        }

        tagList = nbt.getList("GlobalFavorites", Constants.NBT.TAG_COMPOUND);
        count = tagList.size();

        for (int i = 0; i < count; i++)
        {
            NbtCompound tag = tagList.getCompound(i);
            TradeType type = TradeType.fromTag(tag);

            if (type != null)
            {
                this.globalFavorites.add(type);
            }
        }
    }

    private NbtCompound writeToNBT(@Nonnull NbtCompound nbt)
    {
        NbtList favoriteListData = new NbtList();
        NbtList globalFavoriteData = new NbtList();

        for (VillagerData data : this.data.values())
        {
            favoriteListData.add(data.toNBT());
        }

        for (TradeType type : this.globalFavorites)
        {
            globalFavoriteData.add(type.toTag());
        }

        nbt.put("VillagerData", favoriteListData);
        nbt.put("GlobalFavorites", globalFavoriteData);

        this.dirty = false;

        return nbt;
    }

    private String getFileName()
    {
        String worldName = StringUtils.getWorldOrServerName();

        if (worldName != null)
        {
            return "villager_data_" + worldName + ".nbt";
        }

        return "villager_data.nbt";
    }

    private File getSaveDir()
    {
        return new File(FileUtils.getMinecraftDirectory(), Reference.MOD_ID);
    }

    public void readFromDisk()
    {
        this.data.clear();
        this.globalFavorites.clear();

        try
        {
            File saveDir = this.getSaveDir();
            File file = new File(saveDir, this.getFileName());

            if (file.exists())
            {
                if (file.isFile() && file.canRead())
                {
                    FileInputStream is = new FileInputStream(file);
                    this.readFromNBT(NbtIo.readCompressed(is, NbtSizeTracker.ofUnlimitedBytes()));
                    is.close();
                }
                else
                {
                    ItemScroller.logger.warn("VillagerDataStorage#readFromDisk(): Error reading villager data from file '{}'", file.getPath());
                }
            }
        }
        catch (Exception e)
        {
            ItemScroller.logger.warn("Failed to read villager data from file", e);
        }
    }

    public void writeToDisk()
    {
        if (this.dirty)
        {
            try
            {
                File saveDir = this.getSaveDir();

                if (saveDir.exists() == false && saveDir.mkdirs() == false)
                {
                    ItemScroller.logger.warn("Failed to create the data storage directory '{}'", saveDir.getPath());
                    return;
                }

                File fileTmp  = new File(saveDir, this.getFileName() + ".tmp");
                File fileReal = new File(saveDir, this.getFileName());
                FileOutputStream os = new FileOutputStream(fileTmp);
                NbtIo.writeCompressed(this.writeToNBT(new NbtCompound()), os);
                os.close();

                if (fileReal.exists())
                {
                    if (fileReal.delete() == false)
                    {
                        ItemScroller.logger.warn("VillagerDataStorage#writeToDisk(): failed to delete file {} ", fileReal.getName());
                    }
                }

                if (fileTmp.renameTo(fileReal) == false)
                {
                    ItemScroller.logger.warn("VillagerDataStorage#writeToDisk(): failed to delete file {} ", fileTmp.getName());
                }
                this.dirty = false;

            }
            catch (Exception e)
            {
                ItemScroller.logger.warn("Failed to write villager data to file!", e);
            }
        }
    }
}
