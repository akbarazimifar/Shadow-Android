package su.sres.securesms.stickers;

import android.app.Application;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;
import android.arch.lifecycle.ViewModel;
import android.arch.lifecycle.ViewModelProvider;
import android.database.ContentObserver;
import android.os.Handler;
import android.support.annotation.NonNull;

import su.sres.securesms.database.DatabaseContentProviders;
import su.sres.securesms.stickers.StickerKeyboardRepository.PackListResult;
import su.sres.securesms.util.Throttler;

final class StickerKeyboardViewModel extends ViewModel {

    private final Application                     application;
    private final MutableLiveData<PackListResult> packs;
    private final Throttler                       observerThrottler;
    private final ContentObserver                 observer;

    private StickerKeyboardViewModel(@NonNull Application application, @NonNull StickerKeyboardRepository repository) {
        this.application       = application;
        this.packs             = new MutableLiveData<>();
        this.observerThrottler = new Throttler(500);
        this.observer          = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                observerThrottler.publish(() -> repository.getPackList(packs::postValue));
            }
        };

        repository.getPackList(packs::postValue);
        application.getContentResolver().registerContentObserver(DatabaseContentProviders.StickerPack.CONTENT_URI, true, observer);
    }

    @NonNull LiveData<PackListResult> getPacks() {
        return packs;
    }

    @Override
    protected void onCleared() {
        application.getContentResolver().unregisterContentObserver(observer);
    }

    public static final class Factory extends ViewModelProvider.NewInstanceFactory {
        private final Application               application;
        private final StickerKeyboardRepository repository;

        public Factory(@NonNull Application application, @NonNull StickerKeyboardRepository repository) {
            this.application = application;
            this.repository  = repository;
        }

        @Override
        public @NonNull<T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            //noinspection ConstantConditions
            return modelClass.cast(new StickerKeyboardViewModel(application, repository));
        }
    }
}