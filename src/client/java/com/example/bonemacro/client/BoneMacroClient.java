package com.example.bonemacro.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.function.Predicate;

/**
 * R dabao -> /shop -> Mob Drops -> Bone -> Buy More -> Confirm -> saari bones drop.
 * K dabao -> open GUI ke saare slot ke naam chat mein print.
 *
 * Minecraft 1.21.11, Mojang mappings.
 */
public class BoneMacroClient implements ClientModInitializer {

    // ====== CONFIG (apne server ke GUI ke hisaab se badlo) ======
    private static final String SHOP_COMMAND    = "shop";        // bina "/" ke
    private static final String CATEGORY_NAME   = "mob drops";   // lowercase, contains match
    private static final String BUY_MORE_NAME   = "buy more";
    private static final String CONFIRM_NAME    = "confirm";     // "" = skip
    private static final int    BUY_MORE_CLICKS = 35;        // 1 click = 1 stack maana hai -> 36 stack = 1 + 35 clicks
    private static final int    BUY_CLICK_DELAY = 1;         // Buy More clicks ke beech ticks
    private static final int    CLICK_DELAY     = 8;             // ticks (20 = 1 sec)
    private static final int    TIMEOUT_TICKS   = 100;
    // ============================================================

    private enum State { IDLE, WAIT_SHOP, WAIT_ITEM, WAIT_BUY, DROPPING }

    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bonemacro", "main"));

    private KeyMapping startKey;
    private KeyMapping debugKey;

    private State state = State.IDLE;
    private int delay = 0;
    private int timeout = 0;
    private int buyClicksLeft = 0;

    @Override
    public void onInitializeClient() {
        startKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bonemacro.start", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KEY_CATEGORY));
        debugKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.bonemacro.debug", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) {
            state = State.IDLE;
            return;
        }

        while (startKey.consumeClick()) {
            if (state == State.IDLE) start(mc);
            else stop(mc, "Macro band kiya.");
        }
        while (debugKey.consumeClick()) dumpSlots(mc);

        if (state == State.IDLE) return;
        if (delay > 0) { delay--; return; }

        switch (state) {
            case WAIT_SHOP -> {
                if (clickNamed(mc, s -> nameHas(s, CATEGORY_NAME))) {
                    state = State.WAIT_ITEM;
                    next(CLICK_DELAY);
                } else tickTimeout(mc, "Category '" + CATEGORY_NAME + "' nahi mili.");
            }
            case WAIT_ITEM -> {
                if (clickNamed(mc, s -> s.is(Items.BONE))) {
                    state = State.WAIT_BUY;
                    buyClicksLeft = BUY_MORE_CLICKS;
                    next(CLICK_DELAY);
                } else tickTimeout(mc, "Bone item nahi mila.");
            }
            case WAIT_BUY -> {
                if (buyClicksLeft > 0) {
                    if (clickNamed(mc, s -> nameHas(s, BUY_MORE_NAME))) {
                        buyClicksLeft--;
                        next(BUY_CLICK_DELAY);
                    } else tickTimeout(mc, "'" + BUY_MORE_NAME + "' button nahi mila.");
                } else {
                    if (!CONFIRM_NAME.isEmpty()) {
                        clickNamed(mc, s -> nameHas(s, CONFIRM_NAME));
                    }
                    state = State.DROPPING;
                    next(10); // items inventory mein aane ka wait
                }
            }
            case DROPPING -> {
                if (mc.screen != null) {
                    mc.player.closeContainer();
                    next(5);
                    return;
                }
                // player inventory: slot 9-35 main, 36-44 hotbar
                // saare bone stacks ek hi tick mein, bina delay ke drop
                for (int idx = 9; idx <= 44; idx++) {
                    ItemStack s = mc.player.inventoryMenu.getSlot(idx).getItem();
                    if (s.is(Items.BONE)) {
                        // button 1 = poora stack drop (Ctrl+Q jaisa)
                        mc.gameMode.handleInventoryMouseClick(0, idx, 1, ClickType.THROW, mc.player);
                    }
                }
                stop(mc, "Ho gaya! Saari bones drop ho gayi.");
            }
            default -> { }
        }
    }

    private void start(Minecraft mc) {
        mc.player.connection.sendCommand(SHOP_COMMAND);
        state = State.WAIT_SHOP;
        delay = 15;
        timeout = 0;
        msg(mc, "Macro start...");
    }

    private void stop(Minecraft mc, String why) {
        state = State.IDLE;
        msg(mc, why);
    }

    private void next(int d) {
        delay = d;
        timeout = 0;
    }

    private void tickTimeout(Minecraft mc, String why) {
        delay = 5;
        timeout += 5;
        if (timeout >= TIMEOUT_TICKS) {
            stop(mc, "Rukna pada: " + why + " (K dabake slot names check karo)");
        }
    }

    /** Open chest-type GUI mein pehla matching slot, uspe left click. */
    private boolean clickNamed(Minecraft mc, Predicate<ItemStack> match) {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) return false;
        int size = menu.getRowCount() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty() && match.test(s)) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.PICKUP, mc.player);
                return true;
            }
        }
        return false;
    }

    private static boolean nameHas(ItemStack s, String part) {
        return s.getHoverName().getString().toLowerCase().contains(part.toLowerCase());
    }

    private void dumpSlots(Minecraft mc) {
        if (!(mc.player.containerMenu instanceof ChestMenu menu)) {
            msg(mc, "Pehle koi shop GUI kholo, phir K dabao.");
            return;
        }
        int size = menu.getRowCount() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty()) msg(mc, "slot " + i + ": " + s.getHoverName().getString());
        }
    }

    private void msg(Minecraft mc, String text) {
        mc.player.displayClientMessage(Component.literal("[BoneMacro] " + text), false);
    }
}
