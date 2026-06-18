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

      // Read response headers character by character until \r\n\r\n
      ByteBuffer headerBuffer = ByteBuffer.allocate(1);
      StringBuilder headerBuilder = new StringBuilder();
      while (true) {
        headerBuffer.clear();
        int read = channel.read(headerBuffer);
        if (read <= 0) {
          break;
        }
        headerBuffer.flip();
        char c = (char) headerBuffer.get();
        headerBuilder.append(c);
        if (headerBuilder.length() >= 4 && headerBuilder.substring(headerBuilder.length() - 4).equals("\r\n\r\n")) {
          break;
        }
      }

      String headers = headerBuilder.toString();

      // Simple status code check (200 OK or 206 Partial Content)
      boolean isSuccess = headers.contains(" 200 ") || headers.contains(" 206 ");
      if (!isSuccess) {
        throw new HttpDataSource.InvalidResponseCodeException(
            headers.contains(" 416 ") ? 416 : 400,
            headers.lines().findFirst().orElse(""),
            null,
            java.util.Collections.emptyMap(),
            dataSpec,
            Util.EMPTY_BYTE_ARRAY);
      }

      if (dataSpec.length != C.LENGTH_UNSET) {
        bytesRemaining = dataSpec.length;
      } else {
        String contentLengthHeader = null;
        for (String line : headers.split("\r\n")) {
          if (line.toLowerCase().startsWith("content-length:")) {
            contentLengthHeader = line.substring(line.indexOf(':') + 1).trim();
            break;
          }
        }
        if (contentLengthHeader != null) {
          try {
            bytesRemaining = Long.parseLong(contentLengthHeader);
          } catch (NumberFormatException e) {
            bytesRemaining = C.LENGTH_UNSET;
          }
        } else {
          bytesRemaining = C.LENGTH_UNSET;
        }
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

    int originalLimit = buffer.limit();
    buffer.limit(buffer.position() + toRead);
    int read;
    try {
      read = channel.read(buffer);
    } catch (IOException e) {
      throw new HttpDataSource.HttpDataSourceException(
          e, castNonNull(dataSpec), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_READ);
    } finally {
      buffer.limit(originalLimit);
    }

    if (read < 0) {
      return C.RESULT_END_OF_INPUT;
    }

    if (bytesRemaining != C.LENGTH_UNSET) {
      bytesRemaining -= read;
    }
    bytesTransferred(read);
    return read;
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
