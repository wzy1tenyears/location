package com.familylocation.client;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

final class BackgroundLocationSampler {
    interface Callback {
        void onSample(Location location);
    }

    private static final String TAG = "BackgroundLocation";
    private static final float SETTLED_ACCURACY_METERS = 40f;
    private static final long ACCURATE_FIX_SETTLE_MS = 1500L;

    private final LocationManager locationManager;
    private final Handler handler;
    private final LocationListener listener;
    private final Runnable timeoutRunnable;
    private final Runnable settleRunnable;

    private Callback callback;
    private Location bestLocation;
    private boolean active;

    BackgroundLocationSampler(LocationManager locationManager, Handler handler) {
        this.locationManager = locationManager;
        this.handler = handler;
        this.timeoutRunnable = () -> finish(bestLocation);
        this.settleRunnable = () -> finish(bestLocation);
        this.listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                accept(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                // Required by the API 29 interface.
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
    }

    boolean isActive() {
        return active;
    }

    void sample(boolean fineLocation, long timeoutMs, Callback callback) {
        cancel();
        this.callback = callback;
        this.bestLocation = bestLastKnownLocation(fineLocation);
        this.active = true;

        List<String> providers = enabledProviders(fineLocation);
        boolean requested = false;
        for (String provider : providers) {
            try {
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, handler.getLooper());
                requested = true;
            } catch (Exception exception) {
                Log.w(TAG, "Request " + provider + " sample failed: " + exception.getMessage());
            }
        }

        if (!requested) {
            finish(bestLocation);
            return;
        }
        handler.postDelayed(timeoutRunnable, Math.max(1000L, timeoutMs));
        if (isGoodFix(bestLocation)) {
            handler.postDelayed(settleRunnable, ACCURATE_FIX_SETTLE_MS);
        }
    }

    void cancel() {
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(settleRunnable);
        if (active) {
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception ignored) {
                // Best effort cleanup.
            }
        }
        active = false;
        callback = null;
        bestLocation = null;
    }

    private void accept(Location location) {
        if (!active || !isAcceptableFix(location)) {
            return;
        }
        if (isBetter(location, bestLocation)) {
            bestLocation = new Location(location);
        }
        if (isGoodFix(bestLocation)) {
            handler.removeCallbacks(settleRunnable);
            handler.postDelayed(settleRunnable, ACCURATE_FIX_SETTLE_MS);
        }
    }

    private void finish(Location location) {
        if (!active) {
            return;
        }
        Callback completed = callback;
        Location result = location == null ? null : new Location(location);
        cancel();
        if (completed != null) {
            completed.onSample(result);
        }
    }

    private List<String> enabledProviders(boolean fineLocation) {
        List<String> providers = new ArrayList<>();
        if (fineLocation && enabled(LocationManager.GPS_PROVIDER)) {
            providers.add(LocationManager.GPS_PROVIDER);
        }
        return providers;
    }

    private Location bestLastKnownLocation(boolean fineLocation) {
        Location best = null;
        for (String provider : enabledProviders(fineLocation)) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (isAcceptableFix(candidate) && isBetter(candidate, best)) {
                    best = candidate;
                }
            } catch (Exception ignored) {
                // Disabled and vendor-specific providers are ignored.
            }
        }
        return best == null ? null : new Location(best);
    }

    private boolean enabled(String provider) {
        try {
            return locationManager != null && locationManager.isProviderEnabled(provider);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isGoodFix(Location location) {
        return isAcceptableFix(location) && location.getAccuracy() <= SETTLED_ACCURACY_METERS;
    }

    private boolean isAcceptableFix(Location location) {
        return location != null && LocationFixPolicy.isAcceptable(
            location.getProvider(),
            location.getTime(),
            System.currentTimeMillis(),
            location.hasAccuracy(),
            location.hasAccuracy() ? location.getAccuracy() : 0f,
            location.isFromMockProvider()
        );
    }

    private boolean isBetter(Location candidate, Location current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        long timeDelta = candidate.getTime() - current.getTime();
        if (timeDelta > 120_000L) {
            return true;
        }
        if (timeDelta < -120_000L) {
            return false;
        }
        if (!candidate.hasAccuracy()) {
            return false;
        }
        return !current.hasAccuracy() || candidate.getAccuracy() < current.getAccuracy();
    }
}
