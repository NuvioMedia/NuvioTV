use std::fmt;

#[derive(Debug)]
pub enum Error {
    Http(String),
    /// A byte, request or time cap of the reader was reached.
    Budget,
    NoIndex,
    Unsupported(String),
    Malformed(&'static str),
}

pub type Result<T> = std::result::Result<T, Error>;

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Error::Http(e) => write!(f, "http: {e}"),
            Error::Budget => f.write_str("read budget spent"),
            Error::NoIndex => f.write_str("no index"),
            Error::Unsupported(what) => write!(f, "unsupported: {what}"),
            Error::Malformed(what) => write!(f, "malformed {what}"),
        }
    }
}

impl std::error::Error for Error {}
