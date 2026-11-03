//! libavcodec, loaded at runtime so builds need no FFmpeg. Of FFmpeg's structs only the start of
//! AVFrame is read, which no release has changed.

use std::ffi::{CStr, CString, c_char, c_int, c_uint, c_void};
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::{Mutex, OnceLock};

use libloading::Library;

const PADDING: usize = 64;

#[repr(C)]
struct RawFrame {
    data: [*const u8; 8],
    linesize: [c_int; 8],
    extended_data: *mut *mut u8,
    width: c_int,
    height: c_int,
    nb_samples: c_int,
    format: c_int,
}

struct Api {
    find_decoder_by_name: unsafe extern "C" fn(*const c_char) -> *const c_void,
    alloc_context3: unsafe extern "C" fn(*const c_void) -> *mut c_void,
    open2: unsafe extern "C" fn(*mut c_void, *const c_void, *mut *mut c_void) -> c_int,
    free_context: unsafe extern "C" fn(*mut *mut c_void),
    send_packet: unsafe extern "C" fn(*mut c_void, *const c_void) -> c_int,
    receive_frame: unsafe extern "C" fn(*mut c_void, *mut RawFrame) -> c_int,
    flush_buffers: unsafe extern "C" fn(*mut c_void),
    packet_alloc: unsafe extern "C" fn() -> *mut c_void,
    packet_free: unsafe extern "C" fn(*mut *mut c_void),
    packet_from_data: unsafe extern "C" fn(*mut c_void, *mut u8, c_int) -> c_int,
    packet_unref: unsafe extern "C" fn(*mut c_void),
    malloc: unsafe extern "C" fn(usize) -> *mut u8,
    free: unsafe extern "C" fn(*mut c_void),
    frame_alloc: unsafe extern "C" fn() -> *mut RawFrame,
    frame_free: unsafe extern "C" fn(*mut *mut RawFrame),
    frame_unref: unsafe extern "C" fn(*mut RawFrame),
    dict_set: unsafe extern "C" fn(*mut *mut c_void, *const c_char, *const c_char, c_int) -> c_int,
    dict_free: unsafe extern "C" fn(*mut *mut c_void),
    strerror: unsafe extern "C" fn(c_int, *mut c_char, usize) -> c_int,
    pix_fmt_name: unsafe extern "C" fn(c_int) -> *const c_char,
    opt_get_int: unsafe extern "C" fn(*mut c_void, *const c_char, c_int, *mut i64) -> c_int,
    version: c_uint,
    _avcodec: Library,
    _avutil: Library,
}

unsafe impl Send for Api {}
unsafe impl Sync for Api {}

static DIRS: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());
static API: OnceLock<Result<Api, String>> = OnceLock::new();

/// Folders searched for FFmpeg's libraries before the system's own search, set before the first
/// decoder opens.
pub fn library_dirs(dirs: Vec<PathBuf>) {
    if let Ok(mut d) = DIRS.lock() {
        *d = dirs;
    }
}

/// Loads libavcodec on first use.
pub fn ffmpeg_version() -> Result<String, String> {
    let v = api()?.version;
    Ok(format!("{}.{}.{}", v >> 16, (v >> 8) & 0xff, v & 0xff))
}

fn api() -> Result<&'static Api, String> {
    API.get_or_init(Api::find).as_ref().map_err(Clone::clone)
}

/// libavutil's major has been libavcodec's minus two since FFmpeg 4.
fn names(major: u32) -> (String, String) {
    let util = major.saturating_sub(2);
    if cfg!(target_os = "android") {
        ("libavcodec.so".into(), "libavutil.so".into())
    } else if cfg!(windows) {
        (format!("avcodec-{major}.dll"), format!("avutil-{util}.dll"))
    } else if cfg!(target_os = "macos") {
        (
            format!("libavcodec.{major}.dylib"),
            format!("libavutil.{util}.dylib"),
        )
    } else {
        (
            format!("libavcodec.so.{major}"),
            format!("libavutil.so.{util}"),
        )
    }
}

// SAFETY (both loaders): FFmpeg's libraries run no initialisers that depend on us.
fn open(dir: Option<&Path>, name: &str) -> Result<Library, libloading::Error> {
    match dir {
        Some(dir) => open_in(&dir.join(name)),
        None => unsafe { Library::new(name) },
    }
}

/// The folder's own libraries satisfy each other's imports.
#[cfg(windows)]
fn open_in(path: &Path) -> Result<Library, libloading::Error> {
    use libloading::os::windows;
    unsafe { windows::Library::load_with_flags(path, windows::LOAD_WITH_ALTERED_SEARCH_PATH) }
        .map(Into::into)
}

#[cfg(not(windows))]
fn open_in(path: &Path) -> Result<Library, libloading::Error> {
    unsafe { Library::new(path) }
}

impl Api {
    fn find() -> Result<Api, String> {
        let dirs = DIRS.lock().map(|d| d.clone()).unwrap_or_default();
        let majors: Vec<u32> = if cfg!(target_os = "android") {
            vec![0]
        } else {
            (59..=66).rev().collect()
        };
        let mut first = None;
        for major in majors {
            let (codec, util) = names(major);
            for dir in dirs.iter().map(|d| Some(d.as_path())).chain([None]) {
                // libavutil first, so libavcodec links to the same folder's copy.
                let loaded = open(dir, &util).and_then(|u| Ok((open(dir, &codec)?, u)));
                match loaded {
                    Ok((avcodec, avutil)) => return Api::load(avcodec, avutil),
                    Err(e) => {
                        first.get_or_insert(e);
                    }
                }
            }
        }
        Err(format!(
            "no FFmpeg libraries: {}",
            first.map(|e| e.to_string()).unwrap_or_default()
        ))
    }

    fn load(avcodec: Library, avutil: Library) -> Result<Api, String> {
        macro_rules! sym {
            ($lib:ident, $name:literal) => {
                // SAFETY: the signatures match FFmpeg's headers in every release we load.
                *unsafe { $lib.get(concat!($name, "\0").as_bytes()) }
                    .map_err(|e| format!("FFmpeg is missing {}: {e}", $name))?
            };
        }
        let version: unsafe extern "C" fn() -> c_uint = sym!(avcodec, "avcodec_version");
        Ok(Api {
            find_decoder_by_name: sym!(avcodec, "avcodec_find_decoder_by_name"),
            alloc_context3: sym!(avcodec, "avcodec_alloc_context3"),
            open2: sym!(avcodec, "avcodec_open2"),
            free_context: sym!(avcodec, "avcodec_free_context"),
            send_packet: sym!(avcodec, "avcodec_send_packet"),
            receive_frame: sym!(avcodec, "avcodec_receive_frame"),
            flush_buffers: sym!(avcodec, "avcodec_flush_buffers"),
            packet_alloc: sym!(avcodec, "av_packet_alloc"),
            packet_free: sym!(avcodec, "av_packet_free"),
            packet_from_data: sym!(avcodec, "av_packet_from_data"),
            packet_unref: sym!(avcodec, "av_packet_unref"),
            malloc: sym!(avutil, "av_malloc"),
            free: sym!(avutil, "av_free"),
            frame_alloc: sym!(avutil, "av_frame_alloc"),
            frame_free: sym!(avutil, "av_frame_free"),
            frame_unref: sym!(avutil, "av_frame_unref"),
            dict_set: sym!(avutil, "av_dict_set"),
            dict_free: sym!(avutil, "av_dict_free"),
            strerror: sym!(avutil, "av_strerror"),
            pix_fmt_name: sym!(avutil, "av_get_pix_fmt_name"),
            opt_get_int: sym!(avutil, "av_opt_get_int"),
            version: unsafe { version() },
            _avcodec: avcodec,
            _avutil: avutil,
        })
    }

    fn error(&self, ret: c_int) -> String {
        let mut buf = [0 as c_char; 256];
        unsafe { (self.strerror)(ret, buf.as_mut_ptr(), buf.len()) };
        unsafe { CStr::from_ptr(buf.as_ptr()) }
            .to_string_lossy()
            .into_owned()
    }
}

pub struct Frame {
    pub width: c_int,
    pub height: c_int,
    pub bits: c_int,
    pub log2_chroma_w: c_int,
    pub log2_chroma_h: c_int,
    pub planar_yuv: c_int,
    pub data: [*const u8; 3],
    pub linesize: [c_int; 3],
    pub color_range: c_int,
    pub color_primaries: c_int,
    pub color_trc: c_int,
    pub colorspace: c_int,
    pix_fmt: *const c_char,
}

impl Frame {
    pub fn pix_fmt(&self) -> String {
        if self.pix_fmt.is_null() {
            return "?".into();
        }
        unsafe { CStr::from_ptr(self.pix_fmt) }
            .to_string_lossy()
            .into_owned()
    }
}

/// Bits and chroma subsampling of a little-endian planar YUV format.
fn planar_yuv(name: &str) -> Option<(c_int, c_int, c_int)> {
    let rest = name
        .strip_prefix("yuvj")
        .or_else(|| name.strip_prefix("yuv"))?;
    let (sub, depth) = rest.split_once('p')?;
    let (w, h) = match sub {
        "420" => (1, 1),
        "422" => (1, 0),
        "444" => (0, 0),
        "440" => (0, 1),
        "411" => (2, 0),
        "410" => (2, 2),
        _ => return None,
    };
    let bits = match depth {
        "" => 8,
        d => d.strip_suffix("le")?.parse().ok()?,
    };
    Some((bits, w, h))
}

pub struct Options {
    pub threads: i32,
    pub skip_loop_filter: bool,
    pub fast: bool,
}

pub struct Decoder {
    api: &'static Api,
    ctx: *mut c_void,
    packet: *mut c_void,
    raw: *mut RawFrame,
    frame: Frame,
}

unsafe impl Send for Decoder {}

impl Decoder {
    pub fn open(name: &str, o: &Options) -> Result<Decoder, String> {
        let api = api()?;
        let c_name = CString::new(name).map_err(|e| e.to_string())?;
        let codec = unsafe { (api.find_decoder_by_name)(c_name.as_ptr()) };
        if codec.is_null() {
            return Err(format!("no decoder {name}"));
        }
        let d = Decoder {
            api,
            ctx: unsafe { (api.alloc_context3)(codec) },
            packet: unsafe { (api.packet_alloc)() },
            raw: unsafe { (api.frame_alloc)() },
            frame: Frame {
                width: 0,
                height: 0,
                bits: 8,
                log2_chroma_w: 0,
                log2_chroma_h: 0,
                planar_yuv: 0,
                data: [ptr::null(); 3],
                linesize: [0; 3],
                color_range: 0,
                color_primaries: 2,
                color_trc: 2,
                colorspace: 2,
                pix_fmt: ptr::null(),
            },
        };
        if d.ctx.is_null() || d.packet.is_null() || d.raw.is_null() {
            return Err("out of memory".into());
        }
        let threads = o.threads.to_string();
        let mut options = vec![
            ("threads", threads.as_str()),
            ("thread_type", "slice"),
            ("flags", "+low_delay"),
            // libdav1d: keep film grain out of the picture.
            ("export_side_data", "film_grain"),
        ];
        if o.skip_loop_filter {
            options.push(("skip_loop_filter", "all"));
        }
        if o.fast {
            options.push(("flags2", "+fast"));
        }
        let mut dict = ptr::null_mut();
        for (key, value) in options {
            let (key, value) = (CString::new(key).unwrap(), CString::new(value).unwrap());
            unsafe { (api.dict_set)(&mut dict, key.as_ptr(), value.as_ptr(), 0) };
        }
        let ret = unsafe { (api.open2)(d.ctx, codec, &mut dict) };
        unsafe { (api.dict_free)(&mut dict) };
        if ret < 0 {
            return Err(api.error(ret));
        }
        Ok(d)
    }

    /// Decodes one packet; the frame borrows the decoder until the next call.
    pub fn decode(&mut self, packet: &[u8]) -> Result<&Frame, String> {
        let api = self.api;
        unsafe {
            (api.frame_unref)(self.raw);
            (api.flush_buffers)(self.ctx);
            let buf = (api.malloc)(packet.len() + PADDING);
            if buf.is_null() {
                return Err("out of memory".into());
            }
            ptr::copy_nonoverlapping(packet.as_ptr(), buf, packet.len());
            ptr::write_bytes(buf.add(packet.len()), 0, PADDING);
            let ret = (api.packet_from_data)(self.packet, buf, packet.len() as c_int);
            if ret < 0 {
                (api.free)(buf.cast());
                return Err(api.error(ret));
            }
            let ret = (api.send_packet)(self.ctx, self.packet);
            (api.packet_unref)(self.packet);
            if ret < 0 {
                return Err(api.error(ret));
            }
            (api.send_packet)(self.ctx, ptr::null());
            let ret = (api.receive_frame)(self.ctx, self.raw);
            if ret < 0 {
                return Err(api.error(ret));
            }
            let raw = &*self.raw;
            let option = |name: &CStr| {
                let mut v = 0i64;
                (api.opt_get_int)(self.ctx, name.as_ptr(), 0, &mut v);
                v as c_int
            };
            let f = &mut self.frame;
            f.width = raw.width;
            f.height = raw.height;
            f.pix_fmt = (api.pix_fmt_name)(raw.format);
            let layout = (!f.pix_fmt.is_null())
                .then(|| CStr::from_ptr(f.pix_fmt).to_str().ok())
                .flatten()
                .and_then(planar_yuv);
            f.planar_yuv = layout.is_some() as c_int;
            (f.bits, f.log2_chroma_w, f.log2_chroma_h) = layout.unwrap_or((8, 0, 0));
            f.data = [raw.data[0], raw.data[1], raw.data[2]];
            f.linesize = [raw.linesize[0], raw.linesize[1], raw.linesize[2]];
            f.color_range = option(c"color_range");
            f.color_primaries = option(c"color_primaries");
            f.color_trc = option(c"color_trc");
            f.colorspace = option(c"colorspace");
        }
        Ok(&self.frame)
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe {
            (self.api.free_context)(&mut self.ctx);
            (self.api.packet_free)(&mut self.packet);
            (self.api.frame_free)(&mut self.raw);
        }
    }
}
