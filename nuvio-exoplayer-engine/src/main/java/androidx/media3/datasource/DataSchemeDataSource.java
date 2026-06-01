/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.datasource;

import static androidx.media3.common.util.Util.castNonNull;
import static java.lang.Math.min;

import android.net.Uri;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.ByteBufferDataReader;
import androidx.media3.common.C;
import androidx.media3.common.NuvioEngineConfig;
import androidx.media3.common.ParserException;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** A {@link DataSource} for reading data URLs, as defined by RFC 2397. */
@UnstableApi
public final class DataSchemeDataSource extends BaseDataSource implements ByteBufferDataReader {

  public static final String SCHEME_DATA = "data";

  @Nullable private DataSpec dataSpec;
  @Nullable private byte[] data;
  private int readPosition;
  private int bytesRemaining;

  public DataSchemeDataSource() {
    super(/* isNetwork= */ false);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    transferInitializing(dataSpec);
    this.dataSpec = dataSpec;
    Uri uri = dataSpec.uri.normalizeScheme();
    String scheme = uri.getScheme();
    Assertions.checkArgument(SCHEME_DATA.equals(scheme), "Unsupported scheme: " + scheme);
    String[] uriParts = Util.split(uri.getSchemeSpecificPart(), ",");
    if (uriParts.length != 2) {
      throw ParserException.createForMalformedDataOfUnknownType(
          "Unexpected URI format: " + uri, /* cause= */ null);
    }
    String dataString = uriParts[1];
    if (uriParts[0].contains(";base64")) {
      try {
        data = Base64.decode(dataString, /* flags= */ Base64.DEFAULT);
      } catch (IllegalArgumentException e) {
        throw ParserException.createForMalformedDataOfUnknownType(
            "Error while parsing Base64 encoded string: " + dataString, e);
      }
    } else {
      // TODO: Add support for other charsets.
      data = Util.getUtf8Bytes(URLDecoder.decode(dataString, StandardCharsets.US_ASCII.name()));
    }
    if (dataSpec.position > data.length) {
      data = null;
      throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
    }
    readPosition = (int) dataSpec.position;
    bytesRemaining = data.length - readPosition;
    if (dataSpec.length != C.LENGTH_UNSET) {
      bytesRemaining = (int) min(bytesRemaining, dataSpec.length);
    }
    transferStarted(dataSpec);
    return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : bytesRemaining;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) {
    if (length == 0) {
      return 0;
    }
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    length = min(length, bytesRemaining);
    System.arraycopy(castNonNull(data), readPosition, buffer, offset, length);
    readPosition += length;
    bytesRemaining -= length;
    bytesTransferred(length);
    return length;
  }

  @Override
  public boolean supportsByteBufferRead() {
    return NuvioEngineConfig.get().isZeroCopyEnabled();
  }

  @Override
  public int read(ByteBuffer buffer, int length) {
    if (length == 0) {
      return 0;
    }
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    length = min(min(length, buffer.remaining()), bytesRemaining);
    buffer.put(castNonNull(data), readPosition, length);
    readPosition += length;
    bytesRemaining -= length;
    bytesTransferred(length);
    return length;
  }

  @Override
  @Nullable
  public Uri getUri() {
    return dataSpec != null ? dataSpec.uri : null;
  }

  @Override
  public void close() {
    if (data != null) {
      data = null;
      transferEnded();
    }
    dataSpec = null;
  }
}
