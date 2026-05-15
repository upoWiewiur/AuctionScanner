package org.example.marketanalyzer.util;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import java.lang.reflect.Constructor;
import java.util.Arrays;

public class KeyBindingUtil {
    public static KeyBinding create(String id, int code, String category) {
        System.out.println("[MarketAnalyzer] Creating KeyBinding via robust reflection: " + id);
        
        // Log all available constructors for debugging if needed
        try {
            System.out.println("[MarketAnalyzer] Available KeyBinding constructors:");
            for (Constructor<?> c : KeyBinding.class.getConstructors()) {
                System.out.println("  - " + Arrays.toString(c.getParameterTypes()));
            }
        } catch (Exception ignored) {}

        // 1. Try (String, InputUtil.Type, int, String) - Standard 1.21.1
        try {
            Constructor<KeyBinding> c = KeyBinding.class.getConstructor(String.class, InputUtil.Type.class, int.class, String.class);
            return c.newInstance(id, InputUtil.Type.KEYSYM, code, category);
        } catch (Throwable ignored) {}

        // 2. Try (String, int, String) - Older or simplified versions
        try {
            Constructor<KeyBinding> c = KeyBinding.class.getConstructor(String.class, int.class, String.class);
            return c.newInstance(id, code, category);
        } catch (Throwable ignored) {}

        // Try to handle 1.21.11+ where Category is a custom class (class_11900) instead of String
        for (Constructor<?> c : KeyBinding.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            try {
                // Find a constructor that ends with a non-String category parameter
                if (p.length >= 3 && p[p.length - 1] != String.class && p[p.length - 1] != int.class) {
                    Class<?> categoryClass = p[p.length - 1];
                    Object categoryObj = null;
                    
                    // Try to instantiate the category object
                    try {
                        Constructor<?> catConstr = categoryClass.getDeclaredConstructor(String.class);
                        catConstr.setAccessible(true);
                        categoryObj = catConstr.newInstance(category);
                    } catch (Throwable t1) {
                        // Maybe it's a factory method?
                        for (java.lang.reflect.Method m : categoryClass.getDeclaredMethods()) {
                            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class && m.getReturnType() == categoryClass) {
                                m.setAccessible(true);
                                categoryObj = m.invoke(null, category);
                                break;
                            }
                        }
                    }
                    
                    if (categoryObj != null) {
                        // Now we have the category object, let's call the KeyBinding constructor
                        if (p.length == 3 && p[0] == String.class && p[1] == int.class) {
                            return (KeyBinding) c.newInstance(id, code, categoryObj);
                        } else if (p.length == 4 && p[0] == String.class && p[2] == int.class) {
                            // Assume p[1] is Type
                            if (p[1].isEnum() || p[1].getName().contains("Type")) {
                                return (KeyBinding) c.newInstance(id, InputUtil.Type.KEYSYM, code, categoryObj);
                            }
                        } else if (p.length == 5 && p[0] == String.class && p[2] == int.class) {
                            // Assume p[1] is Type, p[4] might be some int or something else
                            // This is a shot in the dark for the 5-arg constructor
                        }
                    } else {
                        // Dump category class info
                        StringBuilder dump = new StringBuilder("Category class info: ");
                        for (Constructor<?> cc : categoryClass.getDeclaredConstructors()) dump.append(Arrays.toString(cc.getParameterTypes())).append("; ");
                        for (java.lang.reflect.Method m : categoryClass.getDeclaredMethods()) dump.append(m.getName()).append("(").append(Arrays.toString(m.getParameterTypes())).append("); ");
                        throw new RuntimeException("Could not create Category object. Dump: " + dump);
                    }
                }
                
                // Classic String category handling
                if (p.length == 4 && p[0] == String.class && p[2] == int.class && p[3] == String.class) {
                    if (p[1].isEnum() || p[1].getName().contains("Type")) {
                        return (KeyBinding) c.newInstance(id, InputUtil.Type.KEYSYM, code, category);
                    }
                }
                if (p.length == 3 && p[0] == String.class && p[1] == int.class && p[2] == String.class) {
                    return (KeyBinding) c.newInstance(id, code, category);
                }
            } catch (Exception ignored) {}
        }

        throw new RuntimeException("[MarketAnalyzer] CRITICAL: Could not find any valid KeyBinding constructor. " +
                "Check logs for available constructors.");
    }
}
