package com.winlator.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PerformanceTuner {
    private static final String TAG = "PerformanceTuner";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable rootPerfRunnable;
    private static Runnable nonRootPerfRunnable;
    private static boolean isRootPerfRunning = false;
    private static boolean isNonRootPerfRunning = false;
    private static String cachedMaxFreq = null;
    private static boolean isRootAccessGranted = false;
    private static java.lang.Process persistentSuProcess = null;
    private static DataOutputStream persistentSuOutputStream = null;

    private static boolean isLibraryLoaded = false;

    static {
        try {
            System.loadLibrary("extras");
            isLibraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load extras library: " + e.getMessage());
        }
    }

    private static native void setAdrenoPerformanceModeNative(boolean enabled);

    public static void setAdrenoPerformanceMode(boolean enabled) {
        if (isLibraryLoaded) {
            try {
                setAdrenoPerformanceModeNative(enabled);
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Native method setAdrenoPerformanceModeNative not found");
            }
        }
    }

    public interface RootCheckCallback {
        void onResult(boolean hasRoot);
    }

    public static void checkRootAccessAsync(RootCheckCallback callback) {
        if (isRootAccessGranted) {
            callback.onResult(true);
            return;
        }
        executor.execute(() -> {
            boolean hasRoot = checkRootAccess();
            isRootAccessGranted = hasRoot;
            handler.post(() -> callback.onResult(hasRoot));
        });
    }

    public static boolean checkRootAccess() {
        if (isRootAccessGranted) return true;
        java.lang.Process p = null;
        try {
            // Use a timeout to prevent hanging if root app is unresponsive
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            if (p.waitFor(5, TimeUnit.SECONDS)) {
                boolean hasRoot = p.exitValue() == 0;
                isRootAccessGranted = hasRoot;
                return hasRoot;
            } else {
                p.destroy();
                return false;
            }
        } catch (Exception e) {
            if (p != null) p.destroy();
            return false;
        }
    }

    /**
     * Starts a root-backed performance tuning loop that maintains elevated GPU/CPU settings.
     *
     * If root access is not granted or root tuning is already active, the method returns immediately.
     * Otherwise it marks root tuning active, launches (or reuses) a persistent `su` process, detects the GPU
     * maximum frequency, and schedules a recurring task that re-applies root performance settings every 1000 ms
     * when necessary.
     *
     * The method clears the active flag and aborts setup if starting the persistent `su` process fails.
     */
    public static void startRootPerformanceMode() {
        if (isRootPerfRunning || !isRootAccessGranted) return;
        isRootPerfRunning = true;
        
        executor.execute(() -> {
            try {
                if (persistentSuProcess == null) {
                    persistentSuProcess = Runtime.getRuntime().exec("su");
                    persistentSuOutputStream = new DataOutputStream(persistentSuProcess.getOutputStream());
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to start persistent su process", e);
                isRootPerfRunning = false;
                return;
            }

            // In Context-less environment, we'll try to detect generic values
            detectMaxFrequency();
            
            rootPerfRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isRootPerfRunning) return;
                    
                    executor.execute(() -> {
                        if (shouldReapplyRoot()) {
                            applyRootPerformanceSettings();
                        }
                    });
                    
                    handler.postDelayed(this, 1000);
                }
            };
            handler.post(rootPerfRunnable);
        });
    }

    public static void stopRootPerformanceMode() {
        isRootPerfRunning = false;
        if (rootPerfRunnable != null) {
            handler.removeCallbacks(rootPerfRunnable);
            rootPerfRunnable = null;
        }
        
        if (persistentSuOutputStream != null) {
            try {
                persistentSuOutputStream.writeBytes("exit\n");
                persistentSuOutputStream.flush();
                persistentSuOutputStream.close();
            } catch (IOException e) {}
            persistentSuOutputStream = null;
        }
        if (persistentSuProcess != null) {
            persistentSuProcess.destroy();
            persistentSuProcess = null;
        }
    }

    public static void startNonRootPerformanceMode() {
        if (isNonRootPerfRunning) return;
        isNonRootPerfRunning = true;
        
        nonRootPerfRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isNonRootPerfRunning) return;
                setAdrenoPerformanceMode(true);
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(nonRootPerfRunnable);
    }

    public static void stopNonRootPerformanceMode() {
        isNonRootPerfRunning = false;
        if (nonRootPerfRunnable != null) {
            handler.removeCallbacks(nonRootPerfRunnable);
            nonRootPerfRunnable = null;
        }
        setAdrenoPerformanceMode(false);
    }

    private static boolean shouldReapplyRoot() {
        // For shouldReapplyRoot, we might still need to call su -c cat
        // but let's try reading it directly first if possible (usually not)
        // Optimization: Use a simpler check or skip reapplication if already applied
        String current = readNode("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq");
        if (current == null || cachedMaxFreq == null) return true;
        
        try {
            long curVal = Long.parseLong(current.trim());
            long maxVal = Long.parseLong(cachedMaxFreq.trim());
            return curVal < maxVal;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Detects and caches the GPU's maximum frequency by reading known sysfs nodes.
     *
     * Attempts to read max frequency values from common sysfs paths and stores the
     * first non-empty value in {@code cachedMaxFreq}. If those nodes are absent or
     * empty, it reads the available frequencies list and selects a preferred value:
     * it chooses "1200000000" or "1100000000" if present (to better support Adreno
     * 830/840), otherwise it uses the last frequency in the list. If no valid
     * value is found, {@code cachedMaxFreq} is left unchanged.
     */
    private static void detectMaxFrequency() {
        String[] nodes = {
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/kernel/gpu/gpu_max_clock"
        };
        
        for (String node : nodes) {
            String val = readNode(node);
            if (val != null && !val.isEmpty()) {
                cachedMaxFreq = val.trim();
                return;
            }
        }
        
        String avail = readNode("/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies");
        if (avail != null && !avail.isEmpty()) {
            String[] freqs = avail.trim().split("\\s+");

            // Prefer 1200MHz/1100MHz if it is in the list of available frequencies
            // to support Adreno 830/840 better in case it's misidentified
            for (int i = 0; i < freqs.length; i++) {
                if ("1200000000".equals(freqs[i]) || "1100000000".equals(freqs[i])) {
                    cachedMaxFreq = freqs[i];
                    return;
                }
            }

            cachedMaxFreq = freqs[freqs.length - 1];
        }
    }

    private static String readNode(String path) {
        // This still creates a process. To truly optimize, we'd need to keep a reader open.
        // But let's at least ensure we only call it when necessary.
        java.lang.Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            if (p.waitFor(1, TimeUnit.SECONDS)) {
                return line;
            } else {
                p.destroy();
                return null;
            }
        } catch (Exception e) {
            if (p != null) p.destroy();
            return null;
        }
    }

    private static void applyRootPerformanceSettings() {
        if (persistentSuOutputStream == null) return;
        
        try {
            StringBuilder script = new StringBuilder();
            script.append("for cpu in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo performance > $cpu; done\n");
            
            String[] gpuGovNodes = {
                "/sys/class/kgsl/kgsl-3d0/devfreq/governor",
                "/sys/class/devfreq/kgsl-3d0/governor",
                "/sys/class/devfreq/gpufreq/governor",
                "/sys/class/kgsl/kgsl-3d0/devfreq/adreno_governor"
            };
            for (String node : gpuGovNodes) script.append("echo performance > ").append(node).append(" 2>/dev/null\n");

            script.append("echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null\n");
            script.append("echo 1 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null\n");
            script.append("echo 1 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null\n");
            script.append("echo 1 > /sys/class/kgsl/kgsl-3d0/force_no_nap 2>/dev/null\n");
            
            script.append("echo 0 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null\n");
            script.append("echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null\n");
            script.append("echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null\n");

            if (cachedMaxFreq != null) {
                script.append("echo ").append(cachedMaxFreq).append(" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null\n");
                script.append("echo ").append(cachedMaxFreq).append(" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null\n");
                script.append("echo ").append(cachedMaxFreq).append(" > /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null\n");
                script.append("echo ").append(cachedMaxFreq).append(" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null\n");
            }
            
            persistentSuOutputStream.writeBytes(script.toString());
            persistentSuOutputStream.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply root settings via persistent process", e);
        }
    }
}
