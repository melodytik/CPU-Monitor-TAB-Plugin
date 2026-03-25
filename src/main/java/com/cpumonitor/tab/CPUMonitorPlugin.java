package com.cpumonitor.tab;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.function.Supplier;

public class CPUMonitorPlugin extends JavaPlugin {
    
    private final java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final Runtime runtime = Runtime.getRuntime();
    private final DecimalFormat df = new DecimalFormat("0.00");
    
    @Override
    public void onEnable() {
        getLogger().info("CPU Monitor for TAB plugin enabled!");
        
        // 等待服务器完全启动后再尝试注册占位符
        getServer().getScheduler().runTaskLater(this, this::tryRegisterPlaceholders, 40L);
    }
    
    private void tryRegisterPlaceholders() {
        // 检查TAB插件是否存在
        if (getServer().getPluginManager().getPlugin("TAB") == null) {
            getLogger().warning("TAB plugin not found! CPU monitoring placeholders will not be available.");
            return;
        }
        
        getLogger().info("TAB plugin detected, attempting to register placeholders...");
        
        // 尝试多次注册，因为TAB可能需要时间初始化
        for (int i = 0; i < 5; i++) {
            if (registerPlaceholders()) {
                getLogger().info("Successfully registered CPU/Memory placeholders for TAB!");
                return;
            }
            
            // 等待1秒后重试
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        getLogger().warning("Failed to register placeholders after multiple attempts. TAB API may not be available.");
    }
    
    private boolean registerPlaceholders() {
        try {
            // 使用反射获取TAB API
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Method getInstanceMethod = tabApiClass.getMethod("getInstance");
            Object tabApi = getInstanceMethod.invoke(null);
            
            if (tabApi == null) {
                getLogger().warning("TAB API instance is null");
                return false;
            }
            
            Method getPlaceholderManagerMethod = tabApiClass.getMethod("getPlaceholderManager");
            Object placeholderManager = getPlaceholderManagerMethod.invoke(tabApi);
            
            if (placeholderManager == null) {
                getLogger().warning("TAB PlaceholderManager is null");
                return false;
            }
            
            // 尝试不同的方法签名
            Method registerMethod = null;
            try {
                // 尝试新版API: registerServerPlaceholder(String, int, Supplier<String>)
                registerMethod = placeholderManager.getClass().getMethod(
                    "registerServerPlaceholder", String.class, int.class, Supplier.class
                );
            } catch (NoSuchMethodException e1) {
                try {
                    // 尝试旧版API: registerServerPlaceholder(String, Supplier<String>)
                    registerMethod = placeholderManager.getClass().getMethod(
                        "registerServerPlaceholder", String.class, Supplier.class
                    );
                } catch (NoSuchMethodException e2) {
                    getLogger().warning("No suitable registerServerPlaceholder method found");
                    return false;
                }
            }
            
            // 注册占位符
            registerPlaceholder(registerMethod, placeholderManager, "%cpu_usage%", 1000, () -> {
                return getCPUUsage();
            });
            
            registerPlaceholder(registerMethod, placeholderManager, "%system_load%", 1000, () -> {
                return getSystemLoad();
            });
            
            registerPlaceholder(registerMethod, placeholderManager, "%available_processors%", 60000, () -> {
                return String.valueOf(runtime.availableProcessors());
            });
            
            registerPlaceholder(registerMethod, placeholderManager, "%memory_usage%", 1000, () -> {
                return getMemoryUsage();
            });
            
            registerPlaceholder(registerMethod, placeholderManager, "%free_memory%", 1000, () -> {
                return formatBytes(runtime.freeMemory());
            });
            
            registerPlaceholder(registerMethod, placeholderManager, "%used_memory%", 1000, () -> {
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                return formatBytes(totalMemory - freeMemory);
            });
            
            registerPlaceholder(registerMethod, placeholderManager, "%total_memory%", 1000, () -> {
                return formatBytes(runtime.totalMemory());
            });
            
            registerPlaceholder(registerMethod, placeholderManager, "%max_memory%", 1000, () -> {
                return formatBytes(runtime.maxMemory());
            });
            
            return true;
            
        } catch (Exception e) {
            getLogger().warning("Failed to register placeholders: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取系统CPU使用率 - 使用CPUUsagePlugin的实现方式
     * @return CPU使用率百分比
     */
    private String getCPUUsage() {
        try {
            // 检查是否是com.sun.management.OperatingSystemMXBean
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = 
                    (com.sun.management.OperatingSystemMXBean) osBean;
                
                // 获取进程CPU使用率
                double processCpuLoad = sunOsBean.getProcessCpuLoad() * 100;
                
                // 获取系统CPU使用率
                double systemCpuLoad = sunOsBean.getSystemCpuLoad() * 100;
                
                // 返回系统CPU使用率，如果无效则返回进程CPU使用率
                if (!Double.isNaN(systemCpuLoad) && systemCpuLoad >= 0) {
                    return df.format(systemCpuLoad) + "%";
                } else if (!Double.isNaN(processCpuLoad) && processCpuLoad >= 0) {
                    return df.format(processCpuLoad) + "%";
                }
            }
            
            // 如果无法获取详细CPU信息，尝试获取系统负载
            double systemLoad = osBean.getSystemLoadAverage();
            if (systemLoad >= 0) {
                // 将负载转换为近似的CPU使用率百分比
                int availableProcessors = osBean.getAvailableProcessors();
                double approximateCpuUsage = (systemLoad / availableProcessors) * 100;
                return df.format(Math.min(approximateCpuUsage, 100)) + "%"; // 限制最大100%
            }
        } catch (Exception e) {
            getLogger().warning("获取CPU使用率时出错: " + e.getMessage());
        }
        
        return "0.00%"; // 无法获取
    }
    
    /**
     * 获取系统负载
     */
    private String getSystemLoad() {
        try {
            // 获取系统负载平均值
            double systemLoad = osBean.getSystemLoadAverage();
            if (systemLoad >= 0) {
                return df.format(systemLoad);
            }
            
            // 如果系统负载不可用，使用CPU使用率作为替代
            String cpuUsage = getCPUUsage();
            if (!cpuUsage.equals("0.00%")) {
                // 从CPU使用率中提取数值并转换为负载值
                double cpuValue = Double.parseDouble(cpuUsage.replace("%", "")) / 100;
                double loadValue = cpuValue * runtime.availableProcessors();
                return df.format(loadValue);
            }
            
            return "0.00";
            
        } catch (Exception e) {
            return "0.00";
        }
    }
    
    private String getMemoryUsage() {
        try {
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            double usagePercent = (double) usedMemory / totalMemory * 100;
            return df.format(usagePercent) + "%";
        } catch (Exception e) {
            return "0.00%";
        }
    }
    
    private void registerPlaceholder(Method registerMethod, Object placeholderManager, 
                                    String placeholder, int refreshRate, Supplier<String> supplier) {
        try {
            if (registerMethod.getParameterCount() == 3) {
                // 新版API: registerServerPlaceholder(String, int, Supplier<String>)
                registerMethod.invoke(placeholderManager, placeholder, refreshRate, supplier);
            } else {
                // 旧版API: registerServerPlaceholder(String, Supplier<String>)
                registerMethod.invoke(placeholderManager, placeholder, supplier);
            }
            getLogger().info("Registered placeholder: " + placeholder);
        } catch (Exception e) {
            getLogger().warning("Failed to register placeholder " + placeholder + ": " + e.getMessage());
        }
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return df.format(bytes / Math.pow(1024, exp)) + " " + pre + "B";
    }
    
    @Override
    public void onDisable() {
        getLogger().info("CPU Monitor for TAB plugin disabled!");
    }
}