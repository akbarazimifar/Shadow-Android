package su.sres.securesms.migrations;

import androidx.annotation.NonNull;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobs.MultiDeviceKeysUpdateJob;
import su.sres.securesms.jobs.StorageSyncJob;
import su.sres.core.util.logging.Log;
import su.sres.securesms.util.TextSecurePreferences;

public class StorageServiceMigrationJob extends MigrationJob {

    private static final String TAG = Log.tag(StorageServiceMigrationJob.class);

    public static final String KEY = "StorageServiceMigrationJob";

    StorageServiceMigrationJob() {
        this(new Parameters.Builder().build());
    }

    private StorageServiceMigrationJob(@NonNull Parameters parameters) {
        super(parameters);
    }

    @Override
    public boolean isUiBlocking() {
        return false;
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void performMigration() {
        JobManager jobManager = ApplicationDependencies.getJobManager();

        if (TextSecurePreferences.isMultiDevice(context)) {
            Log.i(TAG, "Multi-device.");
            jobManager.startChain(new StorageSyncJob())
                    .then(new MultiDeviceKeysUpdateJob())
                    .enqueue();
        } else {
            Log.i(TAG, "Single-device.");
            jobManager.add(new StorageSyncJob());
        }
    }

    @Override
    boolean shouldRetry(@NonNull Exception e) {
        return false;
    }

    public static class Factory implements Job.Factory<StorageServiceMigrationJob> {
        @Override
        public @NonNull StorageServiceMigrationJob create(@NonNull Parameters parameters, @NonNull Data data) {
            return new StorageServiceMigrationJob(parameters);
        }
    }
}