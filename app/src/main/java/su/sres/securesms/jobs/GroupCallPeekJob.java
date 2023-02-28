package su.sres.securesms.jobs;

import androidx.annotation.NonNull;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobmanager.impl.DecryptionsDrainedConstraint;
import su.sres.securesms.recipients.RecipientId;

/**
 * Allows the enqueueing of one peek operation per group while the web socket is not drained.
 */
public final class GroupCallPeekJob extends BaseJob {

    public static final String KEY = "GroupCallPeekJob";

    private static final String QUEUE = "__GroupCallPeekJob__";

    private static final String KEY_GROUP_RECIPIENT_ID = "group_recipient_id";

    @NonNull private final RecipientId groupRecipientId;

    public static void enqueue(@NonNull RecipientId groupRecipientId) {
        JobManager         jobManager = ApplicationDependencies.getJobManager();
        String             queue      = QUEUE + groupRecipientId.serialize();
        Parameters.Builder parameters = new Parameters.Builder()
                .setQueue(queue)
                .addConstraint(DecryptionsDrainedConstraint.KEY);

        jobManager.cancelAllInQueue(queue);

        jobManager.add(new GroupCallPeekJob(parameters.build(), groupRecipientId));
    }

    private GroupCallPeekJob(@NonNull Parameters parameters,
                             @NonNull RecipientId groupRecipientId)
    {
        super(parameters);
        this.groupRecipientId = groupRecipientId;
    }

    @Override
    protected void onRun() {
        ApplicationDependencies.getJobManager().add(new GroupCallPeekWorkerJob(groupRecipientId));
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception e) {
        return false;
    }

    @Override
    public @NonNull Data serialize() {
        return new Data.Builder()
                .putString(KEY_GROUP_RECIPIENT_ID, groupRecipientId.serialize())
                .build();
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onFailure() {
    }

    public static final class Factory implements Job.Factory<GroupCallPeekJob> {

        @Override
        public @NonNull GroupCallPeekJob create(@NonNull Parameters parameters, @NonNull Data data) {
            return new GroupCallPeekJob(parameters, RecipientId.from(data.getString(KEY_GROUP_RECIPIENT_ID)));
        }
    }
}
