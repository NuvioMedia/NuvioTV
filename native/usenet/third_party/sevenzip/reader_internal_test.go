//nolint:wrapcheck
package sevenzip

import (
	"errors"
	iofs "io/fs"
	"os"
	"testing"
	"time"

	"github.com/spf13/afero"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

var errAssertion = errors.New("type assertion failed")

type mockFileInfo struct {
	mock.Mock
}

func (m *mockFileInfo) Name() string {
	return m.Called().String(0)
}

func (m *mockFileInfo) Size() int64 {
	args := m.Called()

	size, ok := args.Get(0).(int64)
	if !ok {
		panic(errAssertion)
	}

	return size
}

func (m *mockFileInfo) Mode() iofs.FileMode {
	args := m.Called()

	mode, ok := args.Get(0).(iofs.FileMode)
	if !ok {
		panic(errAssertion)
	}

	return mode
}

func (m *mockFileInfo) ModTime() time.Time {
	args := m.Called()

	modTime, ok := args.Get(0).(time.Time)
	if !ok {
		panic(errAssertion)
	}

	return modTime
}

func (m *mockFileInfo) IsDir() bool {
	return m.Called().Bool(0)
}

func (m *mockFileInfo) Sys() any {
	return m.Called().Get(0)
}

func newMockFileInfo(tb testing.TB) *mockFileInfo {
	tb.Helper()

	mock := new(mockFileInfo)
	mock.Test(tb)

	tb.Cleanup(func() { mock.AssertExpectations(tb) })

	return mock
}

type mockFile struct {
	mock.Mock
}

func (m *mockFile) Name() string {
	return m.Called().String(0)
}

func (m *mockFile) Readdir(count int) ([]os.FileInfo, error) {
	args := m.Called(count)

	infos, ok := args.Get(0).([]os.FileInfo)
	if infos != nil && !ok {
		panic(errAssertion)
	}

	return infos, args.Error(1)
}

func (m *mockFile) Readdirnames(n int) ([]string, error) {
	args := m.Called(n)

	names, ok := args.Get(0).([]string)
	if names != nil && !ok {
		panic(errAssertion)
	}

	return names, args.Error(1)
}

func (m *mockFile) Stat() (os.FileInfo, error) {
	args := m.Called()

	info, ok := args.Get(0).(os.FileInfo)
	if info != nil && !ok {
		panic(errAssertion)
	}

	return info, args.Error(1)
}

func (m *mockFile) Sync() error {
	return m.Called().Error(0)
}

func (m *mockFile) Truncate(size int64) error {
	return m.Called(size).Error(0)
}

func (m *mockFile) WriteString(s string) (int, error) {
	args := m.Called(s)

	return args.Int(0), args.Error(1)
}

func (m *mockFile) Close() error {
	return m.Called().Error(0)
}

func (m *mockFile) Read(p []byte) (int, error) {
	args := m.Called(p)

	return args.Int(0), args.Error(1)
}

func (m *mockFile) ReadAt(p []byte, off int64) (int, error) {
	args := m.Called(p, off)

	return args.Int(0), args.Error(1)
}

func (m *mockFile) Seek(offset int64, whence int) (int64, error) {
	args := m.Called(offset, whence)

	n, ok := args.Get(0).(int64)
	if !ok {
		panic(errAssertion)
	}

	return n, args.Error(1)
}

func (m *mockFile) Write(p []byte) (int, error) {
	args := m.Called(p)

	return args.Int(0), args.Error(1)
}

func (m *mockFile) WriteAt(p []byte, off int64) (int, error) {
	args := m.Called(p, off)

	return args.Int(0), args.Error(1)
}

func newMockFile(tb testing.TB) *mockFile {
	tb.Helper()

	mock := new(mockFile)
	mock.Test(tb)

	tb.Cleanup(func() { mock.AssertExpectations(tb) })

	return mock
}

type mockFs struct {
	mock.Mock
}

func (m *mockFs) Create(name string) (afero.File, error) {
	args := m.Called(name)

	file, ok := args.Get(0).(afero.File)
	if file != nil && !ok {
		panic(errAssertion)
	}

	return file, args.Error(1)
}

func (m *mockFs) Mkdir(name string, perm os.FileMode) error {
	return m.Called(name, perm).Error(0)
}

func (m *mockFs) MkdirAll(path string, perm os.FileMode) error {
	return m.Called(path, perm).Error(0)
}

func (m *mockFs) Open(name string) (afero.File, error) {
	args := m.Called(name)

	file, ok := args.Get(0).(afero.File)
	if file != nil && !ok {
		panic(errAssertion)
	}

	return file, args.Error(1)
}

func (m *mockFs) OpenFile(name string, flag int, perm os.FileMode) (afero.File, error) {
	args := m.Called(name, flag, perm)

	file, ok := args.Get(0).(afero.File)
	if file != nil && !ok {
		panic(errAssertion)
	}

	return file, args.Error(1)
}

func (m *mockFs) Remove(name string) error {
	return m.Called(name).Error(0)
}

func (m *mockFs) RemoveAll(path string) error {
	return m.Called(path).Error(0)
}

func (m *mockFs) Rename(oldname, newname string) error {
	return m.Called(oldname, newname).Error(0)
}

func (m *mockFs) Stat(name string) (os.FileInfo, error) {
	args := m.Called(name)

	info, ok := args.Get(0).(os.FileInfo)
	if info != nil && !ok {
		panic(errAssertion)
	}

	return info, args.Error(1)
}

func (m *mockFs) Name() string {
	return m.Called().String(0)
}

func (m *mockFs) Chmod(name string, mode os.FileMode) error {
	return m.Called(name, mode).Error(0)
}

func (m *mockFs) Chown(name string, uid, gid int) error {
	return m.Called(name, uid, gid).Error(0)
}

func (m *mockFs) Chtimes(name string, atime, mtime time.Time) error {
	return m.Called(name, atime, mtime).Error(0)
}

func newMockFs(tb testing.TB) *mockFs {
	tb.Helper()

	mock := new(mockFs)
	mock.Test(tb)

	tb.Cleanup(func() { mock.AssertExpectations(tb) })

	return mock
}

var (
	_ os.FileInfo = new(mockFileInfo)
	_ afero.File  = new(mockFile)
	_ afero.Fs    = new(mockFs)
)

//nolint:funlen
func TestOpenReader(t *testing.T) {
	t.Parallel()

	tables := []struct {
		name string
		fs   func(tb testing.TB) afero.Fs
		err  error
	}{
		{
			name: "ok",
			fs: func(tb testing.TB) afero.Fs {
				tb.Helper()

				info := newMockFileInfo(tb)
				info.On("Size").Return(int64(100)).Twice()

				one := newMockFile(tb)
				one.On("Stat").Return(info, nil).Once()
				one.On("Close").Return(nil).Once()

				two := newMockFile(tb)
				two.On("Stat").Return(info, nil).Once()
				two.On("Close").Return(nil).Once()

				fs := newMockFs(tb)
				fs.On("Open", "filename.7z.001").Return(one, nil).Once()
				fs.On("Open", "filename.7z.002").Return(two, nil).Once()
				fs.On("Open", "filename.7z.003").Return(nil, iofs.ErrNotExist).Once()

				return fs
			},
		},
		{
			name: "first open error",
			fs: func(tb testing.TB) afero.Fs {
				tb.Helper()

				fs := newMockFs(tb)
				fs.On("Open", "filename.7z.001").Return(nil, iofs.ErrPermission).Once()

				return fs
			},
			err: iofs.ErrPermission,
		},
		{
			name: "first stat error",
			fs: func(tb testing.TB) afero.Fs {
				tb.Helper()

				one := newMockFile(tb)
				one.On("Stat").Return(nil, iofs.ErrPermission).Once()
				one.On("Close").Return(nil).Once()

				fs := newMockFs(tb)
				fs.On("Open", "filename.7z.001").Return(one, nil).Once()

				return fs
			},
			err: iofs.ErrPermission,
		},
		{
			name: "multi open error",
			fs: func(tb testing.TB) afero.Fs {
				tb.Helper()

				info := newMockFileInfo(tb)
				info.On("Size").Return(int64(100)).Once()

				one := newMockFile(tb)
				one.On("Stat").Return(info, nil).Once()
				one.On("Close").Return(nil).Once()

				fs := newMockFs(tb)
				fs.On("Open", "filename.7z.001").Return(one, nil).Once()
				fs.On("Open", "filename.7z.002").Return(nil, iofs.ErrPermission).Once()

				return fs
			},
			err: iofs.ErrPermission,
		},
		{
			name: "multi stat error",
			fs: func(tb testing.TB) afero.Fs {
				tb.Helper()

				info := newMockFileInfo(tb)
				info.On("Size").Return(int64(100)).Once()

				one := newMockFile(tb)
				one.On("Stat").Return(info, nil).Once()
				one.On("Close").Return(nil).Once()

				two := newMockFile(tb)
				two.On("Stat").Return(nil, iofs.ErrPermission).Once()
				two.On("Close").Return(nil).Once()

				fs := newMockFs(tb)
				fs.On("Open", "filename.7z.001").Return(one, nil).Once()
				fs.On("Open", "filename.7z.002").Return(two, nil).Once()

				return fs
			},
			err: iofs.ErrPermission,
		},
	}

	for _, table := range tables {
		t.Run(table.name, func(t *testing.T) {
			t.Parallel()

			_, _, files, err := openReader(table.fs(t), "filename.7z.001")
			if table.err == nil {
				require.NoError(t, err)
			} else {
				assert.ErrorIs(t, err, table.err)

				return
			}

			defer func() {
				for _, f := range files {
					if err := f.Close(); err != nil {
						t.Fatal(err)
					}
				}
			}()
		})
	}
}
