/// Moves the calling thread to the idle scheduling class, below playback; threads it creates inherit it.
#[cfg(unix)]
pub fn background() {
    unsafe {
        libc::setpriority(libc::PRIO_PROCESS, 0, 19);
        #[cfg(any(target_os = "linux", target_os = "android"))]
        libc::sched_setscheduler(
            0,
            libc::SCHED_IDLE,
            &libc::sched_param { sched_priority: 0 },
        );
    }
}

#[cfg(windows)]
pub fn background() {
    use windows_sys::Win32::System::Threading::{
        GetCurrentThread, SetThreadPriority, THREAD_PRIORITY_IDLE,
    };
    unsafe { SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_IDLE) };
}
