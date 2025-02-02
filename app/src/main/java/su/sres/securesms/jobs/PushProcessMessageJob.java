package su.sres.securesms.jobs;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.BadGroupIdException;
import su.sres.securesms.groups.GroupChangeBusyException;
import su.sres.securesms.groups.GroupId;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.Base64;
import su.sres.securesms.util.GroupUtil;

import su.sres.securesms.messages.MessageContentProcessor;
import su.sres.securesms.messages.MessageContentProcessor.ExceptionMetadata;
import su.sres.securesms.messages.MessageContentProcessor.MessageState;

import su.sres.signalservice.api.groupsv2.NoCredentialForRedemptionTimeException;
import su.sres.signalservice.api.messages.SignalServiceContent;
import su.sres.signalservice.api.messages.SignalServiceGroupContext;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class PushProcessMessageJob extends BaseJob {

  public static final String KEY          = "PushProcessJob";
  public static final String QUEUE_PREFIX = "__PUSH_PROCESS_JOB__";

  public static final String TAG = Log.tag(PushProcessMessageJob.class);

  private static final String KEY_MESSAGE_STATE      = "message_state";
  private static final String KEY_MESSAGE_PLAINTEXT  = "message_content";
  private static final String KEY_SMS_MESSAGE_ID     = "sms_message_id";
  private static final String KEY_TIMESTAMP          = "timestamp";
  private static final String KEY_EXCEPTION_SENDER   = "exception_sender";
  private static final String KEY_EXCEPTION_DEVICE   = "exception_device";
  private static final String KEY_EXCEPTION_GROUP_ID = "exception_groupId";

  @NonNull  private final MessageState         messageState;
  @Nullable private final SignalServiceContent content;
  @Nullable private final ExceptionMetadata    exceptionMetadata;
  private final long                 smsMessageId;
  private final long                 timestamp;

  @WorkerThread
  PushProcessMessageJob(@NonNull SignalServiceContent content,
                        long smsMessageId,
                        long timestamp)
  {
    this(MessageState.DECRYPTED_OK,
            content,
            null,
            smsMessageId,
            timestamp);
  }

  @WorkerThread
  PushProcessMessageJob(@NonNull MessageState messageState,
                        @NonNull ExceptionMetadata exceptionMetadata,
                        long smsMessageId,
                        long timestamp)
  {
    this(messageState,
            null,
            exceptionMetadata,
            smsMessageId,
            timestamp);
  }

  @WorkerThread
  public PushProcessMessageJob(@NonNull MessageState messageState,
                               @Nullable SignalServiceContent content,
                               @Nullable ExceptionMetadata exceptionMetadata,
                               long smsMessageId,
                               long timestamp)
  {
    this(createParameters(content, exceptionMetadata),
            messageState,
            content,
            exceptionMetadata,
            smsMessageId,
            timestamp);
  }

  private PushProcessMessageJob(@NonNull Parameters parameters,
                                @NonNull MessageState messageState,
                                @Nullable SignalServiceContent content,
                                @Nullable ExceptionMetadata exceptionMetadata,
                                long smsMessageId,
                                long timestamp)
  {
    super(parameters);

    this.messageState      = messageState;
    this.exceptionMetadata = exceptionMetadata;
    this.content           = content;
    this.smsMessageId      = smsMessageId;
    this.timestamp         = timestamp;
  }

  public static @NonNull String getQueueName(@NonNull RecipientId recipientId) {
    return QUEUE_PREFIX + recipientId.toQueueKey();
  }

  @WorkerThread
  private static @NonNull Parameters createParameters(@Nullable SignalServiceContent content, @Nullable ExceptionMetadata exceptionMetadata) {
    Context            context   = ApplicationDependencies.getApplication();
    String             queueName = QUEUE_PREFIX;
    Parameters.Builder builder   = new Parameters.Builder()
            .setMaxAttempts(Parameters.UNLIMITED);

    if (content != null) {
      SignalServiceGroupContext signalServiceGroupContext = GroupUtil.getGroupContextIfPresent(content);

      if (signalServiceGroupContext != null) {
        try {
          GroupId groupId = GroupUtil.idFromGroupContext(signalServiceGroupContext);
          queueName = getQueueName(Recipient.externalPossiblyMigratedGroup(context, groupId).getId());

          if (groupId.isV2()) {
            int localRevision = DatabaseFactory.getGroupDatabase(context)
                    .getGroupV2Revision(groupId.requireV2());

            if (signalServiceGroupContext.getGroupV2().get().getRevision() > localRevision ||
                    DatabaseFactory.getGroupDatabase(context).getGroupV1ByExpectedV2(groupId.requireV2()).isPresent())
            {
              Log.i(TAG, "Adding network constraint to group-related job.");
              builder.addConstraint(NetworkConstraint.KEY)
                      .setLifespan(TimeUnit.DAYS.toMillis(30));
            }
          }
        } catch (BadGroupIdException e) {
          Log.w(TAG, "Bad groupId! Using default queue. ID: " + content.getTimestamp());
        }
      } else {
        queueName = getQueueName(RecipientId.fromHighTrust(content.getSender()));
      }
    } else if (exceptionMetadata != null) {
      Recipient recipient = exceptionMetadata.getGroupId() != null ? Recipient.externalPossiblyMigratedGroup(context, exceptionMetadata.getGroupId())
              : Recipient.external(context, exceptionMetadata.getSender());
      queueName = getQueueName(recipient.getId());
    }

    builder.setQueue(queueName);

    return builder.build();
  }

  @Override
  public @NonNull Data serialize() {
    Data.Builder dataBuilder = new Data.Builder()
            .putInt(KEY_MESSAGE_STATE, messageState.ordinal())
            .putLong(KEY_SMS_MESSAGE_ID, smsMessageId)
            .putLong(KEY_TIMESTAMP, timestamp);

    if (messageState == MessageState.DECRYPTED_OK) {
      dataBuilder.putString(KEY_MESSAGE_PLAINTEXT, Base64.encodeBytes(Objects.requireNonNull(content).serialize()));
    } else {
      Objects.requireNonNull(exceptionMetadata);
      dataBuilder.putString(KEY_EXCEPTION_SENDER, exceptionMetadata.getSender())
              .putInt(KEY_EXCEPTION_DEVICE, exceptionMetadata.getSenderDevice())
              .putString(KEY_EXCEPTION_GROUP_ID, exceptionMetadata.getGroupId() == null ? null : exceptionMetadata.getGroupId().toString());
    }

    return dataBuilder.build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws Exception {
    MessageContentProcessor processor = new MessageContentProcessor(context);
    processor.process(messageState, content, exceptionMetadata, timestamp, smsMessageId);
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException ||
            e instanceof NoCredentialForRedemptionTimeException ||
            e instanceof GroupChangeBusyException;
  }

  @Override
  public void onFailure() {

  }

  public static final class Factory implements Job.Factory<PushProcessMessageJob> {
    @Override
    public @NonNull PushProcessMessageJob create(@NonNull Parameters parameters, @NonNull Data data) {
      try {
        MessageState state = MessageState.values()[data.getInt(KEY_MESSAGE_STATE)];

        if (state == MessageState.DECRYPTED_OK) {
          return new PushProcessMessageJob(parameters,
                  state,
                  SignalServiceContent.deserialize(Base64.decode(data.getString(KEY_MESSAGE_PLAINTEXT))),
                  null,
                  data.getLong(KEY_SMS_MESSAGE_ID),
                  data.getLong(KEY_TIMESTAMP));
        } else {
          ExceptionMetadata exceptionMetadata = new ExceptionMetadata(data.getString(KEY_EXCEPTION_SENDER),
                  data.getInt(KEY_EXCEPTION_DEVICE),
                  GroupId.parseNullableOrThrow(data.getStringOrDefault(KEY_EXCEPTION_GROUP_ID, null)));

          return new PushProcessMessageJob(parameters,
                  state,
                  null,
                  exceptionMetadata,
                  data.getLong(KEY_SMS_MESSAGE_ID),
                  data.getLong(KEY_TIMESTAMP));
        }
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }
}
