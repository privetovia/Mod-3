package com.example.csfluid.mixin;

import java.util.HashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.csfluid.AutoLoad;
import com.example.csfluid.AutoToggle;
import com.example.csfluid.CsFluid;
import com.example.csfluid.TankPuller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Авто-режим нагрузки для Compact Steam Engine и забор лавы из соседних баков.
 *
 * Слайдер мощности в окне двигателя = ЛИМИТ (csf$limit). Реальная ступень (suStep)
 * подстраивается под нагрузку сети Create, но не выше лимита.
 */
@Mixin(targets = "de.genesis.compactsteam.CompactSteamEngineBlockEntity", remap = false)
public abstract class EngineAutoLoadMixin implements AutoToggle {

    @Shadow private int suStep;

    @Shadow public abstract int getStep();
    @Shadow public abstract int getStressCapacitySU();
    @Shadow public abstract boolean isBurning();
    @Shadow protected abstract void settingsChanged();

    @Unique private int csf$limit = 0;
    @Unique private int csf$timer = 0;
    @Unique private int csf$pullTimer = 0;
    @Unique private int csf$lowerFor = 0;
    @Unique private float csf$lastStress = 0f;
    @Unique private float csf$lastCapacity = 0f;
    @Unique private static boolean csf$errorLogged = false;
    @Unique private boolean csf$announced = false;
    @Unique private boolean csf$auto = true;
    @Unique private final Set<BlockPos> csf$allowedTanks = new HashSet<>();

    // ---------- интерфейс AutoToggle ----------
    @Override public boolean csfIsAuto() { return csf$auto; }
    @Override public void csfSetAuto(boolean value) { csf$auto = value; }

    @Override public boolean csfIsTankAllowed(BlockPos pos) { return csf$allowedTanks.contains(pos); }

    @Override
    public void csfSetTankAllowed(BlockPos pos, boolean allowed) {
        if (allowed) csf$allowedTanks.add(pos.immutable());
        else csf$allowedTanks.remove(pos);
    }

    @Override public Set<BlockPos> csfGetAllowedTanks() { return csf$allowedTanks; }

    @Override
    public String csfStatus() {
        return "ступень " + getStep() + ", лимит " + csf$limit
                + ", нагрузка " + Math.round(csf$lastStress) + " SU, ёмкость " + Math.round(csf$lastCapacity) + " SU";
    }

    // ---------- слайдер = лимит ----------
    /** После любого setStep (в том числе когда значение не изменилось) лимит = реальное значение ступени. */
    @Inject(method = "setStep", at = @At("RETURN"), remap = false, require = 0)
    private void csf$afterSetStep(int step, CallbackInfo ci) {
        csf$limit = Math.max(1, getStep());
    }

    // ---------- сохранение ----------
    @Inject(method = "write", at = @At("TAIL"), remap = false, require = 0)
    private void csf$write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        tag.putInt("CsfLimit", csf$limit > 0 ? csf$limit : Math.max(1, getStep()));
        tag.putBoolean("CsfAuto", csf$auto);

        long[] tankArr = csf$allowedTanks.stream().mapToLong(BlockPos::asLong).toArray();
        tag.putLongArray("CsfAllowedTanks", tankArr);
    }

    @Inject(method = "read", at = @At("TAIL"), remap = false, require = 0)
    private void csf$read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (tag.contains("CsfLimit")) csf$limit = Math.max(1, tag.getInt("CsfLimit"));
        if (tag.contains("CsfAuto")) csf$auto = tag.getBoolean("CsfAuto");

        if (tag.contains("CsfAllowedTanks")) {
            csf$allowedTanks.clear();
            for (long l : tag.getLongArray("CsfAllowedTanks")) {
                csf$allowedTanks.add(BlockPos.of(l));
            }
        }
    }

    // ---------- основной тик ----------
    @Inject(method = "tick", at = @At("HEAD"), remap = false, require = 0)
    private void csf$tick(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;

        if (!csf$announced) {
            csf$announced = true;
            CsFluid.LOG.info("[csfluid] Двигатель {} активен (авто={}, ступень={}, лимит={}, баков={})",
                    be.getBlockPos(), CsFluid.autoEnabled(), getStep(), csf$limit, csf$allowedTanks.size());
        }

        // 1. Забор лавы из разрешённых соседних баков
        if (CsFluid.pullTanksEnabled() && !csf$allowedTanks.isEmpty()) {
            if (++csf$pullTimer >= CsFluid.pullInterval()) {
                csf$pullTimer = 0;
                try {
                    TankPuller.tickPull(be, this);
                } catch (Throwable t) {
                    if (!csf$errorLogged) {
                        csf$errorLogged = true;
                        CsFluid.LOG.error("[csfluid] Ошибка при выкачке лавы из бака", t);
                    }
                }
            }
        }

        // 2. Авто-регулировка мощности
        try {
            csf$autoLoad(be);
        } catch (Throwable t) {
            if (!csf$errorLogged) {
                csf$errorLogged = true;
                CsFluid.LOG.error("[csfluid] Ошибка в авто-режиме двигателя", t);
            }
        }
    }

    @Unique
    private void csf$autoLoad(BlockEntity be) {
        if (csf$limit <= 0) csf$limit = Math.max(1, getStep());

        // Авто-режим выключен (в конфиге или ключом у этого двигателя) -> держим ступень = лимит
        if (!CsFluid.autoEnabled() || !csf$auto) {
            csf$lowerFor = 0;
            if (getStep() != csf$limit) {
                suStep = csf$limit;
                csf$limit = Math.max(1, getStep()); // если Compact Steam ограничил значение (макс. ступень) — принимаем его
                settingsChanged();
            }
            return;
        }

        int interval = CsFluid.interval();
        if (++csf$timer < interval) return;
        csf$timer = 0;

        // Двигатель не горит (нет топлива / редстоун): ступень не трогаем,
        // чтобы при запуске он стартовал с последней рабочей ступени, а не с минимума
        if (!isBurning()) {
            csf$lowerFor = 0;
            return;
        }

        int cur = Math.max(1, getStep());
        int capSu = getStressCapacitySU();
        int perStep = Math.max(1, capSu / cur);

        AutoLoad.Reading r = AutoLoad.read(be);
        if (r == null) return;
        csf$lastStress = r.stress;
        csf$lastCapacity = r.capacity;

        // Вклад самого двигателя в ёмкость сети: точный из Create, иначе оценка по ступени
        float own = r.own >= 0f ? Math.min(r.own, r.capacity) : (float) capSu;
        float others = Math.max(0f, r.capacity - own);
        float need = Math.max(0f, r.stress - others);

        int want = (int) Math.ceil(need * (1.0 + CsFluid.margin()) / perStep);
        int min = CsFluid.minStep();
        int max = Math.max(min, csf$limit);
        want = Mth.clamp(want, min, max);

        // Сеть перегружена: Create мог не показать реальную нагрузку (потребители с динамическим
        // потреблением «замирают» при перегрузе), поэтому поднимаем мощность по шагу и не снижаем.
        if (r.overstressed) {
            want = Math.max(want, Math.min(max, cur + 1));
        }

        if (want > cur) {
            csf$lowerFor = 0;
            csf$apply(be, r, cur, want, max);
        } else if (want < cur) {
            csf$lowerFor += interval;
            if (csf$lowerFor >= CsFluid.lowerDelay()) {
                csf$lowerFor = 0;
                csf$apply(be, r, cur, want, max);
            }
        } else {
            csf$lowerFor = 0;
        }
    }

    @Unique
    private void csf$apply(BlockEntity be, AutoLoad.Reading r, int cur, int want, int max) {
        if (CsFluid.debugLog()) {
            CsFluid.LOG.info("[csfluid] {}: нагрузка={} SU, ёмкость={} SU, свой вклад={} SU, перегруз={}, ступень {} -> {} (лимит {})",
                    be.getBlockPos(), r.stress, r.capacity, r.own, r.overstressed, cur, want, max);
        }
        suStep = want;
        if (getStep() != want) {
            csf$limit = Math.max(1, getStep()); // упёрлись в максимальную ступень Compact Steam
        }
        settingsChanged();
    }
}
