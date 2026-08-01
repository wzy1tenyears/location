package com.familylocation.client;

import android.app.Activity;
import android.os.Build;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.util.ArrayList;
import java.util.List;

final class FrontendRuntimeController {
    private final Activity activity;
    private final List<WebView> managedWebViews = new ArrayList<>();
    private volatile boolean foreground;

    FrontendRuntimeController(Activity activity) {
        this.activity = activity;
    }

    void onResume() {
        foreground = true;
    }

    void onStop() {
        foreground = false;
        destroyManagedWebViews();
    }

    boolean canLoadForegroundWebView() {
        return foreground
            && !activity.isFinishing()
            && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed());
    }

    WebView createManagedWebView() {
        WebView webView = new WebView(activity);
        managedWebViews.add(webView);
        return webView;
    }

    void destroyManagedWebView(WebView webView) {
        if (webView == null) {
            return;
        }
        managedWebViews.remove(webView);
        try {
            ViewGroup parent = webView.getParent() instanceof ViewGroup ? (ViewGroup) webView.getParent() : null;
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.clearCache(false);
            webView.removeAllViews();
            webView.destroy();
        } catch (Exception ignored) {
            // A renderer crash or detached view can make WebView teardown throw.
        }
    }

    void destroyManagedWebViews() {
        List<WebView> views = new ArrayList<>(managedWebViews);
        for (WebView webView : views) {
            destroyManagedWebView(webView);
        }
        managedWebViews.clear();
    }
}
