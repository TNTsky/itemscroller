package fi.dy.masa.itemscroller.util;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import fi.dy.masa.itemscroller.ItemScroller;
import fi.dy.masa.itemscroller.config.Configs;
import fi.dy.masa.itemscroller.config.Hotkeys;
import fi.dy.masa.itemscroller.recipes.CraftingHandler;
import fi.dy.masa.itemscroller.recipes.CraftingHandler.SlotRange;
import fi.dy.masa.itemscroller.recipes.RecipePattern;
import fi.dy.masa.itemscroller.recipes.RecipeStorage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ContainerScreen;
import net.minecraft.client.gui.container.VillagerScreen;
import net.minecraft.client.gui.ingame.CreativePlayerInventoryScreen;
import net.minecraft.client.gui.ingame.PlayerInventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.container.Container;
import net.minecraft.container.Slot;
import net.minecraft.container.SlotActionType;
import net.minecraft.container.TradeOutputSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.crafting.CraftingRecipe;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TraderOfferList;
import net.minecraft.world.World;

public class InventoryUtils
{
    private static MoveAction activeMoveAction = MoveAction.NONE;
    private static int lastPosX;
    private static int lastPosY;
    private static int slotNumberLast;
    private static final Set<Integer> DRAGGED_SLOTS = new HashSet<Integer>();
    private static WeakReference<Slot> sourceSlotCandidate = null;
    private static WeakReference<Slot> sourceSlot = null;
    private static ItemStack stackInCursorLast = ItemStack.EMPTY;

    public static void onSlotChangedCraftingGrid(PlayerEntity player,
            CraftingInventory craftMatrix, CraftingResultInventory inventoryCraftResult)
    {
        World world = player.getEntityWorld();

        if (Configs.Generic.CLIENT_CRAFTING_FIX.getBooleanValue() &&
            world.isClient && (world instanceof ClientWorld) && player instanceof ClientPlayerEntity)
        {
            ItemStack stack = ItemStack.EMPTY;
            Optional<CraftingRecipe> optional = ((ClientWorld) world).getRecipeManager().getFirstMatch(RecipeType.CRAFTING, craftMatrix, world);

            if (optional.isPresent())
            {
                CraftingRecipe recipe = optional.get();

                if ((recipe.isIgnoredInRecipeBook() ||
                     world.getGameRules().getBoolean("doLimitedCrafting") == false ||
                     ((ClientPlayerEntity) player).getRecipeBook().contains(recipe))
               )
               {
                   inventoryCraftResult.setLastRecipe(recipe);
                   stack = recipe.craft(craftMatrix);
               }

               inventoryCraftResult.setInvStack(0, stack);
            }
        }
    }

    public static String getStackString(ItemStack stack)
    {
        if (isStackEmpty(stack) == false)
        {
            Identifier rl = Registry.ITEM.getId(stack.getItem());

            return String.format("[%s - display: %s - NBT: %s] (%s)",
                    rl != null ? rl.toString() : "null", stack.getDisplayName().getString(),
                    stack.getTag() != null ? stack.getTag().toString() : "<no NBT>",
                    stack.toString());
        }

        return "<empty>";
    }

    public static void debugPrintSlotInfo(ContainerScreen<? extends Container> gui, Slot slot)
    {
        if (slot == null)
        {
            ItemScroller.logger.info("slot was null");
            return;
        }

        boolean hasSlot = gui.getContainer().slotList.contains(slot);
        Object inv = slot.inventory;
        String stackStr = InventoryUtils.getStackString(slot.getStack());

        ItemScroller.logger.info(String.format("slot: slotNumber: %d, getSlotIndex(): %d, getHasStack(): %s, " +
                "slot class: %s, inv class: %s, Container's slot list has slot: %s, stack: %s, numSlots: %d",
                slot.id, AccessorUtils.getSlotIndex(slot), slot.hasStack(), slot.getClass().getName(),
                inv != null ? inv.getClass().getName() : "<null>", hasSlot ? " true" : "false", stackStr,
                gui.getContainer().slotList.size()));
    }

    private static boolean isValidSlot(Slot slot, ContainerScreen<? extends Container> gui, boolean requireItems)
    {
        Container container = gui.getContainer();

        return container != null && container.slotList != null &&
                slot != null && container.slotList.contains(slot) &&
                (requireItems == false || slot.hasStack()) &&
                Configs.SLOT_BLACKLIST.contains(slot.getClass().getName()) == false;
    }

    public static boolean isCraftingSlot(ContainerScreen<? extends Container> gui, Slot slot)
    {
        return slot != null && CraftingHandler.getCraftingGridSlots(gui, slot) != null;
    }

    /**
     * Checks if there are slots belonging to another inventory on screen above the given slot
     */
    private static boolean inventoryExistsAbove(Slot slot, Container container)
    {
        for (Slot slotTmp : container.slotList)
        {
            if (slotTmp.yPosition < slot.yPosition && areSlotsInSameInventory(slot, slotTmp) == false)
            {
                return true;
            }
        }

        return false;
    }

    public static boolean canShiftPlaceItems(ContainerScreen<? extends Container> gui)
    {
        Slot slot = AccessorUtils.getSlotUnderMouse(gui);
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack stackCursor = mc.player.inventory.getCursorStack();

        // The target slot needs to be an empty, valid slot, and there needs to be items in the cursor
        return slot != null && isStackEmpty(stackCursor) == false && isValidSlot(slot, gui, false) &&
               slot.hasStack() == false && slot.canInsert(stackCursor);
    }

    public static boolean tryMoveItems(ContainerScreen<? extends Container> gui, RecipeStorage recipes, boolean scrollingUp)
    {
        Slot slot = AccessorUtils.getSlotUnderMouse(gui);
        MinecraftClient mc = MinecraftClient.getInstance();

        // We require an empty cursor
        if (slot == null || isStackEmpty(mc.player.inventory.getCursorStack()) == false)
        {
            return false;
        }

        // Villager handling only happens when scrolling over the trade output slot
        boolean villagerHandling = Configs.Toggles.SCROLL_VILLAGER.getBooleanValue() && gui instanceof VillagerScreen && slot instanceof TradeOutputSlot;
        boolean craftingHandling = Configs.Toggles.CRAFTING_FEATURES.getBooleanValue() && isCraftingSlot(gui, slot);
        boolean keyActiveMoveEverything = Hotkeys.MODIFIER_MOVE_EVERYTHING.getKeybind().isKeybindHeld();
        boolean keyActiveMoveMatching = Hotkeys.MODIFIER_MOVE_MATCHING.getKeybind().isKeybindHeld();
        boolean keyActiveMoveStacks = Hotkeys.MODIFIER_MOVE_STACK.getKeybind().isKeybindHeld();
        boolean nonSingleMove = keyActiveMoveEverything || keyActiveMoveMatching || keyActiveMoveStacks;
        boolean moveToOtherInventory = scrollingUp;

        if (Configs.Generic.SLOT_POSITION_AWARE_SCROLL_DIRECTION.getBooleanValue())
        {
            boolean above = inventoryExistsAbove(slot, gui.getContainer());
            // so basically: (above && scrollingUp) || (above == false && scrollingUp == false)
            moveToOtherInventory = (above == scrollingUp);
        }

        if ((Configs.Generic.REVERSE_SCROLL_DIRECTION_SINGLE.getBooleanValue() && nonSingleMove == false) ||
            (Configs.Generic.REVERSE_SCROLL_DIRECTION_STACKS.getBooleanValue() && nonSingleMove))
        {
            moveToOtherInventory = ! moveToOtherInventory;
        }

        // Check that the slot is valid, (don't require items in case of the villager output slot or a crafting slot)
        if (isValidSlot(slot, gui, villagerHandling || craftingHandling ? false : true) == false)
        {
            return false;
        }

        if (craftingHandling)
        {
            return tryMoveItemsCrafting(recipes, slot, gui, moveToOtherInventory, keyActiveMoveStacks, keyActiveMoveEverything);
        }

        if (villagerHandling)
        {
            return tryMoveItemsVillager((VillagerScreen) gui, slot, moveToOtherInventory, keyActiveMoveStacks);
        }

        if ((Configs.Toggles.SCROLL_SINGLE.getBooleanValue() == false && nonSingleMove == false) ||
            (Configs.Toggles.SCROLL_STACKS.getBooleanValue() == false && keyActiveMoveStacks) ||
            (Configs.Toggles.SCROLL_MATCHING.getBooleanValue() == false && keyActiveMoveMatching) ||
            (Configs.Toggles.SCROLL_EVERYTHING.getBooleanValue() == false && keyActiveMoveEverything))
        {
            return false;
        }

        // Move everything
        if (keyActiveMoveEverything)
        {
            tryMoveStacks(slot, gui, false, moveToOtherInventory, false);
        }
        // Move all matching items
        else if (keyActiveMoveMatching)
        {
            tryMoveStacks(slot, gui, true, moveToOtherInventory, false);
            return true;
        }
        // Move one matching stack
        else if (keyActiveMoveStacks)
        {
            tryMoveStacks(slot, gui, true, moveToOtherInventory, true);
        }
        else
        {
            ItemStack stack = slot.getStack();

            // Scrolling items from this slot/inventory into the other inventory
            if (moveToOtherInventory)
            {
                tryMoveSingleItemToOtherInventory(slot, gui);
            }
            // Scrolling items from the other inventory into this slot/inventory
            else if (getStackSize(stack) < slot.getMaxStackAmount(stack))
            {
                tryMoveSingleItemToThisInventory(slot, gui);
            }
        }

        return false;
    }

    public static boolean dragMoveItems(ContainerScreen<? extends Container> gui, MinecraftClient mc, MoveAction action, int mouseX, int mouseY, boolean isClick)
    {
        if (isStackEmpty(mc.player.inventory.getCursorStack()) == false)
        {
            // Updating these here is part of the fix to preventing a drag after shift + place
            lastPosX = mouseX;
            lastPosY = mouseY;
            stopDragging();

            return false;
        }

        boolean cancel = false;

        if (isClick && action != MoveAction.NONE)
        {
            // Reset this or the method call won't do anything...
            slotNumberLast = -1;
            lastPosX = mouseX;
            lastPosY = mouseY;
            activeMoveAction = action;
            cancel = dragMoveFromSlotAtPosition(gui, mouseX, mouseY, action);
        }
        else
        {
            action = activeMoveAction;
        }

        if (activeMoveAction != MoveAction.NONE && cancel == false)
        {
            int distX = mouseX - lastPosX;
            int distY = mouseY - lastPosY;
            int absX = Math.abs(distX);
            int absY = Math.abs(distY);

            if (absX > absY)
            {
                int inc = distX > 0 ? 1 : -1;

                for (int x = lastPosX; ; x += inc)
                {
                    int y = absX != 0 ? lastPosY + ((x - lastPosX) * distY / absX) : mouseY;
                    dragMoveFromSlotAtPosition(gui, x, y, action);

                    if (x == mouseX)
                    {
                        break;
                    }
                }
            }
            else
            {
                int inc = distY > 0 ? 1 : -1;

                for (int y = lastPosY; ; y += inc)
                {
                    int x = absY != 0 ? lastPosX + ((y - lastPosY) * distX / absY) : mouseX;
                    dragMoveFromSlotAtPosition(gui, x, y, action);

                    if (y == mouseY)
                    {
                        break;
                    }
                }
            }
        }

        lastPosX = mouseX;
        lastPosY = mouseY;

        // Always update the slot under the mouse.
        // This should prevent a "double click/move" when shift + left clicking on slots that have more
        // than one stack of items. (the regular slotClick() + a "drag move" from the slot that is under the mouse
        // when the left mouse button is pressed down and this code runs).
        Slot slot = AccessorUtils.getSlotAtPosition(gui, mouseX, mouseY);

        if (slot != null)
        {
            if (gui instanceof CreativePlayerInventoryScreen)
            {
                boolean isPlayerInv = ((CreativePlayerInventoryScreen) gui).method_2469() == ItemGroup.INVENTORY.getIndex();
                int slotNumber = isPlayerInv ? AccessorUtils.getSlotIndex(slot) : slot.id;
                slotNumberLast = slotNumber;
            }
            else
            {
                slotNumberLast = slot.id;
            }
        }
        else
        {
            slotNumberLast = -1;
        }

        return cancel;
    }

    public static void stopDragging()
    {
        activeMoveAction = MoveAction.NONE;
        DRAGGED_SLOTS.clear();
    }

    private static boolean dragMoveFromSlotAtPosition(ContainerScreen<? extends Container> gui, int x, int y, MoveAction action)
    {
        if (gui instanceof CreativePlayerInventoryScreen)
        {
            return dragMoveFromSlotAtPositionCreative(gui, x, y, action);
        }

        Slot slot = AccessorUtils.getSlotAtPosition(gui, x, y);
        MinecraftClient mc = MinecraftClient.getInstance();
        MoveAmount amount = InputUtils.getMoveAmount(action);
        boolean flag = slot != null && isValidSlot(slot, gui, true) && slot.canTakeItems(mc.player);
        //boolean cancel = flag && (amount == MoveAmount.LEAVE_ONE || amount == MoveAmount.MOVE_ONE);

        if (flag && slot.id != slotNumberLast &&
            (amount != MoveAmount.MOVE_ONE || DRAGGED_SLOTS.contains(slot.id) == false))
        {
            switch (action)
            {
                case MOVE_TO_OTHER_MOVE_ONE:
                    tryMoveSingleItemToOtherInventory(slot, gui);
                    break;

                case MOVE_TO_OTHER_LEAVE_ONE:
                    tryMoveAllButOneItemToOtherInventory(slot, gui);
                    break;

                case MOVE_TO_OTHER_STACKS:
                    shiftClickSlot(gui, slot.id);
                    break;

                case MOVE_TO_OTHER_MATCHING:
                    tryMoveStacks(slot, gui, true, true, false);
                    break;

                case DROP_ONE:
                    clickSlot(gui, slot.id, 0, SlotActionType.THROW);
                    break;

                case DROP_LEAVE_ONE:
                    leftClickSlot(gui, slot.id);
                    rightClickSlot(gui, slot.id);
                    dropItemsFromCursor(gui);
                    break;

                case DROP_STACKS:
                    clickSlot(gui, slot.id, 1, SlotActionType.THROW);
                    break;

                case MOVE_DOWN_MOVE_ONE:
                case MOVE_DOWN_LEAVE_ONE:
                case MOVE_DOWN_STACKS:
                case MOVE_DOWN_MATCHING:
                    tryMoveItemsVertically(gui, slot, false, amount);
                    break;

                case MOVE_UP_MOVE_ONE:
                case MOVE_UP_LEAVE_ONE:
                case MOVE_UP_STACKS:
                case MOVE_UP_MATCHING:
                    tryMoveItemsVertically(gui, slot, true, amount);
                    break;

                default:
            }

            DRAGGED_SLOTS.add(slot.id);
        }

        return true;
    }

    private static boolean dragMoveFromSlotAtPositionCreative(ContainerScreen<? extends Container> gui, int x, int y, MoveAction action)
    {
        CreativePlayerInventoryScreen guiCreative = (CreativePlayerInventoryScreen) gui;
        Slot slot = AccessorUtils.getSlotAtPosition(gui, x, y);
        boolean isPlayerInv = guiCreative.method_2469() == ItemGroup.INVENTORY.getIndex();

        // Only allow dragging from the hotbar slots
        if (slot == null || (slot.getClass() != Slot.class && isPlayerInv == false))
        {
            return false;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        MoveAmount amount = InputUtils.getMoveAmount(action);
        boolean flag = slot != null && isValidSlot(slot, gui, true) && slot.canTakeItems(mc.player);
        boolean cancel = flag && (amount == MoveAmount.LEAVE_ONE || amount == MoveAmount.MOVE_ONE);
        // The player inventory tab of the creative inventory uses stupid wrapped
        // slots that all have slotNumber = 0 on the outer instance ;_;
        // However in that case we can use the slotIndex which is easy enough to get.
        int slotNumber = isPlayerInv ? AccessorUtils.getSlotIndex(slot) : slot.id;

        if (flag && slotNumber != slotNumberLast && DRAGGED_SLOTS.contains(slotNumber) == false)
        {
            switch (action)
            {
                case SCROLL_TO_OTHER_MOVE_ONE:
                case MOVE_TO_OTHER_MOVE_ONE:
                    leftClickSlot(guiCreative, slot, slotNumber);
                    rightClickSlot(guiCreative, slot, slotNumber);
                    shiftClickSlot(guiCreative, slot, slotNumber);
                    leftClickSlot(guiCreative, slot, slotNumber);

                    cancel = true;
                    break;

                case MOVE_TO_OTHER_LEAVE_ONE:
                    // Too lazy to try to duplicate the proper code for the weird creative inventory...
                    if (isPlayerInv == false)
                    {
                        leftClickSlot(guiCreative, slot, slotNumber);
                        rightClickSlot(guiCreative, slot, slotNumber);

                        // Delete the rest of the stack by placing it in the first creative "source slot"
                        Slot slotFirst = gui.getContainer().slotList.get(0);
                        leftClickSlot(guiCreative, slotFirst, slotFirst.id);
                    }

                    cancel = true;
                    break;

                case SCROLL_TO_OTHER_STACKS:
                case MOVE_TO_OTHER_STACKS:
                    shiftClickSlot(gui, slot, slotNumber);
                    cancel = true;
                    break;

                case DROP_ONE:
                    clickSlot(gui, slot.id, 0, SlotActionType.THROW);
                    break;

                case DROP_LEAVE_ONE:
                    leftClickSlot(gui, slot.id);
                    rightClickSlot(gui, slot.id);
                    dropItemsFromCursor(gui);
                    break;

                case DROP_STACKS:
                    clickSlot(gui, slot.id, 1, SlotActionType.THROW);
                    cancel = true;
                    break;

                case MOVE_DOWN_MOVE_ONE:
                case MOVE_DOWN_LEAVE_ONE:
                case MOVE_DOWN_STACKS:
                    tryMoveItemsVertically(gui, slot, false, amount);
                    cancel = true;
                    break;

                case MOVE_UP_MOVE_ONE:
                case MOVE_UP_LEAVE_ONE:
                case MOVE_UP_STACKS:
                    tryMoveItemsVertically(gui, slot, true, amount);
                    cancel = true;
                    break;

                default:
            }

            DRAGGED_SLOTS.add(slotNumber);
        }

        return cancel;
    }

    public static void dropStacks(ContainerScreen<? extends Container> gui, ItemStack stackReference, Slot slotReference, boolean sameInventory)
    {
        if (slotReference != null && isStackEmpty(stackReference) == false)
        {
            Container container = gui.getContainer();
            stackReference = stackReference.copy();

            for (Slot slot : container.slotList)
            {
                // If this slot is in the same inventory that the items were picked up to the cursor from
                // and the stack is identical to the one in the cursor, then this stack will get dropped.
                if (areSlotsInSameInventory(slot, slotReference) == sameInventory && areStacksEqual(slot.getStack(), stackReference))
                {
                    // Drop the stack
                    dropStack(gui, slot.id);
                }
            }
        }
    }

    public static boolean shiftDropItems(ContainerScreen<? extends Container> gui)
    {
        ItemStack stackReference = MinecraftClient.getInstance().player.inventory.getCursorStack();

        if (isStackEmpty(stackReference) == false && sourceSlot != null)
        {
            stackReference = stackReference.copy();

            // First drop the existing stack from the cursor
            dropItemsFromCursor(gui);

            dropStacks(gui, stackReference, sourceSlot.get(), true);
            return true;
        }

        return false;
    }

    public static boolean shiftPlaceItems(Slot slot, ContainerScreen<? extends Container> gui)
    {
        // Left click to place the items from the cursor to the slot
        leftClickSlot(gui, slot.id);

        // Ugly fix to prevent accidentally drag-moving the stack from the slot that it was just placed into...
        DRAGGED_SLOTS.add(slot.id);

        tryMoveStacks(slot, gui, true, false, false);

        return true;
    }

    /**
     * Store a reference to the slot when a slot is left or right clicked on.
     * The slot is then later used to determine which inventory an ItemStack was
     * picked up from, if the stack from the cursor is dropped while holding shift.
     */
    public static void storeSourceSlotCandidate(Slot slot, MinecraftClient mc)
    {
        // Left or right mouse button was pressed
        if (slot != null)
        {
            ItemStack stackCursor = mc.player.inventory.getCursorStack();
            ItemStack stack = EMPTY_STACK;

            if (isStackEmpty(stackCursor) == false)
            {
                // Do a cheap copy without NBT data
                stack = new ItemStack(stackCursor.getItem(), getStackSize(stackCursor));
            }

            // Store the candidate
            // NOTE: This method is called BEFORE the stack has been picked up to the cursor!
            // Thus we can't check that there is an item already in the cursor, and that's why this is just a "candidate"
            sourceSlotCandidate = new WeakReference<>(slot);
            stackInCursorLast = stack;
        }
    }

    /**
     * Check if the (previous) mouse event resulted in picking up a new ItemStack to the cursor
     */
    public static void checkForItemPickup(MinecraftClient mc)
    {
        ItemStack stackCursor = mc.player.inventory.getCursorStack();

        // Picked up or swapped items to the cursor, grab a reference to the slot that the items came from
        // Note that we are only checking the item here!
        if (isStackEmpty(stackCursor) == false && stackCursor.isEqualIgnoreTags(stackInCursorLast) == false && sourceSlotCandidate != null)
        {
            sourceSlot = new WeakReference<>(sourceSlotCandidate.get());
        }
    }

    private static boolean tryMoveItemsVillager(VillagerScreen gui, Slot slot, boolean moveToOtherInventory, boolean fullStacks)
    {
        if (fullStacks)
        {
            // Try to fill the merchant's buy slots from the player inventory
            if (moveToOtherInventory == false)
            {
                tryMoveItemsToMerchantBuySlots(gui, true);
            }
            // Move items from sell slot to player inventory
            else if (slot.hasStack())
            {
                tryMoveStacks(slot, gui, true, true, true);
            }
            // Scrolling over an empty output slot, clear the buy slots
            else
            {
                tryMoveStacks(slot, gui, false, true, false);
            }
        }
        else
        {
            // Scrolling items from player inventory into merchant buy slots
            if (moveToOtherInventory == false)
            {
                tryMoveItemsToMerchantBuySlots(gui, false);
            }
            // Scrolling items from this slot/inventory into the other inventory
            else if (slot.hasStack())
            {
                moveOneSetOfItemsFromSlotToPlayerInventory(gui, slot);
            }
        }

        return false;
    }

    private static boolean tryMoveSingleItemToOtherInventory(Slot slot, ContainerScreen<? extends Container> gui)
    {
        ItemStack stackOrig = slot.getStack();
        Container container = gui.getContainer();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (isStackEmpty(mc.player.inventory.getCursorStack()) == false || slot.canTakeItems(mc.player) == false ||
            (getStackSize(stackOrig) > 1 && slot.canInsert(stackOrig) == false))
        {
            return false;
        }

        // Can take all the items to the cursor at once, use a shift-click method to move one item from the slot
        if (getStackSize(stackOrig) <= stackOrig.getMaxAmount())
        {
            return clickSlotsToMoveSingleItemByShiftClick(gui, slot.id);
        }

        ItemStack stack = stackOrig.copy();
        setStackSize(stack, 1);

        ItemStack[] originalStacks = getOriginalStacks(container);

        // Try to move the temporary single-item stack via the shift-click handler method
        slot.setStack(stack);
        container.transferSlot(mc.player, slot.id);

        // Successfully moved the item somewhere, now we want to check where it went
        if (slot.hasStack() == false)
        {
            int targetSlot = getTargetSlot(container, originalStacks);

            // Found where the item went
            if (targetSlot >= 0)
            {
                // Remove the dummy item from the target slot (on the client side)
                container.slotList.get(targetSlot).takeStack(1);

                // Restore the original stack to the slot under the cursor (on the client side)
                restoreOriginalStacks(container, originalStacks);

                // Do the slot clicks to actually move the items (on the server side)
                return clickSlotsToMoveSingleItem(gui, slot.id, targetSlot);
            }
        }

        // Restore the original stack to the slot under the cursor (on the client side)
        slot.setStack(stackOrig);

        return false;
    }

    private static boolean tryMoveAllButOneItemToOtherInventory(Slot slot, ContainerScreen<? extends Container> gui)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        ItemStack stackOrig = slot.getStack().copy();

        if (getStackSize(stackOrig) == 1 || getStackSize(stackOrig) > stackOrig.getMaxAmount() ||
            slot.canTakeItems(player) == false || slot.canInsert(stackOrig) == false)
        {
            return true;
        }

        // Take half of the items from the original slot to the cursor
        rightClickSlot(gui, slot.id);

        ItemStack stackInCursor = player.inventory.getCursorStack();
        if (isStackEmpty(stackInCursor))
        {
            return false;
        }

        int stackInCursorSizeOrig = getStackSize(stackInCursor);
        int tempSlotNum = -1;

        // Find some other slot where to store one of the items temporarily
        for (Slot slotTmp : gui.getContainer().slotList)
        {
            if (slotTmp.id != slot.id &&
                areSlotsInSameInventory(slotTmp, slot, true) &&
                slotTmp.canInsert(stackInCursor) &&
                slotTmp.canTakeItems(player))
            {
                ItemStack stackInSlot = slotTmp.getStack();

                if (isStackEmpty(stackInSlot) || areStacksEqual(stackInSlot, stackInCursor))
                {
                    // Try to put one item into the temporary slot
                    rightClickSlot(gui, slotTmp.id);

                    stackInCursor = player.inventory.getCursorStack();

                    // Successfully stored one item
                    if (isStackEmpty(stackInCursor) || getStackSize(stackInCursor) < stackInCursorSizeOrig)
                    {
                        tempSlotNum = slotTmp.id;
                        break;
                    }
                }
            }
        }

        if (isStackEmpty(player.inventory.getCursorStack()) == false)
        {
            // Return the rest of the items into the original slot
            leftClickSlot(gui, slot.id);
        }

        // Successfully stored one item in a temporary slot
        if (tempSlotNum != -1)
        {
            // Shift click the stack from the original slot
            shiftClickSlot(gui, slot.id);

            // Take half a stack from the temporary slot
            rightClickSlot(gui, tempSlotNum);

            // Return one item into the original slot
            rightClickSlot(gui, slot.id);

            // Return the rest of the items to the temporary slot, if any
            if (isStackEmpty(player.inventory.getCursorStack()) == false)
            {
                leftClickSlot(gui, tempSlotNum);
            }

            return true;
        }
        // No temporary slot found, try to move the stack manually
        else
        {
            boolean treatHotbarAsDifferent = gui.getClass() == PlayerInventoryScreen.class;
            List<Integer> slots = getSlotNumbersOfEmptySlots(gui.getContainer(), slot, false, treatHotbarAsDifferent, false);

            if (slots.isEmpty())
            {
                slots = getSlotNumbersOfMatchingStacks(gui.getContainer(), slot, false, slot.getStack(), true, treatHotbarAsDifferent, false);
            }

            if (slots.isEmpty() == false)
            {
                // Take the stack
                leftClickSlot(gui, slot.id);

                // Return one item
                rightClickSlot(gui, slot.id);

                // Try to place the stack in the cursor to any valid empty or matching slots in a different inventory
                for (int slotNum : slots)
                {
                    Slot slotTmp = gui.getContainer().getSlot(slotNum);
                    stackInCursor = player.inventory.getCursorStack();

                    if (isStackEmpty(stackInCursor))
                    {
                        return true;
                    }

                    if (slotTmp.canInsert(stackInCursor))
                    {
                        leftClickSlot(gui, slotNum);
                    }
                }

                // Items left, return them
                if (isStackEmpty(stackInCursor) == false)
                {
                    leftClickSlot(gui, slot.id);
                }
            }
        }

        return false;
    }

    private static boolean tryMoveSingleItemToThisInventory(Slot slot, ContainerScreen<? extends Container> gui)
    {
        Container container = gui.getContainer();
        ItemStack stackOrig = slot.getStack();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (slot.canInsert(stackOrig) == false)
        {
            return false;
        }

        for (int slotNum = container.slotList.size() - 1; slotNum >= 0; slotNum--)
        {
            Slot slotTmp = container.slotList.get(slotNum);
            ItemStack stackTmp = slotTmp.getStack();

            if (areSlotsInSameInventory(slotTmp, slot) == false &&
                isStackEmpty(stackTmp) == false && slotTmp.canTakeItems(mc.player) &&
                (getStackSize(stackTmp) == 1 || slotTmp.canInsert(stackTmp)))
            {
                if (areStacksEqual(stackTmp, stackOrig))
                {
                    return clickSlotsToMoveSingleItem(gui, slotTmp.id, slot.id);
                }
            }
        }

        // If we weren't able to move any items from another inventory, then try to move items
        // within the same inventory (mostly between the hotbar and the player inventory)
        /*
        for (Slot slotTmp : container.slotList)
        {
            ItemStack stackTmp = slotTmp.getStack();

            if (slotTmp.id != slot.id &&
                isStackEmpty(stackTmp) == false && slotTmp.canTakeItems(gui.mc.player) &&
                (getStackSize(stackTmp) == 1 || slotTmp.canInsert(stackTmp)))
            {
                if (areStacksEqual(stackTmp, stackOrig))
                {
                    return this.clickSlotsToMoveSingleItem(gui, slotTmp.id, slot.id);
                }
            }
        }
        */

        return false;
    }

    public static void tryMoveStacks(Slot slot, ContainerScreen<? extends Container> gui, boolean matchingOnly, boolean toOtherInventory, boolean firstOnly)
    {
        tryMoveStacks(slot.getStack(), slot, gui, matchingOnly, toOtherInventory, firstOnly);
    }

    private static void tryMoveStacks(ItemStack stackReference, Slot slot, ContainerScreen<? extends Container> gui, boolean matchingOnly, boolean toOtherInventory, boolean firstOnly)
    {
        Container container = gui.getContainer();
        final int maxSlot = container.slotList.size() - 1;

        for (int i = maxSlot; i >= 0; i--)
        {
            Slot slotTmp = container.slotList.get(i);

            if (slotTmp.id != slot.id &&
                areSlotsInSameInventory(slotTmp, slot) == toOtherInventory && slotTmp.hasStack() &&
                (matchingOnly == false || areStacksEqual(stackReference, slotTmp.getStack())))
            {
                boolean success = shiftClickSlotWithCheck(gui, slotTmp.id);

                // Failed to shift-click items, try a manual method
                if (success == false && Configs.Toggles.SCROLL_STACKS_FALLBACK.getBooleanValue())
                {
                    clickSlotsToMoveItemsFromSlot(slotTmp, gui, toOtherInventory);
                }

                if (firstOnly)
                {
                    return;
                }
            }
        }

        // If moving to the other inventory, then move the hovered slot's stack last
        if (toOtherInventory && shiftClickSlotWithCheck(gui, slot.id) == false)
        {
            clickSlotsToMoveItemsFromSlot(slot, gui, toOtherInventory);
        }
    }

    private static void tryMoveItemsToMerchantBuySlots(VillagerScreen gui, boolean fillStacks)
    {
        TraderOfferList list = gui.getContainer().getRecipes();
        int index = AccessorUtils.getSelectedMerchantRecipe(gui);

        if (list == null || list.size() <= index)
        {
            return;
        }

        TradeOffer recipe = list.get(index);

        if (recipe == null)
        {
            return;
        }

        ItemStack buy1 = recipe.getAdjustedFirstBuyItem();
        ItemStack buy2 = recipe.getSecondBuyItem();

        if (isStackEmpty(buy1) == false)
        {
            fillBuySlot(gui, 0, buy1, fillStacks);
        }

        if (isStackEmpty(buy2) == false)
        {
            fillBuySlot(gui, 1, buy2, fillStacks);
        }
    }

    private static void fillBuySlot(ContainerScreen<? extends Container> gui, int slotNum, ItemStack buyStack, boolean fillStacks)
    {
        Slot slot = gui.getContainer().getSlot(slotNum);
        ItemStack existingStack = slot.getStack();
        MinecraftClient mc = MinecraftClient.getInstance();

        // If there are items not matching the merchant recipe, move them out first
        if (isStackEmpty(existingStack) == false && areStacksEqual(buyStack, existingStack) == false)
        {
            shiftClickSlot(gui, slotNum);
        }

        existingStack = slot.getStack();

        if (isStackEmpty(existingStack) || areStacksEqual(buyStack, existingStack))
        {
            moveItemsFromInventory(gui, slotNum, mc.player.inventory, buyStack, fillStacks);
        }
    }

    public static void handleRecipeClick(ContainerScreen<? extends Container> gui, MinecraftClient mc, RecipeStorage recipes, int hoveredRecipeId,
            boolean isLeftClick, boolean isRightClick, boolean isPickBlock, boolean isShiftDown)
    {
        if (isLeftClick || isRightClick)
        {
            boolean changed = recipes.getSelection() != hoveredRecipeId;
            recipes.changeSelectedRecipe(hoveredRecipeId);

            if (changed)
            {
                InventoryUtils.clearFirstCraftingGridOfItems(recipes.getSelectedRecipe(), gui, false);
            }

            InventoryUtils.tryMoveItemsToFirstCraftingGrid(recipes.getRecipe(hoveredRecipeId), gui, isShiftDown);

            // Right click: Also craft the items
            if (isRightClick)
            {
                Slot outputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);
                boolean dropKeyDown = mc.options.keyDrop.isPressed(); // FIXME 1.14

                if (outputSlot != null)
                {
                    if (dropKeyDown)
                    {
                        if (isShiftDown)
                        {
                            if (Configs.Generic.CARPET_CTRL_Q_CRAFTING.getBooleanValue())
                            {
                                InventoryUtils.dropStack(gui, outputSlot.id);
                            }
                            else
                            {
                                InventoryUtils.dropStacksUntilEmpty(gui, outputSlot.id);
                            }
                        }
                        else
                        {
                            InventoryUtils.dropItem(gui, outputSlot.id);
                        }
                    }
                    else
                    {
                        if (isShiftDown)
                        {
                            InventoryUtils.shiftClickSlot(gui, outputSlot.id);
                        }
                        else
                        {
                            InventoryUtils.moveOneSetOfItemsFromSlotToPlayerInventory(gui, outputSlot);
                        }
                    }
                }
            }
        }
        else if (isPickBlock)
        {
            InventoryUtils.clearFirstCraftingGridOfAllItems(gui);
        }
    }

    public static void tryMoveItemsToFirstCraftingGrid(RecipePattern recipe, ContainerScreen<? extends Container> gui, boolean fillStacks)
    {
        Slot craftingOutputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (craftingOutputSlot != null)
        {
            tryMoveItemsToCraftingGridSlots(recipe, craftingOutputSlot, gui, fillStacks);
        }
    }

    public static void loadRecipeItemsToGridForOutputSlotUnderMouse(RecipePattern recipe, ContainerScreen<? extends Container> gui)
    {
        Slot slot = AccessorUtils.getSlotUnderMouse(gui);
        loadRecipeItemsToGridForOutputSlot(recipe, gui, slot);
    }

    private static void loadRecipeItemsToGridForOutputSlot(RecipePattern recipe, ContainerScreen<? extends Container> gui, Slot outputSlot)
    {
        if (outputSlot != null && isCraftingSlot(gui, outputSlot) && isStackEmpty(recipe.getResult()) == false)
        {
            tryMoveItemsToCraftingGridSlots(recipe, outputSlot, gui, false);
        }
    }

    private static boolean tryMoveItemsCrafting(RecipeStorage recipes, Slot slot, ContainerScreen<? extends Container> gui,
            boolean moveToOtherInventory, boolean moveStacks, boolean moveEverything)
    {
        RecipePattern recipe = recipes.getSelectedRecipe();
        ItemStack stackRecipeOutput = recipe.getResult();

        // Try to craft items
        if (moveToOtherInventory)
        {
            // Items in the output slot
            if (slot.hasStack())
            {
                // The output item matches the current recipe
                if (areStacksEqual(slot.getStack(), stackRecipeOutput))
                {
                    if (moveEverything)
                    {
                        craftAsManyItemsAsPossible(recipe, slot, gui);
                    }
                    else if (moveStacks)
                    {
                        shiftClickSlot(gui, slot.id);
                    }
                    else
                    {
                        moveOneSetOfItemsFromSlotToPlayerInventory(gui, slot);
                    }
                }
            }
            // Scrolling over an empty output slot, clear the grid
            else
            {
                clearCraftingGridOfAllItems(gui, CraftingHandler.getCraftingGridSlots(gui, slot));
            }
        }
        // Try to move items to the grid
        else if (moveToOtherInventory == false && isStackEmpty(stackRecipeOutput) == false)
        {
            tryMoveItemsToCraftingGridSlots(recipe, slot, gui, moveStacks);
        }

        return false;
    }

    private static void craftAsManyItemsAsPossible(RecipePattern recipe, Slot slot, ContainerScreen<? extends Container> gui)
    {
        ItemStack result = recipe.getResult();
        int failSafe = 1024;

        while (failSafe > 0 && slot.hasStack() && areStacksEqual(slot.getStack(), result))
        {
            shiftClickSlot(gui, slot.id);

            // Ran out of some or all ingredients for the recipe
            if (slot.hasStack() == false || areStacksEqual(slot.getStack(), result) == false)
            {
                tryMoveItemsToCraftingGridSlots(recipe, slot, gui, true);
            }
            // No change in the result slot after shift clicking, let's assume the craft failed and stop here
            else
            {
                break;
            }

            failSafe--;
        }
    }

    public static void clearFirstCraftingGridOfItems(RecipePattern recipe, ContainerScreen<? extends Container> gui, boolean clearNonMatchingOnly)
    {
        Slot craftingOutputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (craftingOutputSlot != null)
        {
            SlotRange range = CraftingHandler.getCraftingGridSlots(gui, craftingOutputSlot);
            clearCraftingGridOfItems(recipe, gui, range, clearNonMatchingOnly);
        }
    }

    public static void clearFirstCraftingGridOfAllItems(ContainerScreen<? extends Container> gui)
    {
        Slot craftingOutputSlot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (craftingOutputSlot != null)
        {
            SlotRange range = CraftingHandler.getCraftingGridSlots(gui, craftingOutputSlot);
            clearCraftingGridOfAllItems(gui, range);
        }
    }

    private static boolean clearCraftingGridOfItems(RecipePattern recipe, ContainerScreen<? extends Container> gui, SlotRange range, boolean clearNonMatchingOnly)
    {
        final int invSlots = gui.getContainer().slotList.size();
        final int rangeSlots = range.getSlotCount();
        final int recipeSize = recipe.getRecipeLength();
        final int slotCount = Math.min(rangeSlots, recipeSize);

        for (int i = 0, slotNum = range.getFirst(); i < slotCount && slotNum < invSlots; i++, slotNum++)
        {
            Slot slotTmp = gui.getContainer().getSlot(slotNum);

            if (slotTmp != null && slotTmp.hasStack() &&
                (clearNonMatchingOnly == false || areStacksEqual(recipe.getRecipeItems()[i], slotTmp.getStack()) == false))
            {
                shiftClickSlot(gui, slotNum);

                // Failed to clear the slot
                if (slotTmp.hasStack())
                {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean clearCraftingGridOfAllItems(ContainerScreen<? extends Container> gui, SlotRange range)
    {
        final int invSlots = gui.getContainer().slotList.size();
        final int rangeSlots = range.getSlotCount();
        boolean clearedAll = true;

        for (int i = 0, slotNum = range.getFirst(); i < rangeSlots && slotNum < invSlots; i++, slotNum++)
        {
            Slot slotTmp = gui.getContainer().getSlot(slotNum);

            if (slotTmp != null && slotTmp.hasStack())
            {
                shiftClickSlot(gui, slotNum);

                // Failed to clear the slot
                if (slotTmp.hasStack())
                {
                    clearedAll = false;
                }
            }
        }

        return clearedAll;
    }

    private static boolean tryMoveItemsToCraftingGridSlots(RecipePattern recipe, Slot slot, ContainerScreen<? extends Container> gui, boolean fillStacks)
    {
        Container container = gui.getContainer();
        int numSlots = container.slotList.size();
        SlotRange range = CraftingHandler.getCraftingGridSlots(gui, slot);

        // Check that the slot range is valid and that the recipe can fit into this type of crafting grid
        if (range != null && range.getLast() < numSlots && recipe.getRecipeLength() <= range.getSlotCount())
        {
            // Clear non-matching items from the grid first
            if (clearCraftingGridOfItems(recipe, gui, range, true) == false)
            {
                return false;
            }

            // This slot is used to check that we get items from a DIFFERENT inventory than where this slot is in
            Slot slotGridFirst = container.getSlot(range.getFirst());
            Map<ItemType, List<Integer>> ingredientSlots = ItemType.getSlotsPerItem(recipe.getRecipeItems());

            for (Map.Entry<ItemType, List<Integer>> entry : ingredientSlots.entrySet())
            {
                ItemStack ingredientReference = entry.getKey().getStack();
                List<Integer> recipeSlots = entry.getValue();
                List<Integer> targetSlots = new ArrayList<Integer>();

                // Get the actual target slot numbers based on the grid's start and the relative positions inside the grid
                for (int s : recipeSlots)
                {
                    targetSlots.add(s + range.getFirst());
                }

                if (fillStacks)
                {
                    fillCraftingGrid(gui, slotGridFirst, ingredientReference, targetSlots);
                }
                else
                {
                    moveOneRecipeItemIntoCraftingGrid(gui, slotGridFirst, ingredientReference, targetSlots);
                }
            }
        }

        return false;
    }

    private static void fillCraftingGrid(ContainerScreen<? extends Container> gui, Slot slotGridFirst, ItemStack ingredientReference, List<Integer> targetSlots)
    {
        Container container = gui.getContainer();
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        int slotNum = -1;
        int slotReturn = -1;
        int sizeOrig = 0;

        if (isStackEmpty(ingredientReference))
        {
            return;
        }

        while (true)
        {
            slotNum = getSlotNumberOfLargestMatchingStackFromDifferentInventory(container, slotGridFirst, ingredientReference);

            // Didn't find ingredient items
            if (slotNum < 0)
            {
                break;
            }

            if (slotReturn == -1)
            {
                slotReturn = slotNum;
            }

            // Pick up the ingredient stack from the found slot
            leftClickSlot(gui, slotNum);

            ItemStack stackCursor = player.inventory.getCursorStack();

            // Successfully picked up ingredient items
            if (areStacksEqual(ingredientReference, stackCursor))
            {
                sizeOrig = getStackSize(stackCursor);
                dragSplitItemsIntoSlots(gui, targetSlots);
                stackCursor = player.inventory.getCursorStack();

                // Items left in cursor
                if (isStackEmpty(stackCursor) == false)
                {
                    // Didn't manage to move any items anymore
                    if (getStackSize(stackCursor) >= sizeOrig)
                    {
                        break;
                    }

                    // Collect all the remaining items into the first found slot, as long as possible
                    leftClickSlot(gui, slotReturn);

                    // All of them didn't fit into the first slot anymore, switch into the current source slot
                    if (isStackEmpty(player.inventory.getCursorStack()) == false)
                    {
                        slotReturn = slotNum;
                        leftClickSlot(gui, slotReturn);
                    }
                }
            }
            // Failed to pick up the stack, break to avoid infinite loops
            // TODO: we could also "blacklist" this slot and try to continue...?
            else
            {
                break;
            }

            // Somehow items were left in the cursor, break here
            if (isStackEmpty(player.inventory.getCursorStack()) == false)
            {
                break;
            }
        }

        // Return the rest of the items to the original slot
        if (slotNum >= 0 && isStackEmpty(player.inventory.getCursorStack()) == false)
        {
            leftClickSlot(gui, slotNum);
        }
    }

    public static void rightClickCraftOneStack(ContainerScreen<? extends Container> gui)
    {
        Slot slot = AccessorUtils.getSlotUnderMouse(gui);
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.inventory;
        ItemStack stackCursor = inv.getCursorStack();

        if (slot == null || slot.hasStack() == false ||
            (isStackEmpty(stackCursor) == false) && areStacksEqual(slot.getStack(), stackCursor) == false)
        {
            return;
        }

        int sizeLast = 0;

        while (true)
        {
            rightClickSlot(gui, slot.id);
            stackCursor = inv.getCursorStack();

            // Failed to craft items, or the stack became full, or ran out of ingredients
            if (isStackEmpty(stackCursor) || getStackSize(stackCursor) <= sizeLast ||
                getStackSize(stackCursor) >= stackCursor.getMaxAmount() ||
                areStacksEqual(slot.getStack(), stackCursor) == false)
            {
                break;
            }

            sizeLast = getStackSize(stackCursor);
        }
    }

    public static void craftEverythingPossibleWithCurrentRecipe(RecipePattern recipe, ContainerScreen<? extends Container> gui)
    {
        Slot slot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (slot != null && isStackEmpty(recipe.getResult()) == false)
        {
            SlotRange range = CraftingHandler.getCraftingGridSlots(gui, slot);

            if (range != null)
            {
                // Clear all items from the grid first, to avoid unbalanced stacks
                if (clearCraftingGridOfItems(recipe, gui, range, false) == false)
                {
                    return;
                }

                tryMoveItemsToCraftingGridSlots(recipe, slot, gui, true);

                if (slot.hasStack())
                {
                    craftAsManyItemsAsPossible(recipe, slot, gui);
                }
            }
        }
    }

    public static void moveAllCraftingResultsToOtherInventory(RecipePattern recipe, ContainerScreen<? extends Container> gui)
    {
        if (isStackEmpty(recipe.getResult()) == false)
        {
            Slot slot = null;
            ItemStack stackResult = recipe.getResult().copy();

            for (Slot slotTmp : gui.getContainer().slotList)
            {
                // This slot is likely in the player inventory, as there is another inventory above
                if (areStacksEqual(slotTmp.getStack(), stackResult) &&
                    inventoryExistsAbove(slotTmp, gui.getContainer()))
                {
                    slot = slotTmp;
                    break;
                }
            }

            if (slot != null)
            {
                // Get a list of slots with matching items, which are in the same inventory
                // as the slot that is assumed to be in the player inventory.
                List<Integer> slots = getSlotNumbersOfMatchingStacks(gui.getContainer(), slot, true, stackResult, false, false, false);

                for (int slotNum : slots)
                {
                    shiftClickSlot(gui, slotNum);
                }
            }
        }
    }

    public static void throwAllCraftingResultsToGround(RecipePattern recipe, ContainerScreen<? extends Container> gui)
    {
        Slot slot = CraftingHandler.getFirstCraftingOutputSlotForGui(gui);

        if (slot != null && isStackEmpty(recipe.getResult()) == false)
        {
            dropStacks(gui, recipe.getResult(), slot, false);
        }
    }

    private static int putSingleItemIntoSlots(ContainerScreen<? extends Container> gui, List<Integer> targetSlots, int startIndex)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack stackInCursor = mc.player.inventory.getCursorStack();

        if (isStackEmpty(stackInCursor))
        {
            return 0;
        }

        int numSlots = gui.getContainer().slotList.size();
        int numItems = getStackSize(stackInCursor);
        int loops = Math.min(numItems, targetSlots.size() - startIndex);
        int count = 0;

        for (int i = 0; i < loops; i++)
        {
            int slotNum = targetSlots.get(startIndex + i);

            if (slotNum >= numSlots)
            {
                break;
            }

            rightClickSlot(gui, slotNum);
            count++;
        }

        return count;
    }

    public static void moveOneSetOfItemsFromSlotToPlayerInventory(ContainerScreen<? extends Container> gui, Slot slot)
    {
        leftClickSlot(gui, slot.id);

        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack stackCursor = mc.player.inventory.getCursorStack();

        if (isStackEmpty(stackCursor) == false)
        {
            List<Integer> slots = getSlotNumbersOfMatchingStacks(gui.getContainer(), slot, false, stackCursor, true, true, false);

            if (moveItemFromCursorToSlots(gui, slots) == false)
            {
                slots = getSlotNumbersOfEmptySlotsInPlayerInventory(gui.getContainer(), false);
                moveItemFromCursorToSlots(gui, slots);
            }
        }
    }

    private static void moveOneRecipeItemIntoCraftingGrid(ContainerScreen<? extends Container> gui, Slot slotGridFirst, ItemStack ingredientReference, List<Integer> targetSlots)
    {
        Container container = gui.getContainer();
        MinecraftClient mc = MinecraftClient.getInstance();
        int index = 0;
        int slotNum = -1;
        int slotCount = targetSlots.size();

        while (index < slotCount)
        {
            slotNum = getSlotNumberOfSmallestStackFromDifferentInventory(container, slotGridFirst, ingredientReference, slotCount);

            // Didn't find ingredient items
            if (slotNum < 0)
            {
                break;
            }

            // Pick up the ingredient stack from the found slot
            leftClickSlot(gui, slotNum);

            // Successfully picked up ingredient items
            if (areStacksEqual(ingredientReference, mc.player.inventory.getCursorStack()))
            {
                int filled = putSingleItemIntoSlots(gui, targetSlots, index);
                index += filled;

                if (filled < 1)
                {
                    break;
                }
            }
            // Failed to pick up the stack, break to avoid infinite loops
            // TODO: we could also "blacklist" this slot and try to continue...?
            else
            {
                break;
            }
        }

        // Return the rest of the items to the original slot
        if (slotNum >= 0 && isStackEmpty(mc.player.inventory.getCursorStack()) == false)
        {
            leftClickSlot(gui, slotNum);
        }
    }

    private static boolean moveItemFromCursorToSlots(ContainerScreen<? extends Container> gui, List<Integer> slotNumbers)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.inventory;

        for (int slotNum : slotNumbers)
        {
            leftClickSlot(gui, slotNum);

            if (isStackEmpty(inv.getCursorStack()))
            {
                return true;
            }
        }

        return false;
    }

    private static void moveItemsFromInventory(ContainerScreen<? extends Container> gui, int slotTo, Inventory invSrc, ItemStack stackTemplate, boolean fillStacks)
    {
        Container container = gui.getContainer();

        for (Slot slot : container.slotList)
        {
            if (slot == null)
            {
                continue;
            }

            if (slot.inventory == invSrc && areStacksEqual(stackTemplate, slot.getStack()))
            {
                if (fillStacks)
                {
                    if (clickSlotsToMoveItems(gui, slot.id, slotTo) == false)
                    {
                        break;
                    }
                }
                else
                {
                    clickSlotsToMoveSingleItem(gui, slot.id, slotTo);
                    break;
                }
            }
        }
    }

    private static int getSlotNumberOfLargestMatchingStackFromDifferentInventory(Container container, Slot slotReference, ItemStack stackReference)
    {
        int slotNum = -1;
        int largest = 0;

        for (Slot slot : container.slotList)
        {
            if (areSlotsInSameInventory(slot, slotReference) == false && slot.hasStack() &&
                areStacksEqual(stackReference, slot.getStack()))
            {
                int stackSize = getStackSize(slot.getStack());

                if (stackSize > largest)
                {
                    slotNum = slot.id;
                    largest = stackSize;
                }
            }
        }

        return slotNum;
    }

    /**
     * Returns the slot number of the slot that has the smallest stackSize that is still equal to or larger
     * than idealSize. The slot must also NOT be in the same inventory as slotReference.
     * If an adequately large stack is not found, then the largest one is selected.
     * @param container
     * @param slotReference
     * @param stackReference
     * @return
     */
    private static int getSlotNumberOfSmallestStackFromDifferentInventory(Container container, Slot slotReference, ItemStack stackReference, int idealSize)
    {
        int slotNum = -1;
        int smallest = Integer.MAX_VALUE;

        for (Slot slot : container.slotList)
        {
            if (areSlotsInSameInventory(slot, slotReference) == false && slot.hasStack() &&
                areStacksEqual(stackReference, slot.getStack()))
            {
                int stackSize = getStackSize(slot.getStack());

                if (stackSize < smallest && stackSize >= idealSize)
                {
                    slotNum = slot.id;
                    smallest = stackSize;
                }
            }
        }

        // Didn't find an adequately sized stack, now try to find at least some items...
        if (slotNum == -1)
        {
            int largest = 0;

            for (Slot slot : container.slotList)
            {
                if (areSlotsInSameInventory(slot, slotReference) == false && slot.hasStack() &&
                    areStacksEqual(stackReference, slot.getStack()))
                {
                    int stackSize = getStackSize(slot.getStack());

                    if (stackSize > largest)
                    {
                        slotNum = slot.id;
                        largest = stackSize;
                    }
                }
            }
        }

        return slotNum;
    }

    /**
     * Return the slot numbers of slots that have items identical to stackReference.
     * If preferPartial is true, then stacks with a stackSize less that getMaxStackSize() are
     * at the beginning of the list (not ordered though) and full stacks are at the end, otherwise the reverse is true.
     * @param container
     * @param slotReference
     * @param sameInventory if true, then the returned slots are from the same inventory, if false, then from a different inventory
     * @param stackReference
     * @param preferPartial
     * @param treatHotbarAsDifferent
     * @param reverse if true, returns the slots starting from the end of the inventory
     * @return
     */
    private static List<Integer> getSlotNumbersOfMatchingStacks(
            Container container, Slot slotReference, boolean sameInventory,
            ItemStack stackReference, boolean preferPartial, boolean treatHotbarAsDifferent, boolean reverse)
    {
        List<Integer> slots = new ArrayList<Integer>(64);
        final int maxSlot = container.slotList.size() - 1;
        final int increment = reverse ? -1 : 1;

        for (int i = reverse ? maxSlot : 0; i >= 0 && i <= maxSlot; i += increment)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && slot.hasStack() &&
                areSlotsInSameInventory(slot, slotReference, treatHotbarAsDifferent) == sameInventory &&
                areStacksEqual(slot.getStack(), stackReference))
            {
                if ((getStackSize(slot.getStack()) < stackReference.getMaxAmount()) == preferPartial)
                {
                    slots.add(0, slot.id);
                }
                else
                {
                    slots.add(slot.id);
                }
            }
        }

        return slots;
    }

    private static List<Integer> getSlotNumbersOfMatchingStacks(Container container, ItemStack stackReference, boolean preferPartial)
    {
        List<Integer> slots = new ArrayList<Integer>(64);
        final int maxSlot = container.slotList.size() - 1;

        for (int i = 0; i <= maxSlot; ++i)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && slot.hasStack() && areStacksEqual(slot.getStack(), stackReference))
            {
                if ((getStackSize(slot.getStack()) < stackReference.getMaxAmount()) == preferPartial)
                {
                    slots.add(0, slot.id);
                }
                else
                {
                    slots.add(slot.id);
                }
            }
        }

        return slots;
    }

    private static List<Integer> getSlotNumbersOfEmptySlots(
            Container container, Slot slotReference, boolean sameInventory, boolean treatHotbarAsDifferent, boolean reverse)
    {
        List<Integer> slots = new ArrayList<Integer>(64);
        final int maxSlot = container.slotList.size() - 1;
        final int increment = reverse ? -1 : 1;

        for (int i = reverse ? maxSlot : 0; i >= 0 && i <= maxSlot; i += increment)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && slot.hasStack() == false &&
                areSlotsInSameInventory(slot, slotReference, treatHotbarAsDifferent) == sameInventory)
            {
                slots.add(slot.id);
            }
        }

        return slots;
    }

    private static List<Integer> getSlotNumbersOfEmptySlotsInPlayerInventory(Container container, boolean reverse)
    {
        List<Integer> slots = new ArrayList<Integer>(64);
        final int maxSlot = container.slotList.size() - 1;
        final int increment = reverse ? -1 : 1;

        for (int i = reverse ? maxSlot : 0; i >= 0 && i <= maxSlot; i += increment)
        {
            Slot slot = container.getSlot(i);

            if (slot != null && (slot.inventory instanceof PlayerInventory) && slot.hasStack() == false)
            {
                slots.add(slot.id);
            }
        }

        return slots;
    }

    public static boolean areStacksEqual(ItemStack stack1, ItemStack stack2)
    {
        return stack1.isEmpty() == false && stack1.isEqualIgnoreTags(stack2) && ItemStack.areTagsEqual(stack1, stack2);
    }

    private static boolean areSlotsInSameInventory(Slot slot1, Slot slot2)
    {
        return areSlotsInSameInventory(slot1, slot2, false);
    }

    private static boolean areSlotsInSameInventory(Slot slot1, Slot slot2, boolean treatHotbarAsDifferent)
    {
        if (slot1.inventory == slot2.inventory)
        {
            if (treatHotbarAsDifferent && slot1.inventory instanceof PlayerInventory)
            {
                int index1 = AccessorUtils.getSlotIndex(slot1);
                int index2 = AccessorUtils.getSlotIndex(slot2);
                // Don't ever treat the offhand slot as a different inventory
                return index1 == 40 || index2 == 40 || (index1 < 9) == (index2 < 9);
            }

            return true;
        }

        return false;
    }

    private static ItemStack[] getOriginalStacks(Container container)
    {
        ItemStack[] originalStacks = new ItemStack[container.slotList.size()];

        for (int i = 0; i < originalStacks.length; i++)
        {
            originalStacks[i] = container.slotList.get(i).getStack().copy();
        }

        return originalStacks;
    }

    private static void restoreOriginalStacks(Container container, ItemStack[] originalStacks)
    {
        for (int i = 0; i < originalStacks.length; i++)
        {
            ItemStack stackSlot = container.getSlot(i).getStack();

            if (areStacksEqual(stackSlot, originalStacks[i]) == false ||
                (isStackEmpty(stackSlot) == false && getStackSize(stackSlot) != getStackSize(originalStacks[i])))
            {
                container.setStackInSlot(i, originalStacks[i]);
            }
        }
    }

    private static int getTargetSlot(Container container, ItemStack[] originalStacks)
    {
        List<Slot> slots = container.slotList;

        for (int i = 0; i < originalStacks.length; i++)
        {
            ItemStack stackOrig = originalStacks[i];
            ItemStack stackNew = slots.get(i).getStack();

            if ((isStackEmpty(stackOrig) && isStackEmpty(stackNew) == false) ||
               (isStackEmpty(stackOrig) == false && isStackEmpty(stackNew) == false &&
               getStackSize(stackNew) == (getStackSize(stackOrig) + 1)))
            {
                return i;
            }
        }

        return -1;
    }

    /*
    private void clickSlotsToMoveItems(Slot slot, ContainerScreen<? extends Container> gui, boolean matchingOnly, boolean toOtherInventory)
    {
        for (Slot slotTmp : gui.getContainer().slotList)
        {
            if (slotTmp.id != slot.id && areSlotsInSameInventory(slotTmp, slot) == toOtherInventory &&
                slotTmp.hasStack() && (matchingOnly == false || areStacksEqual(slot.getStack(), slotTmp.getStack())))
            {
                this.clickSlotsToMoveItemsFromSlot(slotTmp, gui, toOtherInventory);
                return;
            }
        }

        // Move the hovered-over slot's stack last
        if (toOtherInventory)
        {
            this.clickSlotsToMoveItemsFromSlot(slot, gui, toOtherInventory);
        }
    }
    */

    private static void clickSlotsToMoveItemsFromSlot(Slot slotFrom, ContainerScreen<? extends Container> gui, boolean toOtherInventory)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        // Left click to pick up the found source stack
        leftClickSlot(gui, slotFrom.id);

        if (isStackEmpty(player.inventory.getCursorStack()))
        {
            return;
        }

        for (Slot slotDst : gui.getContainer().slotList)
        {
            ItemStack stackDst = slotDst.getStack();

            if (areSlotsInSameInventory(slotDst, slotFrom) != toOtherInventory &&
                (isStackEmpty(stackDst) || areStacksEqual(stackDst, player.inventory.getCursorStack())))
            {
                // Left click to (try and) place items to the slot
                leftClickSlot(gui, slotDst.id);
            }

            if (isStackEmpty(player.inventory.getCursorStack()))
            {
                return;
            }
        }

        // Couldn't fit the entire stack to the target inventory, return the rest of the items
        if (isStackEmpty(player.inventory.getCursorStack()) == false)
        {
            leftClickSlot(gui, slotFrom.id);
        }
    }

    private static boolean clickSlotsToMoveSingleItem(ContainerScreen<? extends Container> gui, int slotFrom, int slotTo)
    {
        //System.out.println("clickSlotsToMoveSingleItem(from: " + slotFrom + ", to: " + slotTo + ")");
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack stack = gui.getContainer().slotList.get(slotFrom).getStack();

        if (isStackEmpty(stack))
        {
            return false;
        }

        // Click on the from-slot to take items to the cursor - if there is more than one item in the from-slot,
        // right click on it, otherwise left click.
        if (getStackSize(stack) > 1)
        {
            rightClickSlot(gui, slotFrom);
        }
        else
        {
            leftClickSlot(gui, slotFrom);
        }

        // Right click on the target slot to put one item to it
        rightClickSlot(gui, slotTo);

        // If there are items left in the cursor, then return them back to the original slot
        if (isStackEmpty(mc.player.inventory.getCursorStack()) == false)
        {
            // Left click again on the from-slot to return the rest of the items to it
            leftClickSlot(gui, slotFrom);
        }

        return true;
    }

    private static boolean clickSlotsToMoveSingleItemByShiftClick(ContainerScreen<? extends Container> gui, int slotFrom)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        Slot slot = gui.getContainer().slotList.get(slotFrom);
        ItemStack stack = slot.getStack();

        if (isStackEmpty(stack))
        {
            return false;
        }

        if (getStackSize(stack) > 1)
        {
            // Left click on the from-slot to take all the items to the cursor
            leftClickSlot(gui, slotFrom);

            // Still items left in the slot, put the stack back and abort
            if (slot.hasStack())
            {
                leftClickSlot(gui, slotFrom);
                return false;
            }
            else
            {
                // Right click one item back to the slot
                rightClickSlot(gui, slotFrom);
            }
        }

        // ... and then shift-click on the slot
        shiftClickSlot(gui, slotFrom);

        if (isStackEmpty(mc.player.inventory.getCursorStack()) == false)
        {
            // ... and then return the rest of the items
            leftClickSlot(gui, slotFrom);
        }

        return true;
    }

    /**
     * Try move items from slotFrom to slotTo
     * @return true if at least some items were moved
     */
    private static boolean clickSlotsToMoveItems(ContainerScreen<? extends Container> gui, int slotFrom, int slotTo)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        //System.out.println("clickSlotsToMoveItems(from: " + slotFrom + ", to: " + slotTo + ")");

        // Left click to take items
        leftClickSlot(gui, slotFrom);

        // Couldn't take the items, bail out now
        if (isStackEmpty(player.inventory.getCursorStack()))
        {
            return false;
        }

        boolean ret = true;
        int size = getStackSize(player.inventory.getCursorStack());

        // Left click on the target slot to put the items to it
        leftClickSlot(gui, slotTo);

        // If there are items left in the cursor, then return them back to the original slot
        if (isStackEmpty(player.inventory.getCursorStack()) == false)
        {
            ret = getStackSize(player.inventory.getCursorStack()) != size;

            // Left click again on the from-slot to return the rest of the items to it
            leftClickSlot(gui, slotFrom);
        }

        return ret;
    }

    public static void dropStacksUntilEmpty(ContainerScreen<? extends Container> gui, int slotNum)
    {
        if (slotNum >= 0 && slotNum < gui.getContainer().slotList.size())
        {
            Slot slot = gui.getContainer().getSlot(slotNum);
            int failsafe = 64;

            while (failsafe-- > 0 && slot.hasStack())
            {
                dropStack(gui, slotNum);
            }
        }
    }

    public static void dropStacksWhileHasItem(ContainerScreen<? extends Container> gui, int slotNum, ItemStack stackReference)
    {
        if (slotNum >= 0 && slotNum < gui.getContainer().slotList.size())
        {
            Slot slot = gui.getContainer().getSlot(slotNum);
            int failsafe = 256;

            while (failsafe-- > 0 && areStacksEqual(slot.getStack(), stackReference))
            {
                dropStack(gui, slotNum);
            }
        }
    }

    private static boolean shiftClickSlotWithCheck(ContainerScreen<? extends Container> gui, int slotNum)
    {
        Slot slot = gui.getContainer().getSlot(slotNum);

        if (slot == null || slot.hasStack() == false)
        {
            return false;
        }

        int sizeOrig = getStackSize(slot.getStack());
        shiftClickSlot(gui, slotNum);

        return slot.hasStack() == false || getStackSize(slot.getStack()) != sizeOrig;
    }

    public static boolean tryMoveItemsVertically(ContainerScreen<? extends Container> gui, Slot slot, boolean moveUp, MoveAmount amount)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        // We require an empty cursor
        if (slot == null || isStackEmpty(mc.player.inventory.getCursorStack()) == false)
        {
            return false;
        }

        List<Integer> slots = getVerticallyFurthestSuitableSlotsForStackInSlot(gui.getContainer(), slot, moveUp);

        if (slots.isEmpty())
        {
            return false;
        }

        if (amount == MoveAmount.FULL_STACKS)
        {
            moveStackToSlots(gui, slot, slots, false);
        }
        else if (amount == MoveAmount.MOVE_ONE)
        {
            moveOneItemToFirstValidSlot(gui, slot, slots);
        }
        else if (amount == MoveAmount.LEAVE_ONE)
        {
            moveStackToSlots(gui, slot, slots, true);
        }
        else if (amount == MoveAmount.ALL_MATCHING)
        {
            moveMatchingStacksToSlots(gui, slot, moveUp);
        }

        return true;
    }

    private static void moveMatchingStacksToSlots(ContainerScreen<? extends Container> gui, Slot slot, boolean moveUp)
    {
        List<Integer> matchingSlots = getSlotNumbersOfMatchingStacks(gui.getContainer(), slot, true, slot.getStack(), true, true, false);
        List<Integer> targetSlots = getSlotNumbersOfEmptySlots(gui.getContainer(), slot, false, true, false);
        targetSlots.addAll(getSlotNumbersOfEmptySlots(gui.getContainer(), slot, true, true, false));
        targetSlots.addAll(matchingSlots);

        Collections.sort(matchingSlots, new SlotVerticalSorterSlotNumbers(gui.getContainer(), ! moveUp));
        Collections.sort(targetSlots, new SlotVerticalSorterSlotNumbers(gui.getContainer(), moveUp));

        for (int i = 0; i < matchingSlots.size(); ++i)
        {
            int srcSlotNum = matchingSlots.get(i).intValue();
            Slot srcSlot = gui.getContainer().getSlot(srcSlotNum);
            Slot lastSlot = moveStackToSlots(gui, srcSlot, targetSlots, false);

            if (lastSlot == null || (lastSlot.id == srcSlot.id || (lastSlot.yPosition > srcSlot.yPosition) == moveUp))
            {
                return;
            }
        }
    }

    private static Slot moveStackToSlots(ContainerScreen<? extends Container> gui, Slot slotFrom, List<Integer> slotsTo, boolean leaveOne)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.inventory;
        Slot lastSlot = null;

        // Empty slot, nothing to do
        if (slotFrom.hasStack() == false)
        {
            return null;
        }

        // Pick up the stack
        leftClickSlot(gui, slotFrom.id);

        if (leaveOne)
        {
            rightClickSlot(gui, slotFrom.id);
        }

        for (int slotNum : slotsTo)
        {
            // Empty cursor, all done here
            if (isStackEmpty(inv.getCursorStack()))
            {
                break;
            }

            Slot dstSlot = gui.getContainer().getSlot(slotNum);

            if (dstSlot.canInsert(inv.getCursorStack()) &&
                (dstSlot.hasStack() == false || areStacksEqual(dstSlot.getStack(), inv.getCursorStack())))
            {
                leftClickSlot(gui, slotNum);
                lastSlot = dstSlot;
            }
        }

        // Return the rest of the items, if any
        if (isStackEmpty(inv.getCursorStack()) == false)
        {
            leftClickSlot(gui, slotFrom.id);
        }

        return lastSlot;
    }

    private static void moveOneItemToFirstValidSlot(ContainerScreen<? extends Container> gui, Slot slotFrom, List<Integer> slotsTo)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.inventory;

        // Pick up half of the the stack
        rightClickSlot(gui, slotFrom.id);

        if (isStackEmpty(inv.getCursorStack()))
        {
            return;
        }

        int sizeOrig = getStackSize(inv.getCursorStack());

        for (int slotNum : slotsTo)
        {
            rightClickSlot(gui, slotNum);
            ItemStack stackCursor = inv.getCursorStack();

            if (isStackEmpty(stackCursor) || getStackSize(stackCursor) != sizeOrig)
            {
                break;
            }
        }

        // Return the rest of the items, if any
        if (isStackEmpty(inv.getCursorStack()) == false)
        {
            leftClickSlot(gui, slotFrom.id);
        }
    }

    private static List<Integer> getVerticallyFurthestSuitableSlotsForStackInSlot(Container container, final Slot slotIn, boolean above)
    {
        if (slotIn == null || slotIn.hasStack() == false)
        {
            return Collections.emptyList();
        }

        List<Integer> slotNumbers = new ArrayList<>();
        ItemStack stackSlot = slotIn.getStack();

        for (Slot slotTmp : container.slotList)
        {
            if (slotTmp.id != slotIn.id && slotTmp.yPosition != slotIn.yPosition)
            {
                if (above == slotTmp.yPosition < slotIn.yPosition)
                {
                    ItemStack stackTmp = slotTmp.getStack();

                    if ((isStackEmpty(stackTmp) && slotTmp.canInsert(stackSlot)) ||
                        (areStacksEqual(stackTmp, stackSlot)) && slotTmp.getMaxStackAmount(stackTmp) > getStackSize(stackTmp))
                    {
                        slotNumbers.add(slotTmp.id);
                    }
                }
            }
        }

        Collections.sort(slotNumbers, new SlotVerticalSorterSlotNumbers(container, above));

        return slotNumbers;
    }

    public static void tryClearCursor(ContainerScreen<? extends Container> gui, MinecraftClient mc)
    {
        ItemStack stackCursor = mc.player.inventory.getCursorStack();

        if (stackCursor.isEmpty() == false)
        {
            List<Integer> emptySlots = getSlotNumbersOfEmptySlotsInPlayerInventory(gui.getContainer(), false);

            if (emptySlots.isEmpty() == false)
            {
                leftClickSlot(gui, emptySlots.get(0));
            }
            else
            {
                List<Integer> matchingSlots = getSlotNumbersOfMatchingStacks(gui.getContainer(), stackCursor, true);

                if (matchingSlots.isEmpty() == false)
                {
                    for (int slotNum : matchingSlots)
                    {
                        Slot slot = gui.getContainer().getSlot(slotNum);
                        ItemStack stackSlot = slot.getStack();

                        if (slot == null || areStacksEqual(stackSlot, stackCursor) == false ||
                            stackSlot.getAmount() >= stackCursor.getMaxAmount())
                        {
                            break;
                        }

                        leftClickSlot(gui, slotNum);
                        stackCursor = mc.player.inventory.getCursorStack();
                    }
                }
            }
        }

        if (mc.player.inventory.getCursorStack().isEmpty() == false)
        {
            dropItemsFromCursor(gui);
        }
    }

    public static void resetLastSlotNumber()
    {
        slotNumberLast = -1;
    }

    public static MoveAction getActiveMoveAction()
    {
        return activeMoveAction;
    }

    /*
    private static class SlotVerticalSorterSlots implements Comparator<Slot>
    {
        private final boolean topToBottom;

        public SlotVerticalSorterSlots(boolean topToBottom)
        {
            this.topToBottom = topToBottom;
        }

        @Override
        public int compare(Slot slot1, Slot slot2)
        {
            if (slot1.yPos == slot2.yPos)
            {
                return (slot1.id < slot2.id) == this.topToBottom ? -1 : 1;
            }

            return (slot1.yPos < slot2.yPos) == this.topToBottom ? -1 : 1;
        }
    }
    */

    private static class SlotVerticalSorterSlotNumbers implements Comparator<Integer>
    {
        private final Container container;
        private final boolean topToBottom;

        public SlotVerticalSorterSlotNumbers(Container container, boolean topToBottom)
        {
            this.container = container;
            this.topToBottom = topToBottom;
        }

        @Override
        public int compare(Integer slotNum1, Integer slotNum2)
        {
            Slot slot1 = this.container.getSlot(slotNum1.intValue());
            Slot slot2 = this.container.getSlot(slotNum2.intValue());

            if (slot1.yPosition == slot2.yPosition)
            {
                return (slot1.id < slot2.id) == this.topToBottom ? -1 : 1;
            }

            return (slot1.yPosition < slot2.yPosition) == this.topToBottom ? -1 : 1;
        }
    }

    public static void clickSlot(ContainerScreen<? extends Container> gui, int slotNum, int mouseButton, SlotActionType type)
    {
        if (slotNum >= 0 && slotNum < gui.getContainer().slotList.size())
        {
            Slot slot = gui.getContainer().getSlot(slotNum);
            clickSlot(gui, slot, slotNum, mouseButton, type);
        }
        else
        {
            try
            {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.interactionManager.method_2906(gui.getContainer().syncId, slotNum, mouseButton, type, mc.player);
            }
            catch (Exception e)
            {
                ItemScroller.logger.warn("Exception while emulating a slot click: gui: '{}', slotNum: {}, mouseButton; {}, SlotActionType: {}",
                        gui.getClass().getName(), slotNum, mouseButton, type, e);
            }
        }
    }

    public static void clickSlot(ContainerScreen<? extends Container> gui, Slot slot, int slotNum, int mouseButton, SlotActionType type)
    {
        try
        {
            AccessorUtils.handleMouseClick(gui, slot, slotNum, mouseButton, type);
        }
        catch (Exception e)
        {
            ItemScroller.logger.warn("Exception while emulating a slot click: gui: '{}', slotNum: {}, mouseButton; {}, SlotActionType: {}",
                    gui.getClass().getName(), slotNum, mouseButton, type, e);
        }
    }

    public static void leftClickSlot(ContainerScreen<? extends Container> gui, Slot slot, int slotNumber)
    {
        clickSlot(gui, slot, slotNumber, 0, SlotActionType.PICKUP);
    }

    public static void rightClickSlot(ContainerScreen<? extends Container> gui, Slot slot, int slotNumber)
    {
        clickSlot(gui, slot, slotNumber, 1, SlotActionType.PICKUP);
    }

    public static void shiftClickSlot(ContainerScreen<? extends Container> gui, Slot slot, int slotNumber)
    {
        clickSlot(gui, slot, slotNumber, 0, SlotActionType.QUICK_MOVE);
    }

    public static void leftClickSlot(ContainerScreen<? extends Container> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 0, SlotActionType.PICKUP);
    }

    public static void rightClickSlot(ContainerScreen<? extends Container> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 1, SlotActionType.PICKUP);
    }

    public static void shiftClickSlot(ContainerScreen<? extends Container> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 0, SlotActionType.QUICK_MOVE);
    }

    public static void dropItemsFromCursor(ContainerScreen<? extends Container> gui)
    {
        clickSlot(gui, -999, 0, SlotActionType.PICKUP);
    }

    public static void dropItem(ContainerScreen<? extends Container> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 0, SlotActionType.THROW);
    }

    public static void dropStack(ContainerScreen<? extends Container> gui, int slotNum)
    {
        clickSlot(gui, slotNum, 1, SlotActionType.THROW);
    }

    public static void swapSlots(ContainerScreen<? extends Container> gui, int slotNum, int otherSlot)
    {
        clickSlot(gui, slotNum, 0, SlotActionType.SWAP);
        clickSlot(gui, otherSlot, 0, SlotActionType.SWAP);
        clickSlot(gui, slotNum, 0, SlotActionType.SWAP);
    }

    private static void dragSplitItemsIntoSlots(ContainerScreen<? extends Container> gui, List<Integer> targetSlots)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack stackInCursor = mc.player.inventory.getCursorStack();

        if (isStackEmpty(stackInCursor))
        {
            return;
        }

        if (targetSlots.size() == 1)
        {
            leftClickSlot(gui, targetSlots.get(0));
            return;
        }

        int numSlots = gui.getContainer().slotList.size();
        int loops = targetSlots.size();

        // Start the drag
        clickSlot(gui, -999, 0, SlotActionType.QUICK_CRAFT);

        for (int i = 0; i < loops; i++)
        {
            int slotNum = targetSlots.get(i);

            if (slotNum >= numSlots)
            {
                break;
            }

            clickSlot(gui, targetSlots.get(i), 1, SlotActionType.QUICK_CRAFT);
        }

        // End the drag
        clickSlot(gui, -999, 2, SlotActionType.QUICK_CRAFT);
    }

    /**************************************************************
     * Compatibility code for pre-1.11 vs. 1.11+
     * Well kind of, as in make the differences minimal,
     * only requires changing these things for the ItemStack
     * related changes.
     *************************************************************/

    public static final ItemStack EMPTY_STACK = ItemStack.EMPTY;

    public static boolean isStackEmpty(ItemStack stack)
    {
        return stack.isEmpty();
    }

    public static int getStackSize(ItemStack stack)
    {
        return stack.getAmount();
    }

    public static void setStackSize(ItemStack stack, int size)
    {
        stack.setAmount(size);
    }
}
