#ifndef __RAPIDYENC_H
#define __RAPIDYENC_H
#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stddef.h>

#ifndef RAPIDYENC_API
# define RAPIDYENC_API
#endif

/**
 * Version, in 0xMMmmpp format, where MM=major version, mm=minor version, pp=patch version
 */
#define RAPIDYENC_VERSION 0x010101
RAPIDYENC_API int rapidyenc_version(void); // returns RAPIDYENC_VERSION

/**
 * For determining which kernel was selected for the current CPU
 * Note that if this was compiled with the BUILD_NATIVE option set, values may not correspond with any of those below
 */
#define RYKERN_GENERIC 0   // generic kernel chosen
// x86 specific encode/decode kernels
#define RYKERN_SSE2 0x100
#define RYKERN_SSSE3 0x200
#define RYKERN_AVX 0x381
#define RYKERN_AVX2 0x403
#define RYKERN_VBMI2 0x603
// ARM specific encode/decode kernels
#define RYKERN_NEON 0x1000
// RISC-V specific encode/decode kernels
#define RYKERN_RVV 0x10000
// x86 specific CRC32 kernels
#define RYKERN_PCLMUL 0x340
#define RYKERN_VPCLMUL 0x440
// ARM specific CRC32 kernels
#define RYKERN_ARMCRC 8
#define RYKERN_ARMPMULL 0x48
// RISC-V specific CRC32 kernels
#define RYKERN_ZBC 16


/***** ENCODE *****/
#ifndef RAPIDYENC_DISABLE_ENCODE
/**
 * Initialise global state of the encoder (sets up lookup tables and performs CPU detection).
 * As it alters global state, this function only needs to be called once, and is not thread-safe (subsequent calls to this will do nothing).
 * This must be called before any other rapidyenc_encode* functions are called.
 */
RAPIDYENC_API void rapidyenc_encode_init(void);


/**
 * yEnc encode the buffer at `src` (of length `src_length`) and write it to `dest`
 * Returns the number of bytes written to `dest`
 * `dest` is assumed to be large enough to hold the output - use `rapidyenc_encode_max_length` to compute the necessary size of `dest`
 *
 * This is effectively an alias for `rapidyenc_encode_ex(128, NULL, src, dest, src_length, 1)`
 */
RAPIDYENC_API size_t rapidyenc_encode(const void* __restrict src, void* __restrict dest, size_t src_length);

/**
 * Like `rapidyenc_encode` but provide the ability to perform incremental processing
 * This is done by keeping track of the column position, and you'll need to indicate if this is the last chunk of an article.
 *
 * - line_size: the target number of bytes for each line. 128 is commonly used
 * - column [in/out]: the column in the line to start at. This will be updated with the column position after encoding. Articles will typically start at 0. Pass in NULL to not track the column.
 * - src: the source data to encode
 * - dest: where to write the encoded data to. This cannot alias the source data
 * - src_length: the length of the source data to encode. Note that the length of the output buffer is assumed to be large enough (see `rapidyenc_encode_max_length`)
 * - is_end: if not 0, this is the final chunk of the article. Setting this ensures that trailing whitespace is properly escaped
 */
RAPIDYENC_API size_t rapidyenc_encode_ex(int line_size, int* column, const void* __restrict src, void* __restrict dest, size_t src_length, int is_end);

/**
 * Returns the kernel/ISA level used for encoding
 * Values correspond with RYKERN_* definitions above
 */
RAPIDYENC_API int rapidyenc_encode_kernel();

#endif // !defined(RAPIDYENC_DISABLE_ENCODE)

/**
 * Returns the maximum possible length of yEnc encoded output, given an input of `length` bytes
 * This function does also include additional padding needed by rapidyenc's implementation.
 * Note that this function doesn't require `rapidyenc_encode_init` to be called beforehand
 */
RAPIDYENC_API size_t rapidyenc_encode_max_length(size_t length, int line_size);



/***** DECODE *****/
#ifndef RAPIDYENC_DISABLE_DECODE
/**
 * Current decoder state, for incremental decoding
 * The values here refer to the previously seen characters in the stream, which influence how some sequences need to be handled
 * The shorthands represent:
 *  CR (\r), LF (\n), EQ (=), DT (.)
 */
typedef enum {
	RYDEC_STATE_CRLF, // default
	RYDEC_STATE_EQ,
	RYDEC_STATE_CR,
	RYDEC_STATE_NONE,
	RYDEC_STATE_CRLFDT,
	RYDEC_STATE_CRLFDTCR,
	RYDEC_STATE_CRLFEQ // may actually be "\r\n.=" in raw decoder
} RapidYencDecoderState;

/**
 * End state for incremental decoding (whether the end of the yEnc data was reached)
 */
typedef enum {
	RYDEC_END_NONE,    // end not reached
	RYDEC_END_CONTROL, // \r\n=y sequence found, src points to byte after 'y'
	RYDEC_END_ARTICLE  // \r\n.\r\n sequence found, src points to byte after last '\n'
} RapidYencDecoderEnd;

/**
 * Initialise global state of the decoder (sets up lookup tables and performs CPU detection).
 * As it alters global state, this function only needs to be called once, and is not thread-safe (subsequent calls to this will do nothing).
 * This must be called before any other rapidyenc_decode* functions are called.
 */
RAPIDYENC_API void rapidyenc_decode_init(void);

/**
 * yEnc decode the buffer at `src` (of length `src_length`) and write it to `dest`
 * Returns the number of bytes written to `dest`
 * 
 * This is effectively an alias for `rapidyenc_decode_ex(1, src, dest, src_length, NULL)`
 */
RAPIDYENC_API size_t rapidyenc_decode(const void* src, void* dest, size_t src_length);

/**
 * yEnc decode the buffer at `src` (of length `src_length`) and write it to `dest`
 * Returns the number of bytes written to `dest`
 * 
 * If `is_raw` is non-zero, will also handle NNTP dot unstuffing
 * `state` can be used to track the decoder state, if incremental decoding is desired. Set to NULL if tracking is not needed
 * `src` and `dest` are allowed to point to the same location for in-situ decoding, otherwise `dest` is assumed to be at least `src_length` in size
 */
RAPIDYENC_API size_t rapidyenc_decode_ex(int is_raw, const void* src, void* dest, size_t src_length, RapidYencDecoderState* state);

/**
 * Like `rapidyenc_decode`, but stops decoding when a yEnc/NNTP end sequence is found
 * Returns whether such an end sequence was found
 * Note that the `is_raw` parameter in `rapidyenc_decode` is assumed to be True here
 * 
 * `src` and `dest` are pointers of pointers here, as they'll both be updated to the positions after decoding
 * The length of the written data can thus be derived from the post-decode `dest` minus the pre-decode `dest`
 * Whilst `src` and `dest` can point to the same memory, the pointers themselves should be different. In other words, `**src == **dest` is fine, but `*src == *dest` is not
 */
RAPIDYENC_API RapidYencDecoderEnd rapidyenc_decode_incremental(const void** src, void** dest, size_t src_length, RapidYencDecoderState* state);

/**
 * Returns the kernel/ISA level used for decoding
 * Values correspond with RYKERN_* definitions above
 */
RAPIDYENC_API int rapidyenc_decode_kernel();

#endif // !defined(RAPIDYENC_DISABLE_DECODE)


/***** CRC32 *****/
#ifndef RAPIDYENC_DISABLE_CRC
/**
 * Initialise global state for CRC32 computation (performs CPU detection).
 * As it alters global state, this function only needs to be called once, and is not thread-safe (subsequent calls to this will do nothing).
 * This must be called before any other rapidyenc_crc* functions are called.
 */
RAPIDYENC_API void rapidyenc_crc_init(void);

/**
 * Returns the CRC32 hash of `src` (of length `src_length`), with initial CRC32 value `init_crc`
 * The initial value should be 0 unless this is a subsequent call during incremental hashing
 */
RAPIDYENC_API uint32_t rapidyenc_crc(const void* src, size_t src_length, uint32_t init_crc);

/**
 * Given `crc1 = CRC32(data1)` and `crc2 = CRC32(data2)`, returns CRC32(data1 + data2)
 * `length2` refers to the length of 'data2'
 */
RAPIDYENC_API uint32_t rapidyenc_crc_combine(uint32_t crc1, const uint32_t crc2, uint64_t length2);

/**
 * Returns `rapidyenc_crc(src, length, init_crc)` where 'src' is all zeroes
 */
RAPIDYENC_API uint32_t rapidyenc_crc_zeros(uint32_t init_crc, uint64_t length);

/**
 * Performs the inverse of `rapidyenc_crc_zeros`:
 * Given `init_crc = CRC32(data + [0]*length)`, returns `CRC32(data)`
 */
RAPIDYENC_API uint32_t rapidyenc_crc_unzero(uint32_t init_crc, uint64_t length);

/**
 * Returns the product of `a` and `b` in the CRC32 field
 */
RAPIDYENC_API uint32_t rapidyenc_crc_multiply(uint32_t a, uint32_t b);

/**
 * Returns 2**n in the CRC32 field. n can be negative
 */
RAPIDYENC_API uint32_t rapidyenc_crc_2pow(int64_t n);

/**
 * Returns 2**(8n) in the CRC32 field
 * Similar to `rapidyenc_crc_2pow(8*n)`, but avoids overflow and n cannot be negative
 */
RAPIDYENC_API uint32_t rapidyenc_crc_256pow(uint64_t n);

/**
 * Returns the kernel/ISA level used for CRC32 computation
 * Values correspond with RYKERN_* definitions above
 */
RAPIDYENC_API int rapidyenc_crc_kernel();

#endif // !defined(RAPIDYENC_DISABLE_CRC)

#ifdef __cplusplus
}
#endif
#endif /* __RAPIDYENC_H */
