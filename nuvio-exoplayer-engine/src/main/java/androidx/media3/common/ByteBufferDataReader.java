package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.nio.ByteBuffer;

@UnstableApi
public interface ByteBufferDataReader extends DataReader {

  boolean supportsByteBufferRead();

  int read(ByteBuffer buffer, int length) throws IOException;
}
