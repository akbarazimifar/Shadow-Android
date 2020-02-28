package su.sres.securesms.migrations;

import androidx.annotation.NonNull;

import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.logging.Log;
import su.sres.securesms.phonenumbers.NumberUtil;
import su.sres.securesms.profiles.AvatarHelper;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.GroupUtil;
import su.sres.securesms.util.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Previously, we used a recipient's address as the filename for their avatar. We want to use
 * recipientId's instead in preparation for UUIDs.
 */
public class AvatarMigrationJob extends MigrationJob {

    public static final String KEY = "AvatarMigrationJob";

    private static final String TAG = Log.tag(AvatarMigrationJob.class);

    private static final Pattern NUMBER_PATTERN = Pattern.compile("^[0-9\\-+]+$");

    AvatarMigrationJob() {
        this(new Parameters.Builder().build());
    }

    private AvatarMigrationJob(@NonNull Parameters parameters) {
        super(parameters);
    }

    @Override
    public boolean isUiBlocking() {
        return true;
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void performMigration() {
        File   oldDirectory = new File(context.getFilesDir(), "avatars");
        File[] files        = oldDirectory.listFiles();

        if (files == null) {
            Log.w(TAG, "Unable to read directory, and therefore unable to migrate any avatars.");
            return;
        }

        Log.i(TAG, "Preparing to move " + files.length + " avatars.");

        for (File file : files) {
            try {
                if (isValidFileName(file.getName())) {
                    Recipient recipient = Recipient.external(context, file.getName());
                    byte[]    data      = Util.readFully(new FileInputStream(file));

                    AvatarHelper.setAvatar(context, recipient.getId(), data);
                } else {
                    Log.w(TAG, "Invalid file name! Can't migrate this file. It'll just get deleted.");
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to copy avatar file. Skipping it.", e);
            } finally {
                file.delete();
            }
        }
    }

    @Override
    boolean shouldRetry(@NonNull Exception e) {
        return false;
    }

    private static boolean isValidFileName(@NonNull String name) {
        return NUMBER_PATTERN.matcher(name).matches() || GroupUtil.isEncodedGroup(name) || NumberUtil.isValidEmail(name);
    }

    public static class Factory implements Job.Factory<AvatarMigrationJob> {
        @Override
        public @NonNull AvatarMigrationJob create(@NonNull Parameters parameters, @NonNull Data data) {
            return new AvatarMigrationJob(parameters);
        }
    }
}