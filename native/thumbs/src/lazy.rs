use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use aiostreams_container::Options;

use crate::{Session, Settings, Thumbnail};

pub enum Event {
    Thumbnail(Arc<Thumbnail>),
    /// Why the file has no thumbnails.
    Unavailable(String),
}

/// How long a file whose requests failed waits before it opens again.
const RETRY: Duration = Duration::from_secs(10);

enum Stage {
    /// Not open yet, and after a failed request not to open again before the instant.
    Idle(Box<(Options, Settings)>, Option<Instant>),
    Opening(Option<u64>),
    Open(Session),
    Failed,
}

struct Inner {
    url: String,
    stage: Mutex<Stage>,
    closed: AtomicBool,
    on: Box<dyn Fn(Event) + Send + Sync>,
}

impl Inner {
    fn emit(&self, event: Event) {
        if !self.closed.load(Ordering::Acquire) {
            (self.on)(event);
        }
    }
}

/// A file's [`Session`], opened in the background on the first request so asking never blocks;
/// nothing is reported once it is dropped.
pub struct Thumbnails {
    inner: Arc<Inner>,
}

impl Thumbnails {
    pub fn new(
        url: &str,
        options: Options,
        settings: Settings,
        on: impl Fn(Event) + Send + Sync + 'static,
    ) -> Thumbnails {
        Thumbnails {
            inner: Arc::new(Inner {
                url: url.to_owned(),
                stage: Mutex::new(Stage::Idle(Box::new((options, settings)), None)),
                closed: AtomicBool::new(false),
                on: Box::new(on),
            }),
        }
    }

    /// Asks for the thumbnail of the keyframe at or before `ms`; see [`Session::want`].
    pub fn want(&self, ms: u64, showing: Option<u64>) {
        self.ask(Some((ms, showing)));
    }

    /// Reads the file's index ahead of the first request, which then waits only for its keyframe.
    pub fn prepare(&self) {
        self.ask(None);
    }

    fn ask(&self, ms: Option<(u64, Option<u64>)>) {
        let Ok(mut stage) = self.inner.stage.lock() else {
            return;
        };
        let cached = match &mut *stage {
            Stage::Open(session) => ms.and_then(|(ms, showing)| session.want(ms, showing)),
            Stage::Opening(wanted) => {
                *wanted = ms.map(|(ms, _)| ms).or(*wanted);
                None
            }
            Stage::Failed => None,
            Stage::Idle(_, Some(retry)) if Instant::now() < *retry => None,
            Stage::Idle(..) => {
                let opening = Stage::Opening(ms.map(|(ms, _)| ms));
                let Stage::Idle(open, _) = std::mem::replace(&mut *stage, opening) else {
                    unreachable!()
                };
                self.open(*open);
                None
            }
        };
        drop(stage);
        if let Some(t) = cached {
            self.inner.emit(Event::Thumbnail(t));
        }
    }

    fn open(&self, (options, settings): (Options, Settings)) {
        let inner = self.inner.clone();
        let spawned = std::thread::Builder::new()
            .name("thumbnails-open".into())
            .spawn(move || {
                let ready = {
                    let inner = Arc::downgrade(&inner);
                    move |t| {
                        if let Some(inner) = inner.upgrade() {
                            inner.emit(Event::Thumbnail(t));
                        }
                    }
                };
                let again = Box::new((options.clone(), settings.clone()));
                let opened = Session::open(&inner.url, options, settings, ready);
                let Ok(mut stage) = inner.stage.lock() else {
                    return;
                };
                let wanted = match &*stage {
                    Stage::Opening(wanted) => *wanted,
                    _ => None,
                };
                match opened {
                    Ok(session) => {
                        let keyframes = session.keyframes().entries.len();
                        log::info!(target: "thumbnails", "opened the file: {keyframes} keyframes");
                        let cached = wanted.and_then(|ms| session.want(ms, None));
                        *stage = Stage::Open(session);
                        drop(stage);
                        if let Some(t) = cached {
                            inner.emit(Event::Thumbnail(t));
                        }
                    }
                    Err(e) if e.is_transient() => {
                        *stage = Stage::Idle(again, Some(Instant::now() + RETRY));
                        drop(stage);
                        log::warn!(
                            target: "thumbnails",
                            "opening the file failed, trying again in {} s: {e}",
                            RETRY.as_secs()
                        );
                    }
                    Err(e) => {
                        *stage = Stage::Failed;
                        drop(stage);
                        inner.emit(Event::Unavailable(e.to_string()));
                    }
                }
            });
        if let Err(e) = spawned {
            self.inner.emit(Event::Unavailable(e.to_string()));
        }
    }
}

impl Drop for Thumbnails {
    fn drop(&mut self) {
        self.inner.closed.store(true, Ordering::Release);
        if let Ok(mut stage) = self.inner.stage.lock() {
            *stage = Stage::Failed;
        }
    }
}
