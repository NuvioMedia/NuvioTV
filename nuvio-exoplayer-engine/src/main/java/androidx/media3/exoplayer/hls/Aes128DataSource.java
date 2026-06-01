/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.hls;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.ByteBufferDataReader;
import androidx.media3.common.C;
import androidx.media3.common.NuvioEngineConfig;
import androidx.media3.common.util.Assertions;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A {@link DataSource} that decrypts data read from an upstream source, encrypted with AES-128 with
 * a 128-bit key and PKCS7 padding.
 *
 * <p>Note that this {@link DataSource} does not support being opened from arbitrary offsets. It is
 * designed specifically for reading whole files as defined in an HLS media playlist. For this
 * reason the implementation is private to the HLS package.
 */
/* package */ class Aes128DataSource implements DataSource, ByteBufferDataReader {

  private final DataSource upstream;
  private final byte[] encryptionKey;
  private final byte[] encryptionIv;

  @Nullable private CipherInputStream cipherInputStream;
  @Nullable private ReadableByteChannel cipherChannel;

  /**
   * @param upstream The upstream {@link DataSource}.
   * @param encryptionKey The encryption key.
   * @param encryptionIv The encryption initialization vector.
   */
  public Aes128DataSource(DataSource upstream, byte[] encryptionKey, byte[] encryptionIv) {
    this.upstream = upstream;
    this.encryptionKey = encryptionKey;
    this.encryptionIv = encryptionIv;
  }

  @Override
  public final void addTransferListener(TransferListener transferListener) {
    Assertions.checkNotNull(transferListener);
    upstream.addTransferListener(transferListener);
  }

  @Override
  public final long open(DataSpec dataSpec) throws IOException {
    Cipher cipher;
    try {
      cipher = getCipherInstance();
    } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
      throw new RuntimeException(e);
    }

    Key cipherKey = new SecretKeySpec(encryptionKey, "AES");
    AlgorithmParameterSpec cipherIV = new IvParameterSpec(encryptionIv);

    try {
      cipher.init(Cipher.DECRYPT_MODE, cipherKey, cipherIV);
    } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
      throw new RuntimeException(e);
    }

    DataSourceInputStream inputStream = new DataSourceInputStream(upstream, dataSpec);
    cipherInputStream = new CipherInputStream(inputStream, cipher);
    cipherChannel = Channels.newChannel(cipherInputStream);
    inputStream.open();

    return C.LENGTH_UNSET;
  }

  @Override
  public final int read(byte[] buffer, int offset, int length) throws IOException {
    Assertions.checkNotNull(cipherInputStream);
    int bytesRead = cipherInputStream.read(buffer, offset, length);
    if (bytesRead < 0) {
      return C.RESULT_END_OF_INPUT;
    }
    return bytesRead;
  }

  @Override
  public boolean supportsByteBufferRead() {
    return NuvioEngineConfig.get().isZeroCopyEnabled();
  }

  @Override
  public final int read(ByteBuffer buffer, int length) throws IOException {
    int originalLimit = buffer.limit();
    int bytesRead;
    try {
      buffer.limit(buffer.position() + Math.min(length, buffer.remaining()));
      bytesRead = Assertions.checkNotNull(cipherChannel).read(buffer);
    } finally {
      buffer.limit(originalLimit);
    }
    if (bytesRead < 0) {
      return C.RESULT_END_OF_INPUT;
    }
    return bytesRead;
  }

  @Override
  @Nullable
  public final Uri getUri() {
    return upstream.getUri();
  }

  @Override
  public final Map<String, List<String>> getResponseHeaders() {
    return upstream.getResponseHeaders();
  }

  @Override
  public void close() throws IOException {
    if (cipherInputStream != null) {
      cipherChannel = null;
      cipherInputStream = null;
      upstream.close();
    }
  }

  protected Cipher getCipherInstance() throws NoSuchPaddingException, NoSuchAlgorithmException {
    return Cipher.getInstance("AES/CBC/PKCS7Padding");
  }
}
