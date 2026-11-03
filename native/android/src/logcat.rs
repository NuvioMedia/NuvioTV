//! The Rust code's `log` records in logcat, where the app's problem reports read them.

use std::ffi::{CString, c_char, c_int};

use log::{Level, LevelFilter, Log, Metadata, Record};

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

struct Logcat;

impl Log for Logcat {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= Level::Info
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        // android/log.h's priorities.
        let priority = match record.level() {
            Level::Error => 6,
            Level::Warn => 5,
            Level::Info => 4,
            _ => 3,
        };
        let tag = CString::new(record.target());
        let text = CString::new(record.args().to_string());
        if let (Ok(tag), Ok(text)) = (tag, text) {
            unsafe { __android_log_write(priority, tag.as_ptr(), text.as_ptr()) };
        }
    }

    fn flush(&self) {}
}

pub fn init() {
    if log::set_logger(&Logcat).is_ok() {
        log::set_max_level(LevelFilter::Info);
    }
}
