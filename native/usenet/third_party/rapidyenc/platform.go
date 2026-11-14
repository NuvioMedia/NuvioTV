package rapidyenc

/*
#include "rapidyenc.h"
*/
import "C"
import (
	"fmt"
	"sync"
)

var version = int(C.rapidyenc_version())

// Version returns the version of the backing rapidyenc library.
func Version() string {
	return fmt.Sprintf("%d.%d.%d", version>>16&0xff, version>>8&0xff, version&0xff)
}

var (
	decodeKernel     int
	decodeKernelOnce sync.Once
)

// DecodeKernel returns the name of the implementation being used for decode operations
func DecodeKernel() string {
	decodeKernelOnce.Do(func() {
		maybeInitDecode()
		decodeKernel = int(C.rapidyenc_decode_kernel())
	})

	return kernelToString(decodeKernel)
}

var (
	encodeKernel     int
	encodeKernelOnce sync.Once
)

// EncodeKernel returns the name of the implementation being used for encode operations
func EncodeKernel() string {
	encodeKernelOnce.Do(func() {
		maybeInitEncode()
		encodeKernel = int(C.rapidyenc_encode_kernel())
	})

	return kernelToString(encodeKernel)
}

const (
	kernelGENERIC  = 0
	kernelSSE2     = 0x100
	kernelSSSE3    = 0x200
	kernelAVX      = 0x381
	kernelAVX2     = 0x403
	kernelVBMI2    = 0x603
	kernelNEON     = 0x1000
	kernelRVV      = 0x10000
	kernelPCLMUL   = 0x340
	kernelVPCLMUL  = 0x440
	kernelARMCRC   = 8
	kernelARMPMULL = 0x48
	kernelZBC      = 16
)

func kernelToString(k int) string {
	switch k {
	case kernelGENERIC:
		return "generic"
	case kernelSSE2:
		return "SSE2"
	case kernelSSSE3:
		return "SSSE3"
	case kernelAVX:
		return "AVX"
	case kernelAVX2:
		return "AVX2"
	case kernelVBMI2:
		return "VBMI2"
	case kernelNEON:
		return "NEON"
	case kernelPCLMUL:
		return "PCLMUL"
	case kernelVPCLMUL:
		return "VPCLMUL"
	case kernelARMCRC:
		return "ARM-CRC"
	case kernelRVV:
		return "RVV"
	case kernelARMPMULL:
		return "ARM-CRC + PMULL"
	case kernelZBC:
		return "Zbkc"
	default:
		return "unknown"
	}
}
