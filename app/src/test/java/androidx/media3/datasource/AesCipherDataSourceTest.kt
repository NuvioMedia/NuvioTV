package androidx.media3.datasource

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher

class AesCipherDataSourceTest {

    private val secretKey = ByteArray(16) { i -> (i + 1).toByte() }
    private val plaintext = "Hello World! This is a highly confidential zero-copy AES path test payload.".toByteArray()
    private val nonce = "test-nonce-key"

    @Test
    fun testAesDecryptionHeapBuffer() {
        val encryptedData = encryptPlaintext()

        // Wrap encrypted data in ByteArrayDataSource
        val byteArrayDataSource = ByteArrayDataSource(encryptedData)
        val aesDataSource = AesCipherDataSource(secretKey, byteArrayDataSource)

        val mockUri = mockk<Uri>(relaxed = true)
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .setKey(nonce)
            .build()

        val openedLength = aesDataSource.open(dataSpec)
        assertEquals(encryptedData.size.toLong(), openedLength)

        val decryptedBytes = ByteArray(plaintext.size)
        var totalRead = 0
        while (totalRead < decryptedBytes.size) {
            val read = aesDataSource.read(decryptedBytes, totalRead, decryptedBytes.size - totalRead)
            if (read == -1) break
            totalRead += read
        }

        assertEquals(plaintext.size, totalRead)
        assertArrayEquals(plaintext, decryptedBytes)

        aesDataSource.close()
    }

    @Test
    fun testAesDecryptionDirectByteBuffer() {
        val encryptedData = encryptPlaintext()

        // Create a custom DataSource that implements ByteBufferDataReader and supports it
        val fakeDataSource = FakeByteBufferDataSource(encryptedData)
        val aesDataSource = AesCipherDataSource(secretKey, fakeDataSource)

        assertTrue(aesDataSource.supportsByteBufferRead())

        val mockUri = mockk<Uri>(relaxed = true)
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .setKey(nonce)
            .build()

        aesDataSource.open(dataSpec)

        val targetBuffer = ByteBuffer.allocateDirect(plaintext.size)
        val read = aesDataSource.read(targetBuffer, plaintext.size)
        assertEquals(plaintext.size, read)

        targetBuffer.flip()
        val decryptedBytes = ByteArray(plaintext.size)
        targetBuffer.get(decryptedBytes)

        assertArrayEquals(plaintext, decryptedBytes)
        aesDataSource.close()
    }

    @Test
    fun testAesDecryptionHeapByteBuffer() {
        val encryptedData = encryptPlaintext()

        val fakeDataSource = FakeByteBufferDataSource(encryptedData)
        val aesDataSource = AesCipherDataSource(secretKey, fakeDataSource)

        val mockUri = mockk<Uri>(relaxed = true)
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .setKey(nonce)
            .build()

        aesDataSource.open(dataSpec)

        val targetBuffer = ByteBuffer.allocate(plaintext.size)
        val read = aesDataSource.read(targetBuffer, plaintext.size)
        assertEquals(plaintext.size, read)

        targetBuffer.flip()
        val decryptedBytes = ByteArray(plaintext.size)
        targetBuffer.get(decryptedBytes)

        assertArrayEquals(plaintext, decryptedBytes)
        aesDataSource.close()
    }

    private fun encryptPlaintext(): ByteArray {
        val encryptCipher = AesFlushingCipher(
            Cipher.ENCRYPT_MODE,
            secretKey,
            nonce,
            0L
        )
        val encryptedData = plaintext.clone()
        encryptCipher.updateInPlace(encryptedData, 0, encryptedData.size)
        return encryptedData
    }

    // A fake DataSource helper that implements ByteBufferDataReader for testing direct buffer reads
    private class FakeByteBufferDataSource(private val data: ByteArray) : DataSource, androidx.media3.common.ByteBufferDataReader {
        private var position = 0

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            position = 0
            return data.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val bytesToRead = Math.min(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, bytesToRead)
            position += bytesToRead
            return bytesToRead
        }

        override fun supportsByteBufferRead(): Boolean = true

        override fun read(buffer: ByteBuffer, length: Int): Int {
            if (position >= data.size) return -1
            val bytesToRead = Math.min(length, data.size - position)
            buffer.put(data, position, bytesToRead)
            position += bytesToRead
            return bytesToRead
        }

        override fun getUri(): Uri? = null

        override fun close() {}
    }
}
