package com.wzz.inventory_sort.util;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class SophisticatedBackpacksHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static Method sortMethod = null;
    private static boolean reflectionInitialized = false;

    public static boolean hasSophisticatedBackpacksMod() {
        return ModList.get().isLoaded("sophisticatedbackpacks");
    }

    public static boolean sortBackpack(Screen screen) {
        initializeReflection();
        if (sortMethod == null) {
            return false;
        }
        try {
            return (boolean) sortMethod.invoke(null, screen);
        } catch (InvocationTargetException | IllegalAccessException e) {
            LOGGER.error("调用 Sophisticated Backpacks 排序方法失败: {}", e.getMessage());
            LOGGER.debug("完整错误信息:", e);
            return false;
        }
    }

    private static synchronized void initializeReflection() {
        if (reflectionInitialized) {
            return;
        }
        try {
            Class<?> clazz = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.client.KeybindHandler");
            sortMethod = clazz.getDeclaredMethod("tryCallSort", Screen.class);
            sortMethod.setAccessible(true);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Sophisticated Backpacks 类未找到，可能版本不兼容");
        } catch (NoSuchMethodException e) {
            LOGGER.warn("Sophisticated Backpacks 排序方法未找到，可能版本不兼容");
        } catch (Exception e) {
            LOGGER.error("初始化 Sophisticated Backpacks 反射方法失败: {}", e.getMessage());
        } finally {
            reflectionInitialized = true;
        }
    }
}