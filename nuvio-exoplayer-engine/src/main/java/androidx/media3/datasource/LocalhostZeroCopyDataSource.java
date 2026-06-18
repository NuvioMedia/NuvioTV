package androidx.media3.datasource;

import static androidx.media3.common.util.Util.castNonNull;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.ByteBufferDataReader;
import androidx.media3.common.util.Util;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * A direct zero-copy DataSource for localhost loopback TCP socket streaming (e.g. TorrServe).
 * Reads directly from the socket's loopback buffer into direct {@link ByteBuffer}s.
 */
public final class LocalhostZeroCopyDataSource extends BaseDataSource implements ByteBufferDataReader {

  @Nullable private SocketChannel socketChannel;
  @Nullable private Uri uri;
  @Nullable private DataSpec dataSpec;
  private long bytesRemaining;
  private boolean opened;
  @Nullable private ByteBuffer excessBuffer;

  public LocalhostZeroCopyDataSource() {
    super(/* isNetwork= */ true);
    bytesRemaining = C.LENGTH_UNSET;
  }

  @Override
  public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
    this.dataSpec = dataSpec;
    this.uri = dataSpec.uri;
    transferInitializing(dataSpec);

    String host = uri.getHost();
    if (host == null) {
      host = "127.0.0.1";
    }
    int port = uri.getPort();
    if (port == -1) {
      port = 8090;
    }

    try {
      SocketChannel channel = SocketChannel.open();
      channel.configureBlocking(true);
      channel.connect(new InetSocketAddress(host, port));
      socketChannel = channel;

      // Send lightweight HTTP GET request
      String path = uri.getPath();
      if (uri.getQuery() != null) {
        path += "?" + uri.getQuery();
      }
      if (path == null || path.isEmpty()) {
        path = "/";
      }

      String rangeHeader = "";
      if (dataSpec.position != 0) {
        rangeHeader = "Range: bytes=" + dataSpec.position + "-\r\n";
      }

      String request = "GET " + path + " HTTP/1.1\r\n"
          + "Host: " + host + ":" + port + "\r\n"
          + rangeHeader
          + "Connection: close\r\n\r\n";

      ByteBuffer requestBuffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII));
      while (requestBuffer.hasRemaining()) {
        channel.write(requestBuffer);
      }

      // Read response headers using a chunk-buffered reader into a temporary buffer
      ByteBuffer buffer = ByteBuffer.allocate(2048);
      int headerEndIndex = -1;
      
      while (true) {
        int read = channel.read(buffer);
        if (read <= 0) {
          break;
        }
        // Scan the buffer for "\r\n\r\n"
        byte[] array = buffer.array();
        int limit = buffer.position();
        for (int i = 0; i <= limit - 4; i++) {
          if (array[i] == '\r' && array[i+1] == '\n' && array[i+2] == '\r' && array[i+3] == '\n') {
            headerEndIndex = i + 4;
            break;
          }
        }
        if (headerEndIndex != -1) {
          break;
        }
        if (buffer.position() == buffer.capacity()) {
          // Double buffer capacity if headers exceed 2KB (extremely rare, but safe)
          ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
          buffer.flip();
          newBuffer.put(buffer);
          buffer = newBuffer;
        }
      }

      if (headerEndIndex == -1) {
        throw new HttpDataSource.HttpDataSourceException(
            "Malformed HTTP response: headers separator not found",
            dataSpec,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN);
      }

      // Store excess bytes read into excessBuffer
      int totalRead = buffer.position();
      int excessBytes = totalRead - headerEndIndex;
      if (excessBytes > 0) {
        excessBuffer = ByteBuffer.allocate(excessBytes);
        excessBuffer.put(buffer.array(), headerEndIndex, excessBytes);
        excessBuffer.flip();
      } else {
        excessBuffer = null;
      }

      byte[] headerBytes = buffer.array();

      // Check status code: " 200 " or " 206 " in the first line of the header
      int firstLineEnd = -1;
      for (int i = 0; i < headerEndIndex; i++) {
        if (headerBytes[i] == '\r') {
          firstLineEnd = i;
          break;
        }
      }
      if (firstLineEnd == -1) {
        firstLineEnd = headerEndIndex;
      }

      boolean isSuccess = false;
      for (int i = 0; i <= firstLineEnd - 5; i++) {
        if (headerBytes[i] == ' ' && headerBytes[i+4] == ' ') {
          if ((headerBytes[i+1] == '2' && headerBytes[i+2] == '0' && headerBytes[i+3] == '0') ||
              (headerBytes[i+1] == '2' && headerBytes[i+2] == '0' && headerBytes[i+3] == '6')) {
            isSuccess = true;
            break;
          }
        }
      }

      if (!isSuccess) {
        String firstLine = new String(headerBytes, 0, firstLineEnd, StandardCharsets.US_ASCII);
        throw new HttpDataSource.InvalidResponseCodeException(
            firstLine.contains(" 416 ") ? 416 : 400,
            firstLine,
            null,
            java.util.Collections.emptyMap(),
            dataSpec,
            Util.EMPTY_BYTE_ARRAY);
      }

      if (dataSpec.length != C.LENGTH_UNSET) {
        bytesRemaining = dataSpec.length;
      } else {
        long contentLength = -1;
        byte[] targetLower = {
            'c', 'o', 'n', 't', 'e', 'n', 't', '-', 'l', 'e', 'n', 'g', 't', 'h', ':'
        };
        for (int i = 0; i <= headerEndIndex - targetLower.length; i++) {
          boolean match = true;
          for (int j = 0; j < targetLower.length; j++) {
            byte b = headerBytes[i + j];
            int c = (b >= 'A' && b <= 'Z') ? (b + 32) : b;
            if (c != targetLower[j]) {
              match = false;
              break;
            }
          }
          if (match) {
            int start = i + targetLower.length;
            while (start < headerEndIndex && (headerBytes[start] == ' ' || headerBytes[start] == '\t')) {
              start++;
            }
            long num = 0;
            boolean hasDigits = false;
            while (start < headerEndIndex && headerBytes[start] >= '0' && headerBytes[start] <= '9') {
              num = num * 10 + (headerBytes[start] - '0');
              hasDigits = true;
              start++;
            }
            if (hasDigits) {
              contentLength = num;
            }
            break;
          }
        }
        bytesRemaining = contentLength >= 0 ? contentLength : C.LENGTH_UNSET;
      }

      opened = true;
      transferStarted(dataSpec);
      return bytesRemaining;
    } catch (IOException e) {
      close();
      throw new HttpDataSource.HttpDataSourceException(
          e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
    }
  }

  @Override
  public boolean supportsByteBufferRead() {
    return true;
  }

  @Override
  public int read(ByteBuffer buffer, int length) throws HttpDataSource.HttpDataSourceException {
    if (length == 0) {
      return 0;
    }
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }

    SocketChannel channel = socketChannel;
    if (channel == null) {
      return C.RESULT_END_OF_INPUT;
    }

    int toRead = length;
    if (bytesRemaining != C.LENGTH_UNSET) {
      toRead = (int) Math.min(bytesRemaining, length);
    }

    int excessRead = 0;
    if (excessBuffer != null && excessBuffer.hasRemaining()) {
      excessRead = Math.min(excessBuffer.remaining(), toRead);
      int originalLimit = excessBuffer.limit();
      excessBuffer.limit(excessBuffer.position() + excessRead);
      buffer.put(excessBuffer);
      excessBuffer.limit(originalLimit);
      
      if (!excessBuffer.hasRemaining()) {
        excessBuffer = null;
      }
      
      toRead -= excessRead;
      if (bytesRemaining != C.LENGTH_UNSET) {
        bytesRemaining -= excessRead;
      }
      bytesTransferred(excessRead);
      
      if (toRead == 0) {
        return excessRead;
      }
    }

    if (toRead > 0) {
      int originalLimit = buffer.limit();
      buffer.limit(buffer.position() + toRead);
      int channelRead;
      try {
        channelRead = channel.read(buffer);
      } catch (IOException e) {
        throw new HttpDataSource.HttpDataSourceException(
            e, castNonNull(dataSpec), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_READ);
      } finally {
        buffer.limit(originalLimit);
      }

      if (channelRead < 0) {
        return excessRead > 0 ? excessRead : C.RESULT_END_OF_INPUT;
      }

      if (bytesRemaining != C.LENGTH_UNSET) {
        bytesRemaining -= channelRead;
      }
      bytesTransferred(channelRead);
      return excessRead + channelRead;
    }

    return excessRead;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws HttpDataSource.HttpDataSourceException {
    ByteBuffer wrap = ByteBuffer.wrap(buffer, offset, length);
    return read(wrap, length);
  }

  @Override
  @Nullable
  public Uri getUri() {
    return uri;
  }

  @Override
  public void close() {
    dataSpec = null;
    uri = null;
    excessBuffer = null;
    try {
      if (socketChannel != null) {
        socketChannel.close();
      }
    } catch (IOException e) {
      // Quietly close
    } finally {
      socketChannel = null;
      if (opened) {
        opened = false;
        transferEnded();
      }
    }
  }
}
