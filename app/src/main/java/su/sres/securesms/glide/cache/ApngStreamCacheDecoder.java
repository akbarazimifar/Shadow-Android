package su.sres.securesms.glide.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;

import su.sres.glide.apng.decode.APNGDecoder;
import su.sres.glide.apng.decode.APNGParser;
import su.sres.glide.common.io.StreamReader;
import su.sres.securesms.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class ApngStreamCacheDecoder implements ResourceDecoder<InputStream, APNGDecoder> {

    private final ResourceDecoder<ByteBuffer, APNGDecoder> byteBufferDecoder;

    public ApngStreamCacheDecoder(ResourceDecoder<ByteBuffer, APNGDecoder> byteBufferDecoder) {
        this.byteBufferDecoder = byteBufferDecoder;
    }

    @Override
    public boolean handles(@NonNull InputStream source, @NonNull Options options) {
        return APNGParser.isAPNG(new StreamReader(source));
    }

    @Override
    public @Nullable Resource<APNGDecoder> decode(@NonNull final InputStream source, int width, int height, @NonNull Options options) throws IOException {
        byte[] data = Util.readFully(source);

        if (data == null) {
            return null;
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        return byteBufferDecoder.decode(byteBuffer, width, height, options);
    }
}

