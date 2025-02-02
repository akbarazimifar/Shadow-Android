package su.sres.securesms.mediapreview;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import su.sres.securesms.R;
import su.sres.core.util.logging.Log;
import su.sres.securesms.mms.VideoSlide;
import su.sres.securesms.util.MediaUtil;
import su.sres.securesms.video.VideoPlayer;

public final class VideoMediaPreviewFragment extends MediaPreviewFragment {

    private static final String TAG = Log.tag(VideoMediaPreviewFragment.class);

    private VideoPlayer videoView;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View    itemView    = inflater.inflate(R.layout.media_preview_video_fragment, container, false);
        Bundle  arguments   = requireArguments();
        Uri     uri         = arguments.getParcelable(DATA_URI);
        String  contentType = arguments.getString(DATA_CONTENT_TYPE);
        long    size        = arguments.getLong(DATA_SIZE);
        boolean autoPlay    = arguments.getBoolean(AUTO_PLAY);

        if (!MediaUtil.isVideo(contentType)) {
            throw new AssertionError("This fragment can only display video");
        }

        videoView = itemView.findViewById(R.id.video_player);

        videoView.setWindow(requireActivity().getWindow());
        videoView.setVideoSource(new VideoSlide(getContext(), uri, size), autoPlay);

        videoView.setOnClickListener(v -> events.singleTapOnMedia());

        return itemView;
    }

    @Override
    public void cleanUp() {
        if (videoView != null) {
            videoView.cleanup();
        }
    }

    @Override
    public void pause() {
        if (videoView != null) {
            videoView.pause();
        }
    }

    @Override
    public View getPlaybackControls() {
        return videoView != null ? videoView.getControlView() : null;
    }
}