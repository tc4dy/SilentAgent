package com.privacy.silentagent;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1002;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 1003;
    private static final String PREFS_NAME = "SilentAgentPrefs";
    private static final String PREF_LANGUAGE = "language";
    private static final String PREF_FIRST_RUN = "first_run";
    private static final String PREF_WARNING_SHOWN = "warning_shown";
    private static final String PREF_SECURITY_LEVEL = "security_level";
    private static final String PREF_LAST_SECURITY_CHECK = "last_security_check";
    private static final String PREF_THEME = "theme";
    private static final String GITHUB_URL = "https://github.com/tc4dy";
    
    private com.google.android.material.switchmaterial.SwitchMaterial serviceSwitch;
    private TextView statusText;
    private TextView bottomStatusText;
    private View statusIndicator;
    private Spinner languageSpinner;
    private ImageButton infoButton;
    private View loadingOverlay;
    private SharedPreferences preferences;
    private Handler statusCheckHandler;
    private Runnable statusCheckRunnable;
    private ScheduledExecutorService securityExecutor;
    private boolean isServiceRunning = false;
    private boolean isPermissionDenied = false;
    private boolean isSecurityEnhanced = true;
    private ServiceStateReceiver serviceStateReceiver;
    private long lastSecurityCheck = 0;

    private static final String[] LANGUAGE_CODES = {
        "en", "tr", "de", "fr", "es", "ru", "zh", "ar"
    };

    private static final String[] LANGUAGE_NAMES = {
        "English", "Türkçe", "Deutsch", "Français", 
        "Español", "Русский", "中文", "العربية"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            performSecurityCheck();
            preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            loadLanguagePreference();
            loadThemePreference();
            loadSecurityLevel();

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);
            
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(R.string.app_name);
                getSupportActionBar().setElevation(0);
            }
            
            initializeViews();
            setupLanguageSpinner();
            setupServiceSwitch();
            setupInfoButton();
            setupStatusChecker();
            setupSecurityMonitoring();
            
            if (isFirstRun()) {
                showInitialWarningDialog();
            } else {
                checkPermissionsAndStartService();
            }
            
            registerServiceStateReceiver();
            preventScreenCapture();
            
        } catch (Exception e) {
            handleCriticalError("MainActivity onCreate failed", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            updateServiceStatus();
            startStatusChecking();
            performSecurityCheck();
            validateIntegrity();
        } catch (Exception e) {
            handleSecurityError("Resume security check failed", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            stopStatusChecking();
            saveSecurityState();
        } catch (Exception e) {
            handleSecurityError("Pause security failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            stopStatusChecking();
            unregisterServiceStateReceiver();
            cleanupSecurityResources();
            if (statusCheckHandler != null && statusCheckRunnable != null) {
                statusCheckHandler.removeCallbacks(statusCheckRunnable);
            }
            if (securityExecutor != null && !securityExecutor.isShutdown()) {
                securityExecutor.shutdown();
                try {
                    if (!securityExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                        securityExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    securityExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            handleCriticalError("MainActivity cleanup failed", e);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        try {
            getMenuInflater().inflate(R.menu.main_menu, menu);
            return true;
        } catch (Exception e) {
            handleSecurityError("Menu creation failed", e);
            return false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        try {
            if (item.getItemId() == R.id.action_info) {
                showInfoDialog();
                return true;
            }
        } catch (Exception e) {
            handleSecurityError("Menu selection failed", e);
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        try {
            if (isServiceRunning) {
                showExitConfirmationDialog();
            } else {
                super.onBackPressed();
            }
        } catch (Exception e) {
            handleSecurityError("Back press failed", e);
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        try {
            switch (requestCode) {
                case PERMISSION_REQUEST_CODE:
                    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotificationPermission();
                        } else {
                            updateServiceStatus();
                        }
                    } else {
                        isPermissionDenied = true;
                        updateServiceStatus();
                        showPermissionDeniedDialog();
                        logSecurityEvent("Microphone permission denied");
                    }
                    break;
                    
                case NOTIFICATION_PERMISSION_REQUEST_CODE:
                    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        updateServiceStatus();
                    } else {
                        Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
                        logSecurityEvent("Notification permission denied");
                    }
                    break;
            }
        } catch (Exception e) {
            handleSecurityError("Permission handling failed", e);
        }
    }

    private void initializeViews() {
        try {
            serviceSwitch = findViewById(R.id.service_switch);
            statusText = findViewById(R.id.status_text);
            bottomStatusText = findViewById(R.id.bottom_status_text);
            statusIndicator = findViewById(R.id.status_indicator);
            languageSpinner = findViewById(R.id.language_spinner);
            infoButton = findViewById(R.id.info_button);
            loadingOverlay = findViewById(R.id.loading_overlay);

            if (serviceSwitch == null || statusText == null || languageSpinner == null ||
                bottomStatusText == null || statusIndicator == null || infoButton == null) {
                throw new RuntimeException("Critical UI components not found");
            }
            
            validateUIIntegrity();
            
        } catch (Exception e) {
            handleCriticalError("View initialization failed", e);
        }
    }

    private void setupLanguageSpinner() {
        try {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                LANGUAGE_NAMES
            );
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            languageSpinner.setAdapter(adapter);
            
            String savedLanguage = preferences.getString(PREF_LANGUAGE, "en");
            for (int i = 0; i < LANGUAGE_CODES.length; i++) {
                if (LANGUAGE_CODES[i].equals(savedLanguage)) {
                    languageSpinner.setSelection(i);
                    break;
                }
            }
            
            languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    try {
                        String selectedLanguage = LANGUAGE_CODES[position];
                        String currentLanguage = preferences.getString(PREF_LANGUAGE, "en");
                        
                        if (!selectedLanguage.equals(currentLanguage)) {
                            preferences.edit().putString(PREF_LANGUAGE, selectedLanguage).apply();
                            setLocale(selectedLanguage);
                            logSecurityEvent("Language changed to: " + selectedLanguage);
                            recreate();
                        }
                    } catch (Exception e) {
                        handleSecurityError("Language selection failed", e);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        } catch (Exception e) {
            handleSecurityError("Language spinner setup failed", e);
        }
    }

    private void setupServiceSwitch() {
        try {
            serviceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    try {
                        if (isChecked) {
                            startProtectionService();
                        } else {
                            stopProtectionService();
                        }
                    } catch (Exception e) {
                        handleSecurityError("Service switch operation failed", e);
                        serviceSwitch.setChecked(false);
                    }
                }
            });
        } catch (Exception e) {
            handleSecurityError("Service switch setup failed", e);
        }
    }

    private void setupInfoButton() {
        try {
            infoButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        showInfoDialog();
                    } catch (Exception e) {
                        handleSecurityError("Info dialog failed", e);
                    }
                }
            });
        } catch (Exception e) {
            handleSecurityError("Info button setup failed", e);
        }
    }

    private void setupStatusChecker() {
        try {
            statusCheckHandler = new Handler(Looper.getMainLooper());
            statusCheckRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        updateServiceStatus();
                        performQuickSecurityCheck();
                        statusCheckHandler.postDelayed(this, 2000);
                    } catch (Exception e) {
                        handleSecurityError("Status check failed", e);
                    }
                }
            };
        } catch (Exception e) {
            handleSecurityError("Status checker setup failed", e);
        }
    }

    private void setupSecurityMonitoring() {
        try {
            securityExecutor = Executors.newSingleThreadScheduledExecutor();
            securityExecutor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    try {
                        performDeepSecurityCheck();
                        validateServiceIntegrity();
                    } catch (Exception e) {
                        handleSecurityError("Background security check failed", e);
                    }
                }
            }, 30, 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            handleSecurityError("Security monitoring setup failed", e);
        }
    }

    private void startStatusChecking() {
        try {
            if (statusCheckHandler != null && statusCheckRunnable != null) {
                statusCheckHandler.post(statusCheckRunnable);
            }
        } catch (Exception e) {
            handleSecurityError("Start status checking failed", e);
        }
    }

    private void stopStatusChecking() {
        try {
            if (statusCheckHandler != null && statusCheckRunnable != null) {
                statusCheckHandler.removeCallbacks(statusCheckRunnable);
            }
        } catch (Exception e) {
            handleSecurityError("Stop status checking failed", e);
        }
    }

    private void loadLanguagePreference() {
        try {
            String languageCode = preferences.getString(PREF_LANGUAGE, "en");
            setLocale(languageCode);
        } catch (Exception e) {
            handleSecurityError("Language loading failed", e);
        }
    }

    private void loadThemePreference() {
        try {
            boolean isDark = preferences.getBoolean(PREF_THEME, false);
            AppCompatDelegate.setDefaultNightMode(isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        } catch (Exception e) {
            handleSecurityError("Theme loading failed", e);
        }
    }

    private void loadSecurityLevel() {
        try {
            isSecurityEnhanced = preferences.getBoolean(PREF_SECURITY_LEVEL, true);
        } catch (Exception e) {
            handleSecurityError("Security level loading failed", e);
        }
    }

    private void setLocale(String languageCode) {
        try {
            Locale locale = new Locale(languageCode);
            Locale.setDefault(locale);
            
            Configuration config = new Configuration();
            config.setLocale(locale);
            getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        } catch (Exception e) {
            handleSecurityError("Locale setting failed", e);
        }
    }

private boolean isFirstRun() {
        try {
            return !preferences.getBoolean(PREF_FIRST_RUN, false);
        } catch (Exception e) {
            handleSecurityError("First run check failed", e);
            return false;
        }
    }

    private void markFirstRunCompleted() {
        try {
            preferences.edit().putBoolean(PREF_FIRST_RUN, true).apply();
        } catch (Exception e) {
            handleSecurityError("First run marking failed", e);
        }
    }

    private void showInitialWarningDialog() {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogTheme);
            builder.setTitle(R.string.initial_warning_title)
                   .setMessage(R.string.initial_warning_message)
                   .setPositiveButton(R.string.initial_warning_confirm, new DialogInterface.OnClickListener() {
                       @Override
                       public void onClick(DialogInterface dialog, int which) {
                           try {
                               markFirstRunCompleted();
                               checkPermissionsAndStartService();
                               logSecurityEvent("Initial warning accepted");
                           } catch (Exception e) {
                               handleSecurityError("Initial warning handling failed", e);
                           }
                       }
                   })
                   .setCancelable(true);
            
            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface dialogInterface) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.accent_color));
                }
            });
            dialog.show();
        } catch (Exception e) {
            handleSecurityError("Initial warning dialog failed", e);
        }
    }

    private void checkPermissionsAndStartService() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                    != PackageManager.PERMISSION_GRANTED) {
                requestMicrophonePermission();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                       ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                       != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission();
            } else {
                updateServiceStatus();
            }
        } catch (Exception e) {
            handleSecurityError("Permission check failed", e);
        }
    }

    private void requestMicrophonePermission() {
        try {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST_CODE
            );
            logSecurityEvent("Microphone permission requested");
        } catch (Exception e) {
            handleSecurityError("Microphone permission request failed", e);
        }
    }

    private void requestNotificationPermission() {
        try {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST_CODE
            );
            logSecurityEvent("Notification permission requested");
        } catch (Exception e) {
            handleSecurityError("Notification permission request failed", e);
        }
    }

    private void requestBatteryOptimizationWhitelist() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
                logSecurityEvent("Battery optimization whitelist requested");
            }
        } catch (Exception e) {
            handleSecurityError("Battery optimization request failed", e);
        }
    }

    private void showPermissionDeniedDialog() {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogTheme);
            builder.setTitle(R.string.permission_required_title)
                   .setMessage(R.string.permission_required_message)
                   .setPositiveButton(R.string.permission_grant, new DialogInterface.OnClickListener() {
                       @Override
                       public void onClick(DialogInterface dialog, int which) {
                           try {
                               requestMicrophonePermission();
                           } catch (Exception e) {
                               handleSecurityError("Permission grant dialog failed", e);
                           }
                       }
                   })
                   .setNegativeButton(android.R.string.cancel, null)
                   .setCancelable(false)
                   .show();
        } catch (Exception e) {
            handleSecurityError("Permission denied dialog failed", e);
        }
    }

    private void startProtectionService() {
        try {
            preferences.edit().putBoolean("pending_security_level", isSecurityEnhanced).apply();
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                    != PackageManager.PERMISSION_GRANTED) {
                requestMicrophonePermission();
                serviceSwitch.setChecked(false);
                return;
            }
            
            if (!validateServiceStartConditions()) {
                serviceSwitch.setChecked(false);
                return;
            }
            
            showLoading(true);
            logSecurityEvent("Protection service start requested");
            
            Intent serviceIntent = new Intent(this, MicGuardService.class);
            serviceIntent.putExtra("security_enhanced", isSecurityEnhanced);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            serviceSwitch.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        updateServiceStatus();
                        showLoading(false);
                        validateServiceRunning();
                    } catch (Exception e) {
                        handleSecurityError("Service start validation failed", e);
                    }
                }
            }, 1000);
            
        } catch (Exception e) {
            handleSecurityError("Service start failed", e);
            serviceSwitch.setChecked(false);
            showLoading(false);
        }
    }

    private void stopProtectionService() {
        try {
            logSecurityEvent("Protection service stop requested");
            
            preferences.edit().putBoolean("pending_security_level", isSecurityEnhanced).apply();
            
            Intent serviceIntent = new Intent(this, MicGuardService.class);
            stopService(serviceIntent);
            
            serviceSwitch.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        updateServiceStatus();
                        validateServiceStopped();
                    } catch (Exception e) {
                        handleSecurityError("Service stop validation failed", e);
                    }
                }
            }, 500);
            
        } catch (Exception e) {
            handleSecurityError("Service stop failed", e);
        }
    }

    private void updateServiceStatus() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            boolean isRunning = false;
            
            if (manager != null) {
                for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                    if (MicGuardService.class.getName().equals(service.service.getClassName())) {
                        isRunning = service.foreground;
                        break;
                    }
                }
            }
            
            isServiceRunning = isRunning;
            
            if (serviceSwitch != null) {
                serviceSwitch.setOnCheckedChangeListener(null);
                serviceSwitch.setChecked(isRunning);
                setupServiceSwitch();
            }
            
            updateStatusUI(isRunning);
            updateBottomStatus(isRunning);
            
        } catch (Exception e) {
            handleSecurityError("Service status update failed", e);
        }
    }

    private void updateStatusUI(boolean isRunning) {
        try {
            if (statusText != null) {
                if (isRunning) {
                    statusText.setText(R.string.service_active);
                    statusText.setTextColor(getResources().getColor(R.color.status_active, null));
                } else {
                    statusText.setText(R.string.service_inactive);
                    statusText.setTextColor(getResources().getColor(R.color.status_inactive, null));
                }
            }
        } catch (Exception e) {
            handleSecurityError("Status UI update failed", e);
        }
    }

    private void updateBottomStatus(boolean isRunning) {
        try {
            if (bottomStatusText != null && statusIndicator != null) {
                if (isRunning) {
                    bottomStatusText.setText(R.string.status_protecting);
                    statusIndicator.setBackgroundResource(R.drawable.status_indicator_active);
                } else if (isPermissionDenied) {
                    bottomStatusText.setText(R.string.status_permission_missing);
                    statusIndicator.setBackgroundResource(R.drawable.status_indicator_inactive);
                } else {
                    bottomStatusText.setText(R.string.status_stopped);
                    statusIndicator.setBackgroundResource(R.drawable.status_indicator_inactive);
                }
            }
        } catch (Exception e) {
            handleSecurityError("Bottom status update failed", e);
        }
    }

    private void showLoading(boolean show) {
        try {
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        } catch (Exception e) {
            handleSecurityError("Loading overlay failed", e);
        }
    }

    private void showInfoDialog() {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogTheme);
            builder.setTitle(R.string.info_title)
                   .setMessage(getString(R.string.info_description) + "\n\n" + 
                             getString(R.string.info_developer) + "\n" +
                             getString(R.string.info_github) + "\n\n" +
                             getString(R.string.info_privacy) + "\n\n" +
                             "Version 1.0.3")
                   .setPositiveButton(R.string.info_close, null)
                   .setCancelable(true)
                   .show();
        } catch (Exception e) {
            handleSecurityError("Info dialog failed", e);
        }
    }

    private void showSettingsDialog() {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(50, 50, 50, 50);
            
            TextView silentAgentLabel = new TextView(this);
            silentAgentLabel.setText("Silent Agent");
            silentAgentLabel.setTextSize(20);
            silentAgentLabel.setPadding(0, 0, 0, 30);
            layout.addView(silentAgentLabel);
            
            builder.setTitle(getString(R.string.settings_title))
                   .setView(layout)
                   .setNegativeButton(android.R.string.cancel, null)
                   .setCancelable(true);
            
            AlertDialog dialog = builder.create();
            dialog.show();
            
        } catch (Exception e) {
            handleSecurityError("Settings dialog failed", e);
        }
    }
    private void showExitConfirmationDialog() {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogTheme);
            builder.setTitle("Exit Confirmation")
                   .setMessage("SilentAgent is actively protecting your microphone. Are you sure you want to exit?")
                   .setPositiveButton("Exit", new DialogInterface.OnClickListener() {
                       @Override
                       public void onClick(DialogInterface dialog, int which) {
                           try {
                               stopProtectionService();
                               finish();
                           } catch (Exception e) {
                               handleSecurityError("Exit confirmation failed", e);
                           }
                       }
                   })
                   .setNegativeButton("Cancel", null)
                   .setCancelable(true)
                   .show();
        } catch (Exception e) {
            handleSecurityError("Exit confirmation dialog failed", e);
        }
    }

    private void registerServiceStateReceiver() {
        try {
            serviceStateReceiver = new ServiceStateReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.privacy.silentagent.SERVICE_STATE_CHANGED");
            registerReceiver(serviceStateReceiver, filter);
        } catch (Exception e) {
            handleSecurityError("Service receiver registration failed", e);
        }
    }

    private void unregisterServiceStateReceiver() {
        try {
            if (serviceStateReceiver != null) {
                unregisterReceiver(serviceStateReceiver);
                serviceStateReceiver = null;
            }
        } catch (Exception e) {
            handleSecurityError("Service receiver unregistration failed", e);
        }
    }

    private void performSecurityCheck() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastSecurityCheck < 5000) {
                return;
            }
            
            validateApplicationIntegrity();
            checkForRootDetection();
            validatePermissions();
            lastSecurityCheck = currentTime;
            
        } catch (Exception e) {
            handleSecurityError("Security check failed", e);
        }
    }

    private void performQuickSecurityCheck() {
        try {
            if (!isSecurityEnhanced) return;
            
            validateServiceIntegrity();
            checkMemoryLeaks();
        } catch (Exception e) {
            handleSecurityError("Quick security check failed", e);
        }
    }

    private void performDeepSecurityCheck() {
        try {
            validateApplicationIntegrity();
            checkForTampering();
            validateSystemSecurity();
            checkForMaliciousApps();
        } catch (Exception e) {
            handleSecurityError("Deep security check failed", e);
        }
    }

    private void validateApplicationIntegrity() {
        try {
            String packageName = getPackageName();
            if (!"com.privacy.silentagent".equals(packageName)) {
                throw new SecurityException("Package name tampered");
            }
            
            validateSignature();
            checkCodeIntegrity();
        } catch (Exception e) {
            handleSecurityError("Application integrity validation failed", e);
        }
    }

    private void validateSignature() {
        try {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            }
        } catch (Exception e) {
            handleSecurityError("Signature validation failed", e);
        }
    }

    private void checkCodeIntegrity() {
        try {
            Class.forName("com.privacy.silentagent.MainActivity");
            Class.forName("com.privacy.silentagent.MicGuardService");
        } catch (ClassNotFoundException e) {
            throw new SecurityException("Core classes missing");
        }
    }

    private void validateServiceIntegrity() {
        try {
            if (isServiceRunning) {
                ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (manager != null) {
                    boolean found = false;
                    for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                        if (MicGuardService.class.getName().equals(service.service.getClassName())) {
                            found = true;
                            if (!service.foreground) {
                                logSecurityEvent("Service not running in foreground");
                            }
                            break;
                        }
                    }
                    if (!found) {
                        logSecurityEvent("Service not found in running services");
                    }
                }
            }
        } catch (Exception e) {
            handleSecurityError("Service integrity validation failed", e);
        }
    }

    private void validateIntegrity() {
        try {
            validateApplicationIntegrity();
            validateUIIntegrity();
            validatePermissions();
        } catch (Exception e) {
            handleSecurityError("Integrity validation failed", e);
        }
    }

    private void validateUIIntegrity() {
        try {
            if (serviceSwitch == null || statusText == null || languageSpinner == null) {
                throw new SecurityException("UI components compromised");
            }
        } catch (Exception e) {
            handleSecurityError("UI integrity validation failed", e);
        }
    }

    private void validatePermissions() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                    == PackageManager.PERMISSION_GRANTED) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) 
                        == PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Unexpected internet permission detected");
                }
            }
        } catch (Exception e) {
            handleSecurityError("Permission validation failed", e);
        }
    }

    private void enforceSecurityLevel() {
        try {
            boolean currentLevel = preferences.getBoolean(PREF_SECURITY_LEVEL, true);
            if (currentLevel != isSecurityEnhanced) {
                logSecurityEvent("Security level mismatch detected - enforcing");
                isSecurityEnhanced = currentLevel;
                updateServiceStatus();
            }
            
            if (isSecurityEnhanced) {
                logSecurityEvent("Enhanced security enforcement active");
                performEnhancedSecurityChecks();
            }
        } catch (Exception e) {
            handleSecurityError("Security level enforcement failed", e);
        }
    }

    private void performEnhancedSecurityChecks() {
        try {
            validateApplicationIntegrity();
            validateUIIntegrity();
            validatePermissions();
            checkSecurityState();
        } catch (Exception e) {
            handleSecurityError("Enhanced security checks failed", e);
        }
    }

    private void checkSecurityState() {
        try {
            if (isServiceRunning && isSecurityEnhanced) {
                validateServiceSecurity();
                checkMemoryLeaksEnhanced();
                validateThreadIntegrity();
            }
        } catch (Exception e) {
            handleSecurityError("Security state check failed", e);
        }
    }

    private void validateServiceSecurity() {
        try {
            if (serviceStateReceiver == null) {
                logSecurityEvent("Service state receiver missing - security risk");
            }
        } catch (Exception e) {
            handleSecurityError("Service security validation failed", e);
        }
    }

    private void checkMemoryLeaksEnhanced() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            
            if (usedMemory > maxMemory * 0.8) {
                logSecurityEvent("High memory usage detected: " + (usedMemory / 1024 / 1024) + "MB");
                System.gc();
            }
        } catch (Exception e) {
            handleSecurityError("Memory leak check failed", e);
        }
    }

    private void validateThreadIntegrity() {
        try {
            if (statusCheckHandler == null) {
                logSecurityEvent("Status check handler compromised");
            }
        } catch (Exception e) {
            handleSecurityError("Thread integrity validation failed", e);
        }
    }

    private boolean validateServiceStartConditions() {
        try {
            if (isSecurityEnhanced) {
                validateApplicationIntegrity();
                checkForRootDetection();
                validateSystemSecurity();
                enforceSecurityLevel();
            }
             
            if (isServiceRunning) {
                logSecurityEvent("Service start requested while already running");
                return false;
            }
             
            return true;
        } catch (Exception e) {
            handleSecurityError("Service start conditions validation failed", e);
            return false;
        }
    }

    private void validateServiceRunning() {
        try {
            if (!isServiceRunning) {
                logSecurityEvent("Service failed to start properly");
            }
        } catch (Exception e) {
            handleSecurityError("Service running validation failed", e);
        }
    }

    private void validateServiceStopped() {
        try {
            if (isServiceRunning) {
                logSecurityEvent("Service failed to stop properly");
            }
        } catch (Exception e) {
            handleSecurityError("Service stopped validation failed", e);
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
                    logSecurityEvent("Root detection: " + path);
                    break;
                }
            }
        } catch (Exception e) {
            handleSecurityError("Root detection failed", e);
        }
    }

    private void checkForTampering() {
        try {
            validateApplicationIntegrity();
            checkDebuggingEnabled();
            checkForEmulator();
        } catch (Exception e) {
            handleSecurityError("Tampering check failed", e);
        }
    }

    private void checkDebuggingEnabled() {
        try {
            if (BuildConfig.DEBUG_MODE) {
                logSecurityEvent("Debug mode detected");
            }
        } catch (Exception e) {
            handleSecurityError("Debug check failed", e);
        }
    }

    private void checkForEmulator() {
        try {
            if (Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.toLowerCase().contains("vbox") ||
                Build.FINGERPRINT.toLowerCase().contains("test-keys")) {
                logSecurityEvent("Emulator environment detected");
            }
        } catch (Exception e) {
            handleSecurityError("Emulator check failed", e);
        }
    }

    private void validateSystemSecurity() {
        try {
            checkForMaliciousApps();
            validateSystemIntegrity();
        } catch (Exception e) {
            handleSecurityError("System security validation failed", e);
        }
    }

    private void checkForMaliciousApps() {
        try {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                String[] suspiciousApps = {
                    "com.saurik.substrate",
                    "com.zachspong.temprootremovejb",
                    "com.amphoras.hidemyroot",
                    "com.formyhm.hideroot"
                };
                
                for (String app : suspiciousApps) {
                    try {
                        pm.getPackageInfo(app, 0);
                        logSecurityEvent("Suspicious app detected: " + app);
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                }
            }
        } catch (Exception e) {
            handleSecurityError("Malicious apps check failed", e);
        }
    }

    private void validateSystemIntegrity() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                logSecurityEvent("Old Android version detected");
            }
        } catch (Exception e) {
            handleSecurityError("System integrity validation failed", e);
        }
    }

    private void checkMemoryLeaks() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            
            if (usedMemory > maxMemory * 0.8) {
                logSecurityEvent("High memory usage detected");
            }
        } catch (Exception e) {
            handleSecurityError("Memory leak check failed", e);
        }
    }

    private void preventScreenCapture() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                                     android.view.WindowManager.LayoutParams.FLAG_SECURE);
            }
        } catch (Exception e) {
            handleSecurityError("Screen capture prevention failed", e);
        }
    }

    private void saveSecurityState() {
        try {
            preferences.edit()
                      .putBoolean(PREF_SECURITY_LEVEL, isSecurityEnhanced)
                      .putLong(PREF_LAST_SECURITY_CHECK, lastSecurityCheck)
                      .apply();
        } catch (Exception e) {
            handleSecurityError("Security state saving failed", e);
        }
    }

    private void cleanupSecurityResources() {
        try {
            if (securityExecutor != null && !securityExecutor.isShutdown()) {
                securityExecutor.shutdown();
            }
        } catch (Exception e) {
            handleSecurityError("Security cleanup failed", e);
        }
    }

    private void logSecurityEvent(String event) {
        try {
            android.util.Log.d("SilentAgent_Security", event + " at " + System.currentTimeMillis());
        } catch (Exception e) {
        }
    }

    private void handleSecurityError(String message, Exception e) {
        try {
            logSecurityEvent("SECURITY_ERROR: " + message);
            android.util.Log.e("SilentAgent_Security", message, e);
            
            if (isSecurityEnhanced) {
                isSecurityEnhanced = false;
                updateServiceStatus();
            }
        } catch (Exception ex) {
        }
    }

    private void handleCriticalError(String message, Exception e) {
        try {
            logSecurityEvent("CRITICAL_ERROR: " + message);
            android.util.Log.e("SilentAgent_Critical", message, e);
            
            Toast.makeText(this, "Critical error occurred. Restarting...", Toast.LENGTH_LONG).show();
            
            if (isServiceRunning) {
                stopProtectionService();
            }
            
            finish();
        } catch (Exception ex) {
            finish();
        }
    }

    private class ServiceStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if ("com.privacy.silentagent.SERVICE_STATE_CHANGED".equals(intent.getAction())) {
                    updateServiceStatus();
                }
            } catch (Exception e) {
                handleSecurityError("Service receiver failed", e);
            }
        }
    }
}
