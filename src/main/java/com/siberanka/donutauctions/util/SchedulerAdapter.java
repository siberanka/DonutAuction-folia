package com.siberanka.donutauctions.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public final class SchedulerAdapter {

    private SchedulerAdapter() {
    }

    public static void runSync(Plugin plugin, Runnable runnable) {
        try {
            Method getGlobalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            Object scheduler = getGlobalRegionScheduler.invoke(Bukkit.getServer());
            Method execute = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
            execute.invoke(scheduler, plugin, runnable);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }
}