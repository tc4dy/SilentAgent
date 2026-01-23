package com.privacy.silentagent;

import android.app.Notification;
import android.Manifest;
import android.content.pm.PackageManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

public class MicGuardService extends Service {

    private static final String CHANNEL_ID = "mic_guard_channel";
    private static final String ALERT_CHANNEL_ID = "mic_alert_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int ALERT_NOTIFICATION_ID = 1002;
    
    private static final int LOW_RATE = 8000;
    private static final int HIGH_RATE = 44100;
    private static final int STABILIZATION_DURATION = 10000;
    private static final int CRITICAL_WAKELOCK_DURATION = 30000;
    private static final int LOW_POWER_SLEEP = 15;
    private static final int HIGH_POWER_SLEEP = 5;
    private static final int RETRY_DELAY = 2000;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final int SECURITY_CHECK_INTERVAL = 30000;
    private static final int MAX_MEMORY_USAGE = 50 * 1024 * 1024;
    
    private AudioRecord recorder;
    private Thread micThread;
    private Thread securityThread;
    private volatile boolean running = false;
    private volatile boolean isHighPowerMode = false;
    private volatile int currentRate = LOW_RATE;
    private volatile int consecutiveErrors = 0;
    private volatile long lastSecurityCheck = 0;
    private volatile boolean isSecurityCompromised = false;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    private Handler handler;
    private Runnable powerDownRunnable;
    private AudioManager audioManager;
    private Object recorderLock = new Object();
    private byte[] audioBuffer;
    private long serviceStartTime;
    private int totalErrors = 0;
    private int securityViolations = 0;
    private volatile boolean isSecurityEnhanced = true;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceStartTime = System.currentTimeMillis();
        audioBuffer = new byte[1024];
        
        try {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            handler = new Handler(Looper.getMainLooper());
            
            createNotificationChannels();
            initializeWakeLock();
            performServiceSecurityCheck();
            logServiceEvent("Service created successfully");
        } catch (Exception e) {
            handleCriticalServiceError("Service creation failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && intent.hasExtra("security_enhanced")) {
                isSecurityEnhanced = intent.getBooleanExtra("security_enhanced", true);
                logServiceEvent("Security level set to: " + (isSecurityEnhanced ? "enhanced" : "standard"));
            } else {
                SharedPreferences prefs = getSharedPreferences("SilentAgentPrefs", MODE_PRIVATE);
                boolean savedLevel = prefs.getBoolean("security_level", true);
                boolean pendingLevel = prefs.getBoolean("pending_security_level", savedLevel);
                if (pendingLevel != isSecurityEnhanced) {
                    isSecurityEnhanced = pendingLevel;
                    logServiceEvent("Security level restored from pending: " + (isSecurityEnhanced ? "enhanced" : "standard"));
                }
            }
            
            if (!running) {
                validateStartConditions();
                startForegroundService();
                startMicrophoneProtection();
                startSecurityMonitoring();
                logServiceEvent("Service started successfully");
            } else {
                logServiceEvent("Service start requested while already running");
            }
            return START_STICKY;
        } catch (Exception e) {
            handleCriticalServiceError("Service start failed", e);
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        try {
            logServiceEvent("Service destroy requested");
            running = false;
            
            stopMicrophoneProtection();
            stopSecurityMonitoring();
            releaseWakeLock();
            cleanupResources();
            
            if (handler != null && powerDownRunnable != null) {
                handler.removeCallbacks(powerDownRunnable);
            }
            
            logServiceEvent("Service destroyed successfully");
            super.onDestroy();
        } catch (Exception e) {
            handleCriticalServiceError("Service destroy failed", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            logServiceEvent("Task removed, attempting restart");
            
            Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
            restartServiceIntent.setPackage(getPackageName());
            restartServiceIntent.putExtra("security_enhanced", isSecurityEnhanced);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(restartServiceIntent);
            } else {
                getApplicationContext().startService(restartServiceIntent);
            }
            super.onTaskRemoved(rootIntent);
        } catch (Exception e) {
            handleCriticalServiceError("Task restart failed", e);
        }
    }

    private void createNotificationChannels() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                );
                serviceChannel.setDescription(getString(R.string.notification_channel_desc));
                serviceChannel.setShowBadge(false);
                serviceChannel.setSound(null, null);
                serviceChannel.enableVibration(false);
                serviceChannel.enableLights(false);
                serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                
                NotificationChannel alertChannel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    getString(R.string.notification_alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                );
                alertChannel.setDescription(getString(R.string.notification_alert_channel_desc));
                alertChannel.setShowBadge(true);
                alertChannel.enableVibration(true);
                alertChannel.enableLights(true);
                alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                
                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(serviceChannel);
                    notificationManager.createNotificationChannel(alertChannel);
                }
            }
        } catch (Exception e) {
            handleServiceError("Notification channel creation failed", e);
        }
    }

    private void startForegroundService() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M 
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT
            );

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    Class<?> serviceClass = Service.class;
                    int behaviorConstant = serviceClass.getDeclaredField("FOREGROUND_SERVICE_IMMEDIATE").getInt(null);
                    serviceClass.getMethod("setForegroundServiceBehavior", int.class).invoke(this, behaviorConstant);
                } catch (Exception e) {
                    logServiceEvent("Foreground service behavior setting failed");
                }
            }
        } catch (Exception e) {
            handleCriticalServiceError("Foreground service start failed", e);
        }
    }

    private void initializeWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "SilentAgent:MicGuard"
                    );
                    wakeLock.setReferenceCounted(false);
                }
            }
        } catch (Exception e) {
            handleServiceError("WakeLock initialization failed", e);
        }
    }

    private void acquireTempWakeLock(long durationMs) {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(durationMs);
                logServiceEvent("WakeLock acquired for " + durationMs + "ms");
            }
        } catch (Exception e) {
            handleServiceError("WakeLock acquisition failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                logServiceEvent("WakeLock released");
            }
        } catch (Exception e) {
            handleServiceError("WakeLock release failed", e);
        }
    }

    private void startMicrophoneProtection() {
        try {
            running = true;
            acquireTempWakeLock(STABILIZATION_DURATION);
            
            micThread = new Thread(() -> {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                    
                    if (!initializeRecorder(currentRate)) {
                        handleMicError(-1);
                        return;
                    }
                    
                    micLoop();
                } catch (Exception e) {
                    handleCriticalServiceError("Microphone thread failed", e);
                }
            });
            
            micThread.start();
            logServiceEvent("Microphone protection started");
        } catch (Exception e) {
            handleCriticalServiceError("Microphone protection start failed", e);
        }
    }

    private void stopMicrophoneProtection() {
        try {
            running = false;
            
            synchronized (recorderLock) {
                if (recorder != null) {
                    try {
                        if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            recorder.stop();
                        }
                        recorder.release();
                    } catch (Exception e) {
                        logServiceEvent("Recorder cleanup failed");
                    } finally {
                        recorder = null;
                    }
                }
            }
            
            if (micThread != null) {
                try {
                    micThread.interrupt();
                    micThread.join(1000);
                } catch (InterruptedException e) {
                    micThread.interrupt();
                    Thread.currentThread().interrupt();
                }
            }
            
            logServiceEvent("Microphone protection stopped");
        } catch (Exception e) {
            handleServiceError("Microphone protection stop failed", e);
        }
    }

    private void startSecurityMonitoring() {
        try {
            securityThread = new Thread(() -> {
                while (running) {
                    try {
                        performSecurityCheck();
                        checkMemoryUsage();
                        validateServiceIntegrity();
                        Thread.sleep(SECURITY_CHECK_INTERVAL);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        handleServiceError("Security monitoring failed", e);
                    }
                }
            });
            
            securityThread.start();
            logServiceEvent("Security monitoring started");
        } catch (Exception e) {
            handleServiceError("Security monitoring start failed", e);
        }
    }

    private void stopSecurityMonitoring() {
        try {
            if (securityThread != null) {
                securityThread.interrupt();
                try {
                    securityThread.join(1000);
                } catch (InterruptedException e) {
                    securityThread.interrupt();
                    Thread.currentThread().interrupt();
                }
            }
            logServiceEvent("Security monitoring stopped");
        } catch (Exception e) {
            handleServiceError("Security monitoring stop failed", e);
        }
    }

    private boolean initializeRecorder(int sampleRate) {
        synchronized (recorderLock) {
            try {
                if (recorder != null) {
                    recorder.release();
                    recorder = null;
                }
                
                int bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                );
                
                if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                    return false;
                }
                
                recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                );
                
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    recorder.release();
                    recorder = null;
                    return false;
                }
                
                recorder.startRecording();
                logServiceEvent("Recorder initialized at " + sampleRate + "Hz");
                return true;
                
            } catch (Exception e) {
                handleServiceError("Recorder initialization failed", e);
                if (recorder != null) {
                    recorder.release();
                    recorder = null;
                }
                return false;
            }
        }
    }

    private void micLoop() {
        while (running) {
            synchronized (recorderLock) {
                if (recorder == null || recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    if (!initializeRecorder(currentRate)) {
                        handleMicError(-1);
                        break;
                    }
                    continue;
                }
                
                int read = recorder.read(audioBuffer, 0, audioBuffer.length);
                
                if (read < 0) {
                    handleMicError(read);
                    break;
                }
                
                consecutiveErrors = 0;
                
                if (isSecurityCompromised) {
                    zeroAudioBuffer();
                }
            }
            
            try {
                int sleepTime = isHighPowerMode ? HIGH_POWER_SLEEP : LOW_POWER_SLEEP;
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                handleServiceError("MicLoop sleep failed", e);
            }
        }
    }

    private void zeroAudioBuffer() {
        try {
            java.util.Arrays.fill(audioBuffer, (byte) 0);
        } catch (Exception e) {
            logServiceEvent("Audio buffer zeroing failed");
        }
    }

    private void handleMicError(int errorCode) {
        try {
            consecutiveErrors++;
            totalErrors++;

            logServiceEvent("Microphone error: " + errorCode + " (consecutive: " + consecutiveErrors + ")");

            if (consecutiveErrors == 1) {
                acquireTempWakeLock(CRITICAL_WAKELOCK_DURATION);
            }

            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                switchToHighPowerMode();
                acquireTempWakeLock(CRITICAL_WAKELOCK_DURATION);

                try {
                    Thread.sleep(RETRY_DELAY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                consecutiveErrors = 0;
            }
        } catch (Exception e) {
            handleServiceError("Microphone error handling failed", e);
        }
    }

    private void switchToHighPowerMode() {
        try {
            if (isHighPowerMode) return;
            
            isHighPowerMode = true;
            currentRate = HIGH_RATE;
            
            logServiceEvent("Switching to high power mode");
            
            synchronized (recorderLock) {
                if (recorder != null) {
                    try {
                        if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            recorder.stop();
                        }
                        recorder.release();
                    } catch (Exception e) {
                        logServiceEvent("Recorder stop failed during mode switch");
                    }
                    recorder = null;
                }
            }
            
            if (!initializeRecorder(HIGH_RATE)) {
                initializeRecorder(LOW_RATE);
                currentRate = LOW_RATE;
            }
            
            if (powerDownRunnable != null) {
                handler.removeCallbacks(powerDownRunnable);
            }
            
            powerDownRunnable = new Runnable() {
                @Override
                public void run() {
                    switchToLowPowerMode();
                }
            };
            
            handler.postDelayed(powerDownRunnable, STABILIZATION_DURATION);
        } catch (Exception e) {
            handleServiceError("High power mode switch failed", e);
        }
    }

    private void switchToLowPowerMode() {
        try {
            if (!isHighPowerMode) return;
            
            isHighPowerMode = false;
            currentRate = LOW_RATE;
            
            logServiceEvent("Switching to low power mode");
            
            synchronized (recorderLock) {
                if (recorder != null) {
                    try {
                        if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            recorder.stop();
                        }
                        recorder.release();
                    } catch (Exception e) {
                        logServiceEvent("Recorder stop failed during mode switch");
                    }
                    recorder = null;
                }
            }
            
            initializeRecorder(LOW_RATE);
            releaseWakeLock();
        } catch (Exception e) {
            handleServiceError("Low power mode switch failed", e);
        }
    }

    private void sendHighPriorityNotification() {
        logServiceEvent("High priority notification suppressed");
    }

    private void performServiceSecurityCheck() {
        try {
            validateServiceEnvironment();
            checkForTampering();
            validateAudioPermissions();
        } catch (Exception e) {
            handleServiceError("Service security check failed", e);
        }
    }

    private void performSecurityCheck() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastSecurityCheck < SECURITY_CHECK_INTERVAL) {
                return;
            }
            
            validateServiceIntegrity();
            checkForInterference();
            validateAudioHardware();
            lastSecurityCheck = currentTime;
            
        } catch (Exception e) {
            handleServiceError("Security check failed", e);
        }
    }

    private void validateServiceEnvironment() {
        try {
            String packageName = getPackageName();
            if (!"com.privacy.silentagent".equals(packageName)) {
                throw new SecurityException("Invalid package name");
            }
            
            if (BuildConfig.DEBUG_MODE) {
                logServiceEvent("Running in debug mode");
            }
        } catch (Exception e) {
            handleServiceError("Service environment validation failed", e);
        }
    }

    private void checkForTampering() {
        try {
            if (Build.FINGERPRINT.contains("test-keys")) {
                logServiceEvent("Test keys detected - possible tampering");
            }
        } catch (Exception e) {
            handleServiceError("Tampering check failed", e);
        }
    }

    private void validateAudioPermissions() {
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Audio permission missing");
            }
        } catch (Exception e) {
            handleServiceError("Audio permission validation failed", e);
        }
    }

    private void validateServiceIntegrity() {
        try {
            if (recorder != null && recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                logServiceEvent("Recorder integrity compromised");
            }
        } catch (Exception e) {
            handleServiceError("Service integrity validation failed", e);
        }
    }

    private void checkForInterference() {
        try {
            if (audioManager != null) {
                int mode = audioManager.getMode();
                if (mode != AudioManager.MODE_NORMAL) {
                    logServiceEvent("Audio mode changed: " + mode);
                }
            }
        } catch (Exception e) {
            handleServiceError("Interference check failed", e);
        }
    }

    private void validateAudioHardware() {
        try {
            if (audioManager != null) {
                if (!audioManager.isWiredHeadsetOn() && !audioManager.isBluetoothA2dpOn()) {
                logServiceEvent("No audio output devices detected");
                isSecurityCompromised = true;
                securityViolations++;
                }
            }
        } catch (Exception e) {
            handleServiceError("Audio hardware validation failed", e);
        }
    }

    private void checkMemoryUsage() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            
            if (usedMemory > MAX_MEMORY_USAGE) {
                logServiceEvent("High memory usage: " + (usedMemory / 1024 / 1024) + "MB");
                
                if (usedMemory > MAX_MEMORY_USAGE * 1.5) {
                    logServiceEvent("Critical memory usage, forcing cleanup");
                    System.gc();
                }
            }
        } catch (Exception e) {
            handleServiceError("Memory usage check failed", e);
        }
    }

    private void validateStartConditions() {
        try {
            performServiceSecurityCheck();
            
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Audio permission required");
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Notification permission required");
            }
            
            if (isSecurityEnhanced) {
                logServiceEvent("Enhanced security mode activated - enforcing strict validation");
                validateApplicationIntegrity();
                checkForRootDetection();
                validateSystemSecurity();
            }
        } catch (Exception e) {
            throw new SecurityException("Start conditions not met: " + e.getMessage());
        }
    }

    private void validateApplicationIntegrity() {
        try {
            String packageName = getPackageName();
            if (!"com.privacy.silentagent".equals(packageName)) {
                throw new SecurityException("Package integrity compromised");
            }
            
            if (BuildConfig.DEBUG_MODE) {
                logServiceEvent("Debug mode detected - enhanced security warning");
            }
        } catch (Exception e) {
            logServiceEvent("Application integrity validation failed");
        }
    }

    private void checkForRootDetection() {
        try {
            String[] paths = {
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su"
            };
            
            for (String path : paths) {
                if (new java.io.File(path).exists()) {
                    logServiceEvent("Root access detected at: " + path);
                    break;
                }
            }
        } catch (Exception e) {
            logServiceEvent("Root detection failed");
        }
    }

    private void validateSystemSecurity() {
        try {
            if (Build.FINGERPRINT.contains("test-keys")) {
                logServiceEvent("Test build detected - security risk");
            }
            
            if (Build.TAGS != null && Build.TAGS.contains("release-keys")) {
                logServiceEvent("Release build verified");
            }
        } catch (Exception e) {
            logServiceEvent("System security validation failed");
        }
    }

    private void cleanupResources() {
        try {
            if (audioBuffer != null) {
                audioBuffer = null;
            }
            
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
            
            if (micThread != null) {
                micThread = null;
            }
            
            if (securityThread != null) {
                securityThread = null;
            }
            
            logServiceEvent("Resources cleaned up");
        } catch (Exception e) {
            logServiceEvent("Resource cleanup failed");
        }
    }

    private void logServiceEvent(String event) {
        try {
            long uptime = System.currentTimeMillis() - serviceStartTime;
            android.util.Log.d("SilentAgent_Service", "[" + uptime + "ms] " + event);
        } catch (Exception e) {
        }
    }

    private void handleServiceError(String message, Exception e) {
        try {
            logServiceEvent("ERROR: " + message);
            android.util.Log.e("SilentAgent_Service", message, e);
        } catch (Exception ex) {
        }
    }

    private void handleCriticalServiceError(String message, Exception e) {
        try {
            logServiceEvent("CRITICAL_ERROR: " + message);
            android.util.Log.e("SilentAgent_Service", message, e);
            
            running = false;
            stopSelf();
        } catch (Exception ex) {
            stopSelf();
        }
    }
}