package su.sres.securesms.dependencies;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;

import org.greenrobot.eventbus.EventBus;
import su.sres.securesms.BuildConfig;
import su.sres.securesms.IncomingMessageProcessor;
import su.sres.securesms.crypto.storage.SignalProtocolStoreImpl;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.events.ReminderUpdateEvent;
import su.sres.securesms.gcm.MessageRetriever;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobmanager.JobMigrator;
import su.sres.securesms.jobmanager.impl.JsonDataSerializer;
import su.sres.securesms.jobs.FastJobStorage;
import su.sres.securesms.jobs.JobManagerFactories;
import su.sres.securesms.logging.Log;
import su.sres.securesms.push.SecurityEventListener;
import su.sres.securesms.push.SignalServiceNetworkAccess;
import su.sres.securesms.recipients.LiveRecipientCache;
import su.sres.securesms.service.IncomingMessageObserver;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.SignalServiceMessageReceiver;
import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.util.CredentialsProvider;
import su.sres.signalservice.api.util.RealtimeSleepTimer;
import su.sres.signalservice.api.util.SleepTimer;
import su.sres.signalservice.api.util.UptimeSleepTimer;
import su.sres.signalservice.api.websocket.ConnectivityListener;

/**
 * Implementation of {@link ApplicationDependencies.Provider} that provides real app dependencies.
 */
public class ApplicationDependencyProvider implements ApplicationDependencies.Provider {

    private static final String TAG = Log.tag(ApplicationDependencyProvider.class);

    private final Application                context;
    private final SignalServiceNetworkAccess networkAccess;

    public ApplicationDependencyProvider(@NonNull Application context, @NonNull SignalServiceNetworkAccess networkAccess) {
        this.context       = context;
        this.networkAccess = networkAccess;
    }

    @Override
    public @NonNull SignalServiceAccountManager provideSignalServiceAccountManager() {
        return new SignalServiceAccountManager(networkAccess.getConfiguration(context),
                new DynamicCredentialsProvider(context),
                BuildConfig.USER_AGENT);
    }

    @Override
    public @NonNull SignalServiceMessageSender provideSignalServiceMessageSender() {
        return new SignalServiceMessageSender(networkAccess.getConfiguration(context),
                new DynamicCredentialsProvider(context),
                new SignalProtocolStoreImpl(context),
                BuildConfig.USER_AGENT,
                TextSecurePreferences.isMultiDevice(context),
                Optional.fromNullable(IncomingMessageObserver.getPipe()),
                Optional.fromNullable(IncomingMessageObserver.getUnidentifiedPipe()),
                Optional.of(new SecurityEventListener(context)));
    }

    @Override
    public @NonNull SignalServiceMessageReceiver provideSignalServiceMessageReceiver() {
        SleepTimer sleepTimer = TextSecurePreferences.isFcmDisabled(context) ? new RealtimeSleepTimer(context)
                : new UptimeSleepTimer();
        return new SignalServiceMessageReceiver(networkAccess.getConfiguration(context),
                new DynamicCredentialsProvider(context),
                BuildConfig.USER_AGENT,
                new PipeConnectivityListener(),
                sleepTimer);
    }

    @Override
    public @NonNull SignalServiceNetworkAccess provideSignalServiceNetworkAccess() {
        return networkAccess;
    }

    @Override
    public @NonNull IncomingMessageProcessor provideIncomingMessageProcessor() {
        return new IncomingMessageProcessor(context);
    }

    @Override
    public @NonNull MessageRetriever provideMessageRetriever() {
        return new MessageRetriever();
    }

    @Override
    public @NonNull LiveRecipientCache provideRecipientCache() {
        return new LiveRecipientCache(context);
    }

    @Override
    public @NonNull JobManager provideJobManager() {
        return new JobManager(context, new JobManager.Configuration.Builder()
                .setDataSerializer(new JsonDataSerializer())
                .setJobFactories(JobManagerFactories.getJobFactories(context))
                .setConstraintFactories(JobManagerFactories.getConstraintFactories(context))
                .setConstraintObservers(JobManagerFactories.getConstraintObservers(context))
                .setJobStorage(new FastJobStorage(DatabaseFactory.getJobDatabase(context)))
                .setJobMigrator(new JobMigrator(TextSecurePreferences.getJobManagerVersion(context), JobManager.CURRENT_VERSION, JobManagerFactories.getJobMigrations(context)))
                .build());
    }

    private static class DynamicCredentialsProvider implements CredentialsProvider {

        private final Context context;

        private DynamicCredentialsProvider(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public String getUser() {
            return TextSecurePreferences.getLocalNumber(context);
        }

        @Override
        public String getPassword() {
            return TextSecurePreferences.getPushServerPassword(context);
        }

        @Override
        public String getSignalingKey() {
            return TextSecurePreferences.getSignalingKey(context);
        }
    }

    private class PipeConnectivityListener implements ConnectivityListener {

        @Override
        public void onConnected() {
            Log.i(TAG, "onConnected()");
        }

        @Override
        public void onConnecting() {
            Log.i(TAG, "onConnecting()");
        }

        @Override
        public void onDisconnected() {
            Log.w(TAG, "onDisconnected()");
        }

        @Override
        public void onAuthenticationFailure() {
            Log.w(TAG, "onAuthenticationFailure()");
            TextSecurePreferences.setUnauthorizedReceived(context, true);
            EventBus.getDefault().post(new ReminderUpdateEvent());
        }
    }
}