package com.nordicftms.app;

import com.ifit.glassos.ConsoleInfo;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class NordicFtmsStatusStore {
    public interface Listener {
        void onSnapshotChanged(ServiceStatusSnapshot snapshot);
    }

    public interface Updater {
        void update(ServiceStatusSnapshot snapshot);
    }

    private static final NordicFtmsStatusStore INSTANCE = new NordicFtmsStatusStore();

    private final Object lock = new Object();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final ServiceStatusSnapshot snapshot = new ServiceStatusSnapshot();

    private NordicFtmsStatusStore() {
    }

    public static NordicFtmsStatusStore getInstance() {
        return INSTANCE;
    }

    public ServiceStatusSnapshot getSnapshot() {
        synchronized (lock) {
            return new ServiceStatusSnapshot(snapshot);
        }
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        listener.onSnapshotChanged(getSnapshot());
    }

    public void removeListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.remove(listener);
    }

    public void update(Updater updater) {
        if (updater == null) {
            return;
        }

        ServiceStatusSnapshot snapshotCopy;
        synchronized (lock) {
            updater.update(snapshot);
            snapshotCopy = new ServiceStatusSnapshot(snapshot);
        }
        notifyListeners(snapshotCopy);
    }

    public void clearLastError() {
        update(currentSnapshot -> currentSnapshot.lastError = new ServiceStatusSnapshot.LastError());
    }

    void reset() {
        synchronized (lock) {
            snapshot.serviceState = ServiceStartupState.WAITING_FOR_PERMISSION;
            snapshot.bluetoothPermissionState = ServiceStatusSnapshot.BluetoothPermissionState.UNKNOWN;
            snapshot.bleState = ServiceStatusSnapshot.BleState.STOPPED;
            snapshot.backendState = ServiceStatusSnapshot.BackendState.DISCONNECTED;
            snapshot.dirconProfile = ServiceStatusSnapshot.DirconProfile.DISABLED;
            snapshot.advertisedFtmsName = "NordicFTMS";
            snapshot.dirconServiceName = "Unavailable";
            snapshot.consoleSummary = new ServiceStatusSnapshot.ConsoleSummary();
            snapshot.liveMetrics = new ServiceStatusSnapshot.LiveMetrics();
            snapshot.lastError = new ServiceStatusSnapshot.LastError();
            snapshot.reconnectAttempt = 0;
            snapshot.detailedTracingEnabled = false;
            snapshot.kickrRunModeEnabled = true;
        }
    }

    public void setConsoleInfo(ConsoleInfo consoleInfo) {
        update(currentSnapshot -> currentSnapshot.consoleSummary.applyConsoleInfo(consoleInfo));
    }

    public void setLiveMetrics(
            double speedKph,
            double inclinePercent,
            double distanceKm,
            double resistance,
            double cadenceRpm,
            double watts
    ) {
        update(currentSnapshot -> {
            currentSnapshot.liveMetrics.speedKph = speedKph;
            currentSnapshot.liveMetrics.inclinePercent = inclinePercent;
            currentSnapshot.liveMetrics.distanceKm = distanceKm;
            currentSnapshot.liveMetrics.resistance = resistance;
            currentSnapshot.liveMetrics.cadenceRpm = cadenceRpm;
            currentSnapshot.liveMetrics.watts = watts;
        });
    }

    private void notifyListeners(ServiceStatusSnapshot snapshotCopy) {
        for (Listener listener : listeners) {
            listener.onSnapshotChanged(new ServiceStatusSnapshot(snapshotCopy));
        }
    }
}
