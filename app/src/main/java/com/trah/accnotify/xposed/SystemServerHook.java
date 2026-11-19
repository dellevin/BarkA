package com.trah.accnotify.xposed;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;

import com.trah.accnotify.bridge.IAccnotifyBridge;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SystemServerHook implements IXposedHookLoadPackage {
    private static final String TAG = "AccnotifyXposed";
    private static final String APP_PACKAGE = "com.trah.accnotify";
    private static final String BRIDGE_CLASS = APP_PACKAGE + ".bridge.SystemServerBridgeService";
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!"android".equals(loadPackageParam.packageName)
                || Process.myUid() != Process.SYSTEM_UID) {
            return;
        }

        try {
            Class<?> activityManagerService = XposedHelpers.findClassIfExists(
                    "com.android.server.am.ActivityManagerService",
                    loadPackageParam.classLoader
            );
            if (activityManagerService == null) {
                log("ActivityManagerService not found");
                return;
            }

            Set<?> hooks = XposedBridge.hookAllMethods(
                    activityManagerService,
                    "systemReady",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || !INITIALIZED.compareAndSet(false, true)) {
                                return;
                            }
                            try {
                                Context context = (Context) XposedHelpers.getObjectField(
                                        param.thisObject,
                                        "mContext"
                                );
                                new BridgeBindingController(context).start();
                            } catch (Throwable t) {
                                INITIALIZED.set(false);
                                log("Failed to initialize bridge binding", t);
                            }
                        }
                    }
            );
            if (hooks.isEmpty()) {
                log("ActivityManagerService.systemReady hook not installed");
            }
        } catch (Throwable t) {
            log("Failed to install system_server hook", t);
        }
    }

    private static final class BridgeBindingController {
        private static final long INITIAL_DELAY_MS = 5_000L;
        private static final long MAX_RETRY_DELAY_MS = 60_000L;

        private final Context context;
        private final HandlerThread thread = new HandlerThread("AccnotifyBridge") {
            @Override
            protected void onLooperPrepared() {
                try {
                    handler = new Handler(getLooper());
                    handler.postDelayed(
                            BridgeBindingController.this::bindIfNeeded,
                            INITIAL_DELAY_MS
                    );
                } catch (Throwable t) {
                    log("Failed to prepare bridge binding thread", t);
                }
            }
        };
        private Handler handler;
        private ServiceConnection activeConnection;
        private IBinder activeBinder;
        private IBinder.DeathRecipient deathRecipient;
        private boolean binding;
        private boolean bound;
        private boolean bindingRegistered;
        private boolean retryScheduled;
        private int retryAttempt;
        private long generation;

        BridgeBindingController(Context context) {
            this.context = context;
        }

        void start() {
            try {
                thread.start();
            } catch (Throwable t) {
                log("Failed to start bridge binding thread", t);
            }
        }

        private void bindIfNeeded() {
            retryScheduled = false;
            if (binding || bound) {
                return;
            }

            final long attemptGeneration = ++generation;
            final ServiceConnection connection = createConnection(attemptGeneration);
            activeConnection = connection;
            binding = true;

            try {
                Intent intent = new Intent().setComponent(
                        new ComponentName(APP_PACKAGE, BRIDGE_CLASS)
                );
                bindingRegistered = context.bindService(
                        intent,
                        connection,
                        Context.BIND_AUTO_CREATE
                );
                if (!bindingRegistered) {
                    binding = false;
                    activeConnection = null;
                    scheduleRetry("bindService returned false");
                }
            } catch (Throwable t) {
                binding = false;
                bindingRegistered = false;
                activeConnection = null;
                log("bindService failed", t);
                scheduleRetry("bindService exception");
            }
        }

        private ServiceConnection createConnection(long connectionGeneration) {
            return new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    post(() -> handleConnected(connectionGeneration, this, service));
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    post(() -> handleLost(connectionGeneration, this, "service disconnected"));
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    post(() -> handleLost(connectionGeneration, this, "binding died"));
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    post(() -> handleLost(connectionGeneration, this, "null binding"));
                }
            };
        }

        private void handleConnected(
                long connectionGeneration,
                ServiceConnection connection,
                IBinder binder
        ) {
            if (!isCurrent(connectionGeneration, connection)) {
                return;
            }

            binding = false;
            bound = true;
            activeBinder = binder;
            deathRecipient = () -> post(
                    () -> handleLost(connectionGeneration, connection, "binder died")
            );

            try {
                binder.linkToDeath(deathRecipient, 0);
                IAccnotifyBridge bridge = IAccnotifyBridge.Stub.asInterface(binder);
                bridge.ensureWebSocketRunning();
                retryAttempt = 0;
                log("system_server bridge connected");
            } catch (Throwable t) {
                log("Bridge handshake failed", t);
                handleLost(connectionGeneration, connection, "handshake failed");
            }
        }

        private void handleLost(
                long connectionGeneration,
                ServiceConnection connection,
                String reason
        ) {
            if (!isCurrent(connectionGeneration, connection)) {
                return;
            }
            cleanupCurrentConnection();
            scheduleRetry(reason);
        }

        private boolean isCurrent(long connectionGeneration, ServiceConnection connection) {
            return generation == connectionGeneration && activeConnection == connection;
        }

        private void cleanupCurrentConnection() {
            IBinder binder = activeBinder;
            IBinder.DeathRecipient recipient = deathRecipient;
            if (binder != null && recipient != null) {
                try {
                    binder.unlinkToDeath(recipient, 0);
                } catch (Throwable ignored) {
                }
            }

            ServiceConnection connection = activeConnection;
            if (bindingRegistered && connection != null) {
                try {
                    context.unbindService(connection);
                } catch (Throwable t) {
                    log("unbindService failed", t);
                }
            }

            activeBinder = null;
            deathRecipient = null;
            activeConnection = null;
            binding = false;
            bound = false;
            bindingRegistered = false;
        }

        private void scheduleRetry(String reason) {
            if (retryScheduled || binding || bound) {
                return;
            }
            long delay = Math.min(1_000L << Math.min(retryAttempt, 6), MAX_RETRY_DELAY_MS);
            retryAttempt++;
            retryScheduled = true;
            log("Scheduling bridge rebind in " + delay + " ms: " + reason);
            handler.postDelayed(this::bindIfNeeded, delay);
        }

        private void post(Runnable action) {
            Handler currentHandler = handler;
            if (currentHandler == null) {
                return;
            }
            try {
                currentHandler.post(() -> {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        log("Bridge callback failed", t);
                    }
                });
            } catch (Throwable t) {
                log("Failed to post bridge callback", t);
            }
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(String message, Throwable throwable) {
        XposedBridge.log(TAG + ": " + message + ": " + throwable);
    }
}
