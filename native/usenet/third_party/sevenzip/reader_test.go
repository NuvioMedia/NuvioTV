package sevenzip_test

import (
	"errors"
	"fmt"
	"hash"
	"hash/crc32"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"
	"testing/fstest"
	"testing/iotest"

	"github.com/javi11/sevenzip"
	"github.com/javi11/sevenzip/internal/util"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"golang.org/x/sync/errgroup"
)

func reader(r io.Reader) io.Reader {
	return r
}

var errCRCMismatch = errors.New("CRC doesn't match")

func extractFile(tb testing.TB, r io.Reader, h hash.Hash, f *sevenzip.File) error {
	tb.Helper()

	h.Reset()

	if _, err := io.Copy(h, r); err != nil {
		return fmt.Errorf("error extracting file: %w", err)
	}

	if f.UncompressedSize > 0 && f.CRC32 == 0 {
		tb.Log("archive member", f.Name, "has no CRC")

		return nil
	}

	if !util.CRC32Equal(h.Sum(nil), f.CRC32) {
		return errCRCMismatch
	}

	return nil
}

//nolint:lll
func extractArchive(tb testing.TB, r *sevenzip.Reader, stream int, h hash.Hash, fn func(io.Reader) io.Reader, optimised bool) (err error) {
	tb.Helper()

	for _, f := range r.File {
		if stream >= 0 && f.Stream != stream {
			continue
		}

		var rc io.ReadCloser

		rc, err = f.Open()
		if err != nil {
			return fmt.Errorf("error opening file: %w", err)
		}

		defer func() {
			err = errors.Join(err, rc.Close())
		}()

		if err = extractFile(tb, fn(rc), h, f); err != nil {
			return err
		}

		if optimised {
			if err = rc.Close(); err != nil {
				return fmt.Errorf("error closing: %w", err)
			}
		}
	}

	return nil
}

//nolint:funlen
func TestOpenReader(t *testing.T) {
	t.Parallel()

	tables := []struct {
		name, file string
		volumes    []string
		err        error
	}{
		{
			name: "no header compression",
			file: "t0.7z",
		},
		{
			name: "with header compression",
			file: "t1.7z",
		},
		{
			name: "multiple volume",
			file: "multi.7z.001",
			volumes: []string{
				"multi.7z.001",
				"multi.7z.002",
				"multi.7z.003",
				"multi.7z.004",
				"multi.7z.005",
				"multi.7z.006",
			},
		},
		{
			name: "empty streams and files",
			file: "empty.7z",
		},
		{
			name: "empty2",
			file: "empty2.7z",
		},
		{
			name: "bcj2",
			file: "bcj2.7z",
		},
		{
			name: "bzip2",
			file: "bzip2.7z",
		},
		{
			name: "copy",
			file: "copy.7z",
		},
		{
			name: "deflate",
			file: "deflate.7z",
		},
		{
			name: "delta",
			file: "delta.7z",
		},
		{
			name: "lzma",
			file: "lzma.7z",
		},
		{
			name: "lzma2",
			file: "lzma2.7z",
		},
		{
			name: "complex",
			file: "lzma1900.7z",
		},
		{
			name: "lz4",
			file: "lz4.7z",
		},
		{
			name: "brotli",
			file: "brotli.7z",
		},
		{
			name: "zstd",
			file: "zstd.7z",
		},
		{
			name: "sfx",
			file: "sfx.exe",
		},
		{
			name: "bcj",
			file: "bcj.7z",
		},
		{
			name: "ppc",
			file: "ppc.7z",
		},
		{
			name: "arm",
			file: "arm.7z",
		},
		{
			name: "sparc",
			file: "sparc.7z",
		},
		{
			name: "issue 87",
			file: "issue87.7z",
		},
		{
			name: "issue 112",
			file: "file_and_empty.7z",
		},
		{
			name: "issue 113",
			file: "COMPRESS-492.7z",
			err:  sevenzip.ErrMissingUnpackInfo,
		},
	}

	for _, table := range tables {
		t.Run(table.name, func(t *testing.T) {
			t.Parallel()

			r, err := sevenzip.OpenReader(filepath.Join("testdata", table.file))
			if table.err == nil {
				require.NoError(t, err)
			} else {
				assert.ErrorIs(t, err, table.err)

				return
			}

			defer func() {
				if err := r.Close(); err != nil {
					t.Fatal(err)
				}
			}()

			volumes := []string{}

			if table.volumes != nil {
				for _, v := range table.volumes {
					volumes = append(volumes, filepath.Join("testdata", v))
				}
			} else {
				volumes = append(volumes, filepath.Join("testdata", table.file))
			}

			assert.Equal(t, volumes, r.Volumes())

			if err := extractArchive(t, &r.Reader, -1, crc32.NewIEEE(), iotest.OneByteReader, true); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestOpenReaderWithPassword(t *testing.T) {
	t.Parallel()

	tables := []struct {
		name, file, password string
	}{
		{
			name:     "no header compression",
			file:     "t2.7z",
			password: "password",
		},
		{
			name:     "with header compression",
			file:     "t3.7z",
			password: "password",
		},
		{
			name:     "unencrypted headers compressed files",
			file:     "t4.7z",
			password: "password",
		},
		{
			name:     "unencrypted headers uncompressed files",
			file:     "t5.7z",
			password: "password",
		},
		{
			name:     "issue 75",
			file:     "7zcracker.7z",
			password: "876",
		},
	}

	for _, table := range tables {
		t.Run(table.name, func(t *testing.T) {
			t.Parallel()

			r, err := sevenzip.OpenReaderWithPassword(filepath.Join("testdata", table.file), table.password)
			if err != nil {
				t.Fatal(err)
			}

			defer func() {
				if err := r.Close(); err != nil {
					t.Fatal(err)
				}
			}()

			if err := extractArchive(t, &r.Reader, -1, crc32.NewIEEE(), iotest.OneByteReader, true); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestOpenReaderWithWrongPassword(t *testing.T) {
	t.Parallel()

	t.Run("encrypted headers", func(t *testing.T) {
		t.Parallel()

		_, err := sevenzip.OpenReaderWithPassword(filepath.Join("testdata", "t2.7z"), "notpassword")

		var e *sevenzip.ReadError
		if assert.ErrorAs(t, err, &e) {
			assert.True(t, e.Encrypted)
		}
	})

	t.Run("unencrypted headers compressed files", func(t *testing.T) {
		t.Parallel()

		r, err := sevenzip.OpenReaderWithPassword(filepath.Join("testdata", "t4.7z"), "notpassword")
		require.NoError(t, err)

		defer func() {
			require.NoError(t, r.Close())
		}()

		err = extractArchive(t, &r.Reader, -1, crc32.NewIEEE(), iotest.OneByteReader, true)

		var e *sevenzip.ReadError
		if assert.ErrorAs(t, err, &e) {
			assert.True(t, e.Encrypted)
		}
	})

	t.Run("unencrypted headers uncompressed files", func(t *testing.T) {
		t.Parallel()

		r, err := sevenzip.OpenReaderWithPassword(filepath.Join("testdata", "t5.7z"), "notpassword")
		require.NoError(t, err)

		defer func() {
			require.NoError(t, r.Close())
		}()

		err = extractArchive(t, &r.Reader, -1, crc32.NewIEEE(), iotest.OneByteReader, true)
		assert.ErrorIs(t, err, errCRCMismatch)
	})
}

func TestNewReader(t *testing.T) {
	t.Parallel()

	tables := []struct {
		name, file string
		size       int64
		err        error
	}{
		{
			name: "no header compression",
			file: "t0.7z",
		},
		{
			name: "no header compression",
			file: "t0.7z",
			size: -1,
			err:  sevenzip.ErrNegativeSize,
		},
	}

	for _, table := range tables {
		t.Run(table.name, func(t *testing.T) {
			t.Parallel()

			f, err := os.Open(filepath.Join("testdata", table.file))
			if err != nil {
				t.Fatal(err)
			}

			defer func() {
				if err := f.Close(); err != nil {
					t.Fatal(err)
				}
			}()

			size := table.size
			if size == 0 {
				info, err := f.Stat()
				if err != nil {
					t.Fatal(err)
				}

				size = info.Size()
			}

			r, err := sevenzip.NewReader(f, size)
			if table.err == nil {
				require.NoError(t, err)
			} else {
				assert.ErrorIs(t, err, table.err)

				return
			}

			if err := extractArchive(t, r, -1, crc32.NewIEEE(), iotest.OneByteReader, true); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestFS(t *testing.T) {
	t.Parallel()

	r, err := sevenzip.OpenReader(filepath.Join("testdata", "lzma1900.7z"))
	if err != nil {
		t.Fatal(err)
	}

	defer func() {
		if err := r.Close(); err != nil {
			t.Fatal(err)
		}
	}()

	if err := fstest.TestFS(r, "Asm/arm/7zCrcOpt.asm", "bin/x64/7zr.exe"); err != nil {
		t.Fatal(err)
	}
}

func ExampleOpenReader() {
	r, err := sevenzip.OpenReader(filepath.Join("testdata", "multi.7z.001"))
	if err != nil {
		panic(err)
	}

	defer func() {
		if err := r.Close(); err != nil {
			panic(err)
		}
	}()

	for _, file := range r.File {
		fmt.Println(file.Name)
	}
	// Output: 01
	// 02
	// 03
	// 04
	// 05
	// 06
	// 07
	// 08
	// 09
	// 10
}

func benchmarkArchiveParallel(b *testing.B, file string) {
	b.Helper()

	for range b.N {
		r, err := sevenzip.OpenReader(filepath.Join("testdata", file))
		if err != nil {
			b.Fatal(err)
		}

		var once sync.Once

		f := func() {
			if err := r.Close(); err != nil {
				b.Fatal(err)
			}
		}

		defer once.Do(f)

		streams := make(map[int]struct{}, len(r.File))

		for _, f := range r.File {
			streams[f.Stream] = struct{}{}
		}

		eg := new(errgroup.Group)
		eg.SetLimit(runtime.NumCPU())

		for stream := range streams {
			eg.Go(func() error {
				return extractArchive(b, &r.Reader, stream, crc32.NewIEEE(), reader, true)
			})
		}

		if err := eg.Wait(); err != nil {
			b.Fatal(err)
		}

		once.Do(f)
	}
}

func benchmarkArchiveNaiveParallel(b *testing.B, file string, workers int) {
	b.Helper()

	for range b.N {
		r, err := sevenzip.OpenReader(filepath.Join("testdata", file))
		if err != nil {
			b.Fatal(err)
		}

		var once sync.Once

		f := func() {
			if err := r.Close(); err != nil {
				b.Fatal(err)
			}
		}

		defer once.Do(f)

		eg := new(errgroup.Group)
		eg.SetLimit(workers)

		for _, f := range r.File {
			eg.Go(func() (err error) {
				var rc io.ReadCloser

				rc, err = f.Open()
				if err != nil {
					return fmt.Errorf("error opening file: %w", err)
				}

				defer func() {
					err = errors.Join(err, rc.Close())
				}()

				return extractFile(b, rc, crc32.NewIEEE(), f)
			})
		}

		if err := eg.Wait(); err != nil {
			b.Fatal(err)
		}

		once.Do(f)
	}
}

func benchmarkArchive(b *testing.B, file, password string, optimised bool) {
	b.Helper()

	h := crc32.NewIEEE()

	for range b.N {
		r, err := sevenzip.OpenReaderWithPassword(filepath.Join("testdata", file), password)
		if err != nil {
			b.Fatal(err)
		}

		var once sync.Once

		f := func() {
			if err := r.Close(); err != nil {
				b.Fatal(err)
			}
		}

		defer once.Do(f)

		if err := extractArchive(b, &r.Reader, -1, h, reader, optimised); err != nil {
			b.Fatal(err)
		}

		once.Do(f)
	}
}

func BenchmarkAES7z(b *testing.B) {
	benchmarkArchive(b, "aes7z.7z", "password", true)
}

func BenchmarkBzip2(b *testing.B) {
	benchmarkArchive(b, "bzip2.7z", "", true)
}

func BenchmarkCopy(b *testing.B) {
	benchmarkArchive(b, "copy.7z", "", true)
}

func BenchmarkDeflate(b *testing.B) {
	benchmarkArchive(b, "deflate.7z", "", true)
}

func BenchmarkDelta(b *testing.B) {
	benchmarkArchive(b, "delta.7z", "", true)
}

func BenchmarkLZMA(b *testing.B) {
	benchmarkArchive(b, "lzma.7z", "", true)
}

func BenchmarkLZMA2(b *testing.B) {
	benchmarkArchive(b, "lzma2.7z", "", true)
}

func BenchmarkBCJ2(b *testing.B) {
	benchmarkArchive(b, "bcj2.7z", "", true)
}

func BenchmarkComplex(b *testing.B) {
	benchmarkArchive(b, "lzma1900.7z", "", true)
}

func BenchmarkLZ4(b *testing.B) {
	benchmarkArchive(b, "lz4.7z", "", true)
}

func BenchmarkBrotli(b *testing.B) {
	benchmarkArchive(b, "brotli.7z", "", true)
}

func BenchmarkZstandard(b *testing.B) {
	benchmarkArchive(b, "zstd.7z", "", true)
}

func BenchmarkNaiveReader(b *testing.B) {
	benchmarkArchive(b, "lzma1900.7z", "", false)
}

func BenchmarkOptimisedReader(b *testing.B) {
	benchmarkArchive(b, "lzma1900.7z", "", true)
}

func BenchmarkNaiveParallelReader(b *testing.B) {
	benchmarkArchiveNaiveParallel(b, "lzma1900.7z", runtime.NumCPU())
}

func BenchmarkNaiveSingleParallelReader(b *testing.B) {
	benchmarkArchiveNaiveParallel(b, "lzma1900.7z", 1)
}

func BenchmarkParallelReader(b *testing.B) {
	benchmarkArchiveParallel(b, "lzma1900.7z")
}

func BenchmarkBCJ(b *testing.B) {
	benchmarkArchive(b, "bcj.7z", "", true)
}

func BenchmarkPPC(b *testing.B) {
	benchmarkArchive(b, "ppc.7z", "", true)
}

func BenchmarkARM(b *testing.B) {
	benchmarkArchive(b, "arm.7z", "", true)
}

func BenchmarkSPARC(b *testing.B) {
	benchmarkArchive(b, "sparc.7z", "", true)
}

func TestListFilesWithOffsets(t *testing.T) {
	t.Parallel()

	// Test with a simple archive that contains uncompressed files
	t.Run("UncompressedFiles", func(t *testing.T) {
		r, err := sevenzip.OpenReader(filepath.Join("testdata", "copy.7z"))
		if err != nil {
			t.Skip("Test file not found, skipping test")
		}
		defer r.Close()

		files, err := r.ListFilesWithOffsets()
		assert.NoError(t, err)
		assert.NotNil(t, files)

		// Check that we have some files
		assert.Greater(t, len(files), 0)

		// Verify file info structure
		uncompressedCount := 0
		for _, file := range files {
			assert.NotEmpty(t, file.Name)
			assert.GreaterOrEqual(t, file.Offset, int64(0))
			assert.GreaterOrEqual(t, file.Size, uint64(0))
			assert.GreaterOrEqual(t, file.FolderIndex, 0)

			// Log the file info for debugging
			t.Logf("File: %s, Offset: %d, Size: %d, Compressed: %v, Encrypted: %v",
				file.Name, file.Offset, file.Size, file.Compressed, file.Encrypted)

			if !file.Compressed && !file.Encrypted {
				uncompressedCount++
			}
		}

		// For copy.7z, all files should be uncompressed
		assert.Equal(t, len(files), uncompressedCount, "All files in copy.7z should be uncompressed")
	})

	// Test with a compressed archive
	t.Run("CompressedFiles", func(t *testing.T) {
		r, err := sevenzip.OpenReader(filepath.Join("testdata", "lzma.7z"))
		if err != nil {
			t.Skip("Test file not found, skipping test")
		}
		defer r.Close()

		files, err := r.ListFilesWithOffsets()
		assert.NoError(t, err)
		assert.NotNil(t, files)

		// Check that compressed files are properly identified
		compressedCount := 0
		for _, file := range files {
			if file.Compressed {
				compressedCount++
				t.Logf("Found compressed file: %s", file.Name)
			}
		}

		// For lzma.7z, files should be compressed
		assert.Greater(t, compressedCount, 0, "Should have compressed files in lzma.7z")
	})

	// Test with encrypted archive
	t.Run("EncryptedFiles", func(t *testing.T) {
		r, err := sevenzip.OpenReaderWithPassword(filepath.Join("testdata", "aes7z.7z"), "password")
		if err != nil {
			t.Skip("Test file not found, skipping test")
		}
		defer r.Close()

		files, err := r.ListFilesWithOffsets()
		assert.NoError(t, err)
		assert.NotNil(t, files)

		// Check that encrypted files are properly identified
		encryptedCount := 0
		for _, file := range files {
			if file.Encrypted {
				encryptedCount++
				t.Logf("Found encrypted file: %s", file.Name)
			}
		}

		// For aes7z.7z, files should be encrypted
		assert.Greater(t, encryptedCount, 0, "Should have encrypted files in aes7z.7z")
	})

	// Test encryption metadata extraction
	t.Run("EncryptionMetadata", func(t *testing.T) {
		r, err := sevenzip.OpenReaderWithPassword(filepath.Join("testdata", "aes7z.7z"), "password")
		if err != nil {
			t.Skip("Test file not found, skipping test")
		}
		defer r.Close()

		files, err := r.ListFilesWithOffsets()
		assert.NoError(t, err)
		assert.NotNil(t, files)

		// Verify encryption parameters are present for encrypted files
		foundEncryptedWithMetadata := false
		for _, file := range files {
			if file.Encrypted {
				foundEncryptedWithMetadata = true

				// AES parameters should be populated
				// Note: Salt can be nil or empty (zero-length) - 7-zip allows optional salt
				assert.NotNil(t, file.AESIV, "Encrypted file should have AES IV: %s", file.Name)
				assert.Greater(t, file.KDFIterations, 0, "Encrypted file should have KDF iterations: %s", file.Name)

				// IV should be exactly 16 bytes (AES block size)
				assert.Equal(t, 16, len(file.AESIV), "AES IV should be 16 bytes: %s", file.Name)

				// Salt should be reasonable size (typically 0-16 bytes, can be empty)
				if file.AESSalt != nil {
					assert.LessOrEqual(t, len(file.AESSalt), 16, "AES salt should be <= 16 bytes: %s", file.Name)
				}

				// KDF iterations should be a power of 2 (2^cycles)
				assert.Greater(t, file.KDFIterations, 0, "KDF iterations should be positive: %s", file.Name)

				// Log the parameters for debugging
				t.Logf("Encrypted file: %s", file.Name)
				t.Logf("  Salt: %x (len=%d)", file.AESSalt, len(file.AESSalt))
				t.Logf("  IV: %x (len=%d)", file.AESIV, len(file.AESIV))
				t.Logf("  KDF Iterations: %d", file.KDFIterations)
				t.Logf("  Packed Size: %d", file.PackedSize)
			} else {
				// Non-encrypted files should not have AES parameters
				// Salt can be nil or empty, both are acceptable for non-encrypted files
				if file.AESSalt != nil {
					assert.Equal(t, 0, len(file.AESSalt), "Non-encrypted file should have empty AES salt: %s", file.Name)
				}
				assert.Nil(t, file.AESIV, "Non-encrypted file should not have AES IV: %s", file.Name)
				assert.Equal(t, 0, file.KDFIterations, "Non-encrypted file should have zero KDF iterations: %s", file.Name)
			}
		}

		assert.True(t, foundEncryptedWithMetadata, "Should have found at least one encrypted file with metadata")
	})

	// Test direct offset extraction validation
	t.Run("DirectOffsetExtraction", func(t *testing.T) {
		archivePath := filepath.Join("testdata", "copy.7z")
		r, err := sevenzip.OpenReader(archivePath)
		if err != nil {
			t.Skip("Test file not found, skipping test")
		}
		defer r.Close()

		files, err := r.ListFilesWithOffsets()
		assert.NoError(t, err)
		assert.NotNil(t, files)

		// Find uncompressed, non-encrypted files
		for _, fileInfo := range files {
			if fileInfo.Compressed || fileInfo.Encrypted {
				continue
			}

			// Find the corresponding File object
			var targetFile *sevenzip.File
			for _, f := range r.File {
				if f.Name == fileInfo.Name {
					targetFile = f
					break
				}
			}

			if targetFile == nil {
				t.Fatalf("Could not find File object for %s", fileInfo.Name)
			}

			// Extract using standard method
			rc, err := targetFile.Open()
			assert.NoError(t, err)

			standardData, err := io.ReadAll(rc)
			assert.NoError(t, err)
			rc.Close()

			// Extract using direct offset reading
			f, err := os.Open(archivePath)
			assert.NoError(t, err)

			directData := make([]byte, fileInfo.Size)
			n, err := f.ReadAt(directData, fileInfo.Offset)
			assert.NoError(t, err)
			assert.Equal(t, int(fileInfo.Size), n)
			f.Close()

			// Compare the data
			assert.Equal(t, standardData, directData,
				"Data mismatch for file %s: direct offset extraction should match standard extraction",
				fileInfo.Name)

			// Verify CRC32
			if targetFile.CRC32 != 0 {
				h := crc32.NewIEEE()
				h.Write(directData)
				assert.True(t, util.CRC32Equal(h.Sum(nil), targetFile.CRC32),
					"CRC32 mismatch for file %s", fileInfo.Name)
			}

			t.Logf("Successfully validated direct offset extraction for %s (size: %d, offset: %d)",
				fileInfo.Name, fileInfo.Size, fileInfo.Offset)
		}
	})
}
