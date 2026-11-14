package com.example.csfluid;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * ПКМ гаечным ключом Create (в основной руке, без приседания):
 *  - по двигателю = переключить авто-режим нагрузки;
 *  - по баку, стоящему вплотную к двигателю = разрешить/запретить двигателю выкачивать из него лаву.
 */
public final class WrenchToggle {
    private WrenchToggle() {}

    private static final ResourceLocation WRENCH_ID = ResourceLocation.fromNamespaceAndPath("create", "wrench");

    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!CsFluid.wrenchToggle()) return;

        Player player = event.getEntity();
        if (player.isShiftKeyDown()) return; // присед + ключ = штатный разбор блока

        ItemStack stack = event.getItemStack();
        if (!WRENCH_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return;

        BlockPos clickedPos = event.getPos();
        BlockEntity be = level.getBlockEntity(clickedPos);
        if (be == null) return;

        // 1. Клик по самому двигателю: авто-режим нагрузки
        if (be instanceof AutoToggle toggle) {
            boolean now = !toggle.csfIsAuto();
            toggle.csfSetAuto(now);
            be.setChanged();

            Component state = Component.literal(now ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН")
                    .withStyle(now ? ChatFormatting.GREEN : ChatFormatting.RED);
            player.displayClientMessage(
                    Component.literal("Авто-режим нагрузки: ").withStyle(ChatFormatting.GRAY)
                            .append(state)
                            .append(Component.literal("  (" + toggle.csfStatus() + ")").withStyle(ChatFormatting.DARK_GRAY)),
                    true);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // 2. Клик по баку рядом с двигателем: разрешить/запретить забор лавы
        if (!CsFluid.pullTanksEnabled()) return;
        IFluidHandler tankCap = level.getCapability(Capabilities.FluidHandler.BLOCK, clickedPos, event.getFace());
        if (tankCap == null) {
            tankCap = level.getCapability(Capabilities.FluidHandler.BLOCK, clickedPos, null);
        }
        // Блок должен иметь настоящую ёмкость (бак, ядро ходока и т.п.); иначе ключ работает как обычно
        if (tankCap == null || tankCap.getTanks() <= 0) return;

        List<AutoToggle> engines = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockEntity neighbor = level.getBlockEntity(clickedPos.relative(dir));
            if (neighbor instanceof AutoToggle engineToggle) {
                engines.add(engineToggle);
            }
        }
        if (engines.isEmpty()) return;

        // Если у всех соседних двигателей бак уже разрешён — запрещаем, иначе разрешаем всем (единое состояние)
        boolean allAllowed = true;
        for (AutoToggle e : engines) {
            if (!e.csfIsTankAllowed(clickedPos)) {
                allAllowed = false;
                break;
            }
        }
        boolean target = !allAllowed;
        for (AutoToggle e : engines) {
            e.csfSetTankAllowed(clickedPos, target);
            ((BlockEntity) e).setChanged();
        }

        Component state = Component.literal(target ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН")
                .withStyle(target ? ChatFormatting.GREEN : ChatFormatting.RED);
        player.displayClientMessage(
                Component.literal("Забор лавы из бака: ").withStyle(ChatFormatting.GRAY).append(state), true);

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
