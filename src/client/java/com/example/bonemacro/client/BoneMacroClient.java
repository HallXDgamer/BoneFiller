package com.example.bonemacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.function.Predicate;

/**
 * Ek key dabao -> /shop -> Mob Drops -> Bone -> Buy More -> Confirm -> sab bones drop.
 *
 * R = macro start/stop
 * K = open container ke saare slots ke naam chat mein print karo (names check karne ke liye)
 *
 * Minecraft 1.21.11 + Yarn mappings (1.21.11+build.4) ke hisaab se.
 */
public class BoneMacroClient implements ClientModInitializer {

    // ====== CONFIG (apne server ke GUI ke hisaab se badlo) ======
    private static final String SHOP_COMMAND   = "shop";        // bina "/" ke
    private static final String CATEGORY_NAME  = "mob drops";   // category item ka naam (lowercase, contains)
    private static final String BUY_MORE_NAME  = "buy more";    // buy more button ka naam
    private static final String CONFIRM_NAME   = "confirm";     // confirm button ka naam ("" = skip)
    private static final int    BUY_MORE_CLICKS = 1;            // kitni baar Buy More dabana hai
    private static final int    CLICK_DELAY    = 8;             // ticks (20 tick = 1 sec)
    private static final int    DROP_DELAY     = 2;             // har stack drop ke beech ticks
    private static final int    TIMEOUT_TICKS  = 100;           // itna wait, phir macro band
    // ============================================================

    private enum State { IDLE, WAIT_SHOP, WAIT_ITEM, WAIT_BUY, DROPPING }

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("bonemacro", "main"));

    private KeyBinding startKey;
    private KeyBinding debugKey;

    private State state = State.IDLE;
    private int delay = 0;
    private int timeout = 0;
    private int buyClicksLeft = 0;
    private int dropIdx = 9;

    @Override
    public void onInitializeClient() {
        startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bonemacro.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
        debugKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bonemacro.debug", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient c) {
        if (c.player == null || c.interactionManager == null) {
            state = State.IDLE;
            return;
        }

        while (startKey.wasPressed()) {
            if (state == State.IDLE) start(c);
            else stop(c, "Macro band kiya.");
        }
        while (debugKey.wasPressed()) dumpSlots(c);

        if (state == State.IDLE) return;
        if (delay > 0) { delay--; return; }

        switch (state) {
            case WAIT_SHOP -> {
                if (clickNamed(c, s -> nameHas(s, CATEGORY_NAME))) {
                    state = State.WAIT_ITEM;
                    next(CLICK_DELAY);
                } else tickTimeout(c, "Category '" + CATEGORY_NAME + "' nahi mili.");
            }
            case WAIT_ITEM -> {
                if (clickNamed(c, s -> s.isOf(Items.BONE))) {
                    state = State.WAIT_BUY;
                    buyClicksLeft = BUY_MORE_CLICKS;
                    next(CLICK_DELAY);
                } else tickTimeout(c, "Bone item nahi mila.");
            }
            case WAIT_BUY -> {
                if (buyClicksLeft > 0) {
                    if (clickNamed(c, s -> nameHas(s, BUY_MORE_NAME))) {
                        buyClicksLeft--;
                        next(3);
                    } else tickTimeout(c, "'" + BUY_MORE_NAME + "' button nahi mila.");
                } else {
                    if (!CONFIRM_NAME.isEmpty()) {
                        clickNamed(c, s -> nameHas(s, CONFIRM_NAME));
                    }
                    state = State.DROPPING;
                    dropIdx = 9;
                    next(20); // purchase hone ka wait
                }
            }
            case DROPPING -> {
                if (c.currentScreen != null) {
                    c.player.closeHandledScreen();
                    next(5);
                    return;
                }
                // player inventory: slot 9-35 main, 36-44 hotbar
                while (dropIdx <= 44) {
                    ItemStack s = c.player.playerScreenHandler.getSlot(dropIdx).getStack();
                    int idx = dropIdx++;
                    if (s.isOf(Items.BONE)) {
                        // button 1 = poora stack drop (Ctrl+Q jaisa)
                        c.interactionManager.clickSlot(0, idx, 1, SlotActionType.THROW, c.player);
                        next(DROP_DELAY);
                        return;
                    }
                }
                stop(c, "Ho gaya! Saari bones drop ho gayi.");
            }
            default -> { }
        }
    }

    private void start(MinecraftClient c) {
        c.player.networkHandler.sendChatCommand(SHOP_COMMAND);
        state = State.WAIT_SHOP;
        delay = 15;
        timeout = 0;
        msg(c, "Macro start...");
    }

    private void stop(MinecraftClient c, String why) {
        state = State.IDLE;
        msg(c, why);
    }

    private void next(int d) {
        delay = d;
        timeout = 0;
    }

    private void tickTimeout(MinecraftClient c, String why) {
        delay = 5;
        timeout += 5;
        if (timeout >= TIMEOUT_TICKS) {
            stop(c, "Rukna pada: " + why + " (K dabake slot names check karo)");
        }
    }

    /** Open chest-type GUI mein pehla slot jo match kare, uspe left click. */
    private boolean clickNamed(MinecraftClient c, Predicate<ItemStack> match) {
        if (!(c.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) return false;
        int size = h.getRows() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (!s.isEmpty() && match.test(s)) {
                c.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.PICKUP, c.player);
                return true;
            }
        }
        return false;
    }

    private static boolean nameHas(ItemStack s, String part) {
        return s.getName().getString().toLowerCase().contains(part.toLowerCase());
    }

    private void dumpSlots(MinecraftClient c) {
        if (!(c.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) {
            msg(c, "Pehle koi shop GUI kholo, phir K dabao.");
            return;
        }
        int size = h.getRows() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (!s.isEmpty()) msg(c, "slot " + i + ": " + s.getName().getString());
        }
    }

    private void msg(MinecraftClient c, String text) {
        c.player.sendMessage(Text.literal("[BoneMacro] " + text), false);
    }
}
