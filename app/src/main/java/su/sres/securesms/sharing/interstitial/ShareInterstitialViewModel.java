package su.sres.securesms.sharing.interstitial;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.annimon.stream.Stream;

import su.sres.securesms.linkpreview.LinkPreview;
import su.sres.securesms.sharing.MultiShareArgs;
import su.sres.securesms.sharing.MultiShareSender;
import su.sres.securesms.util.DefaultValueLiveData;
import su.sres.securesms.util.MappingModel;
import su.sres.securesms.util.Util;

import java.util.List;

class ShareInterstitialViewModel extends ViewModel {

    private final MultiShareArgs                           args;
    private final MutableLiveData<List<MappingModel<?>>> recipients;
    private final MutableLiveData<String>                draftText;

    private LinkPreview linkPreview;

    ShareInterstitialViewModel(@NonNull MultiShareArgs args, @NonNull ShareInterstitialRepository repository) {
        this.args        = args;
        this.recipients  = new MutableLiveData<>();
        this.draftText   = new DefaultValueLiveData<>(Util.firstNonNull(args.getDraftText(), ""));

        repository.loadRecipients(args.getShareContactAndThreads(),
                list -> recipients.postValue(Stream.of(list)
                        .<MappingModel<?>>mapIndexed((i, r) -> new ShareInterstitialMappingModel(r, i == list.size() - 1))
                        .toList()));

    }

    LiveData<List<MappingModel<?>>> getRecipients() {
        return recipients;
    }

    LiveData<Boolean> hasDraftText() {
        return Transformations.map(draftText, text -> !TextUtils.isEmpty(text));
    }

    void onDraftTextChanged(@NonNull String change) {
        draftText.setValue(change);
    }

    void onLinkPreviewChanged(@Nullable LinkPreview linkPreview) {
        this.linkPreview = linkPreview;
    }

    void send(@NonNull Consumer<MultiShareSender.MultiShareSendResultCollection> resultsConsumer) {
        LinkPreview linkPreview = this.linkPreview;
        String      draftText   = this.draftText.getValue();

        MultiShareArgs.Builder builder = args.buildUpon()
                .withDraftText(draftText)
                .withLinkPreview(linkPreview);

        MultiShareSender.send(builder.build(), resultsConsumer);
    }

    static class Factory implements ViewModelProvider.Factory {

        private final MultiShareArgs args;
        private final ShareInterstitialRepository repository;

        Factory(@NonNull MultiShareArgs args, @NonNull ShareInterstitialRepository repository) {
            this.args       = args;
            this.repository = repository;
        }

        @Override
        public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return modelClass.cast(new ShareInterstitialViewModel(args, repository));
        }
    }
}
