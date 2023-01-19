package su.sres.securesms.mediasend;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import android.view.View;
import android.view.ViewGroup;

import com.annimon.stream.Stream;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.mms.MediaConstraints;
import su.sres.securesms.scribbles.ImageEditorFragment;
import su.sres.securesms.util.MediaUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class MediaSendFragmentPagerAdapter extends FragmentStatePagerAdapter {

    private final List<Media>                         media;
    private final Map<Integer, MediaSendPageFragment> fragments;
    private final Map<Uri, Object>                    savedState;
    private final MediaConstraints mediaConstraints;

    MediaSendFragmentPagerAdapter(@NonNull FragmentManager fm, @NonNull MediaConstraints mediaConstraints) {
        super(fm);
        this.mediaConstraints = mediaConstraints;
        this.media            = new ArrayList<>();
        this.fragments        = new HashMap<>();
        this.savedState       = new HashMap<>();
    }

    @Override
    public Fragment getItem(int i) {
        Media mediaItem = media.get(i);

        if (MediaUtil.isGif(mediaItem.getMimeType())) {
            return MediaSendGifFragment.newInstance(mediaItem.getUri());
        } else if (MediaUtil.isImageType(mediaItem.getMimeType())) {
            return ImageEditorFragment.newInstance(mediaItem.getUri());
        } else if (MediaUtil.isVideoType(mediaItem.getMimeType())) {
            return MediaSendVideoFragment.newInstance(mediaItem.getUri(),
                    mediaConstraints.getCompressedVideoMaxSize(ApplicationDependencies.getApplication()),
                    mediaConstraints.getVideoMaxSize(ApplicationDependencies.getApplication()));
        } else {
            throw new UnsupportedOperationException("Can only render images and videos. Found mimetype: '" + mediaItem.getMimeType() + "'");
        }
    }

    @Override
    public int getItemPosition(@NonNull Object object) {
        return POSITION_NONE;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        MediaSendPageFragment fragment = (MediaSendPageFragment) super.instantiateItem(container, position);
        fragments.put(position, fragment);

        Object state = savedState.get(fragment.getUri());
        if (state != null) {
            fragment.restoreState(state);
        }

        return fragment;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        MediaSendPageFragment fragment = (MediaSendPageFragment) object;

        Object state = fragment.saveState();
        if (state != null) {
            savedState.put(fragment.getUri(), state);
        }

        super.destroyItem(container, position, object);
        fragments.remove(position);
    }

    @Override
    public int getCount() {
        return media.size();
    }

    List<Media> getAllMedia() {
        return media;
    }

    void setMedia(@NonNull List<Media> media) {
        this.media.clear();
        this.media.addAll(media);
        notifyDataSetChanged();
    }

    Map<Uri, Object> getSavedState() {
        for (MediaSendPageFragment fragment : fragments.values()) {
            Object state = fragment.saveState();
            if (state != null) {
                savedState.put(fragment.getUri(), state);
            }
        }
        return new HashMap<>(savedState);
    }

    void saveAllState() {
        for (MediaSendPageFragment fragment : fragments.values()) {
            Object state = fragment.saveState();
            if (state != null) {
                savedState.put(fragment.getUri(), state);
            }
        }
    }

    void restoreState(@NonNull Map<Uri, Object> state) {
        savedState.clear();
        savedState.putAll(state);
    }

    @Nullable View getPlaybackControls(int position) {
        return fragments.containsKey(position) ? fragments.get(position).getPlaybackControls() : null;
    }

    void pausePlayback() {
        for (MediaSendPageFragment fragment : fragments.values()) {
            if (fragment instanceof MediaSendVideoFragment) {
                ((MediaSendVideoFragment)fragment).pausePlayback();
            }
        }
    }

    void notifyHidden() {
        Stream.of(fragments.values()).forEach(MediaSendPageFragment::notifyHidden);
    }

    void notifyPageChanged(int currentPage) {
        notifyHiddenIfExists(currentPage - 1);
        notifyHiddenIfExists(currentPage + 1);
    }

    private void notifyHiddenIfExists(int position) {
        MediaSendPageFragment fragment = fragments.get(position);

        if (fragment != null) {
            fragment.notifyHidden();
        }
    }
}