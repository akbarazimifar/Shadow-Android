package su.sres.securesms.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Observer;

import org.conscrypt.Conscrypt;

import su.sres.core.util.ThreadUtil;
import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.core.util.logging.Log;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.net.PipeConnectivityListener;
import su.sres.securesms.push.AccountManagerFactory;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.internal.configuration.ShadowProxy;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShadowProxyUtil {

    private static final String TAG = Log.tag(ShadowProxyUtil.class);

    private static final String PROXY_LINK_HOST = "proxy.shadowprivacy.com";

    private static final Pattern PROXY_LINK_PATTERN = Pattern.compile("^(https|sgnl)://" + PROXY_LINK_HOST + "/#([^:]+).*$");
    private static final Pattern HOST_PATTERN       = Pattern.compile("^([^:]+).*$");

    private ShadowProxyUtil() {}

    public static void startListeningToWebsocket() {
        if (SignalStore.proxy().isProxyEnabled() && ApplicationDependencies.getPipeListener().getState().getValue() == PipeConnectivityListener.State.FAILURE) {
            Log.w(TAG, "Proxy is in a failed state. Restarting.");
            ApplicationDependencies.closeConnections();
        }

        ApplicationDependencies.getIncomingMessageObserver();
    }

    /**
     * Handles all things related to enabling a proxy, including saving it and resetting the relevant
     * network connections.
     */
    public static void enableProxy(@NonNull ShadowProxy proxy) {
        SignalStore.proxy().enableProxy(proxy);
        Conscrypt.setUseEngineSocketByDefault(true);
        ApplicationDependencies.resetNetworkConnectionsAfterProxyChange();
        startListeningToWebsocket();
    }

    /**
     * Handles all things related to disabling a proxy, including saving the change and resetting the
     * relevant network connections.
     */
    public static void disableProxy() {
        SignalStore.proxy().disableProxy();
        Conscrypt.setUseEngineSocketByDefault(false);
        ApplicationDependencies.resetNetworkConnectionsAfterProxyChange();
        startListeningToWebsocket();
    }

    /**
     * A blocking call that will wait until the websocket either successfully connects, or fails.
     * It is assumed that the app state is already configured how you would like it, e.g. you've
     * already configured a proxy if relevant.
     *
     * @return True if the connection is successful within the specified timeout, otherwise false.
     */
    @WorkerThread
    public static boolean testWebsocketConnection(long timeout) {
        startListeningToWebsocket();

        if (TextSecurePreferences.getLocalNumber(ApplicationDependencies.getApplication()) == null) {
            Log.i(TAG, "User is unregistered! Doing simple check.");
            return testWebsocketConnectionUnregistered(timeout);
        }

        CountDownLatch latch   = new CountDownLatch(1);
        AtomicBoolean  success = new AtomicBoolean(false);

        Observer<PipeConnectivityListener.State> observer = state -> {
            if (state == PipeConnectivityListener.State.CONNECTED) {
                success.set(true);
                latch.countDown();
            } else if (state == PipeConnectivityListener.State.FAILURE) {
                success.set(false);
                latch.countDown();
            }
        };

        ThreadUtil.runOnMainSync(() -> ApplicationDependencies.getPipeListener().getState().observeForever(observer));

        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted!", e);
        } finally {
            ThreadUtil.runOnMainSync(() -> ApplicationDependencies.getPipeListener().getState().removeObserver(observer));
        }

        return success.get();
    }

    /**
     * If this is a valid proxy deep link, this will return the embedded host. If not, it will return
     * null.
     */
    public static @Nullable String parseHostFromProxyDeepLink(@Nullable String proxyLink) {
        if (proxyLink == null) {
            return null;
        }

        Matcher matcher = PROXY_LINK_PATTERN.matcher(proxyLink);

        if (matcher.matches()) {
            return matcher.group(2);
        } else {
            return null;
        }
    }

    /**
     * Takes in an address that could be in various formats, and converts it to the format we should
     * be storing and connecting to.
     */
    public static @NonNull String convertUserEnteredAddressToHost(@NonNull String host) {
        String parsedHost = ShadowProxyUtil.parseHostFromProxyDeepLink(host);
        if (parsedHost != null) {
            return parsedHost;
        }

        Matcher matcher = HOST_PATTERN.matcher(host);

        if (matcher.matches()) {
            String result = matcher.group(1);
            return result != null ? result : "";
        } else {
            return host;
        }
    }

    public static @NonNull String generateProxyUrl(@NonNull String link) {
        String host   = link;
        String parsed = parseHostFromProxyDeepLink(link);

        if (parsed != null) {
            host = parsed;
        }

        Matcher matcher = HOST_PATTERN.matcher(host);

        if (matcher.matches()) {
            host = matcher.group(1);
        }

        return "https://" + PROXY_LINK_HOST + "/#" + host;
    }

    private static boolean testWebsocketConnectionUnregistered(long timeout) {
        CountDownLatch              latch          = new CountDownLatch(1);
        AtomicBoolean               success        = new AtomicBoolean(false);
        SignalServiceAccountManager accountManager = AccountManagerFactory.createUnauthenticated(ApplicationDependencies.getApplication(), "", "");

        SignalExecutors.UNBOUNDED.execute(() -> {
            try {
                accountManager.checkNetworkConnection();
                success.set(true);
                latch.countDown();
            } catch (IOException e) {
                latch.countDown();
            }
        });

        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted!", e);
        }

        return success.get();
    }
}
