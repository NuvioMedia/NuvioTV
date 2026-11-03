use std::time::Duration;

use aiostreams_container::Options;
use aiostreams_thumbs::{Event, Settings, Thumbnails};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::jlong;

/// Backs `Thumbnails.open`: the handle is a boxed [`Thumbnails`], which reports to `listener`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_tv_ui_screens_player_aiostreams_Thumbnails_open(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    headers: JObjectArray,
    ca_pem: JByteArray,
    listener: JObject,
) -> jlong {
    let e = |e: jni::errors::Error| e.to_string();
    let opened = (|| -> Result<Thumbnails, String> {
        let options = Options {
            timeout: Duration::from_secs(10),
            connect_timeout: Duration::from_secs(5),
            ..Options::default()
        };
        let options = crate::http::options(&mut env, &headers, &ca_pem, options)?;
        let url: String = env.get_string(&url).map_err(e)?.into();
        let vm = env.get_java_vm().map_err(e)?;
        let listener = env.new_global_ref(listener).map_err(e)?;
        let on = move |event: Event| {
            let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
                return;
            };
            // A daemon thread never returns to Java, so its references go with the frame.
            let _ = env.with_local_frame(4, |env| -> jni::errors::Result<()> {
                let (position, jpeg, error) = match event {
                    Event::Thumbnail(t) => (
                        t.time_ms as jlong,
                        JObject::from(env.byte_array_from_slice(&t.jpeg)?),
                        JObject::null(),
                    ),
                    Event::Unavailable(reason) => {
                        (-1, JObject::null(), JObject::from(env.new_string(reason)?))
                    }
                };
                let called = env.call_method(
                    &listener,
                    "onThumbnail",
                    "(J[BLjava/lang/String;)V",
                    &[
                        JValue::Long(position),
                        JValue::Object(&jpeg),
                        JValue::Object(&error),
                    ],
                );
                if called.is_err() {
                    env.exception_clear()?;
                }
                Ok(())
            });
        };
        Ok(Thumbnails::new(&url, options, Settings::default(), on))
    })();
    match opened {
        Ok(thumbnails) => Box::into_raw(Box::new(thumbnails)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/io/IOException", error);
            0
        }
    }
}

/// Backs `Thumbnails.want`; a negative `showing_ms` is none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_tv_ui_screens_player_aiostreams_Thumbnails_want(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    position_ms: jlong,
    showing_ms: jlong,
) {
    if let Some(thumbnails) = unsafe { (handle as *const Thumbnails).as_ref() } {
        thumbnails.want(position_ms.max(0) as u64, u64::try_from(showing_ms).ok());
    }
}

/// Backs `Thumbnails.prepare`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_tv_ui_screens_player_aiostreams_Thumbnails_prepare(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(thumbnails) = unsafe { (handle as *const Thumbnails).as_ref() } {
        thumbnails.prepare();
    }
}

/// Backs `Thumbnails.close`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nuvio_tv_ui_screens_player_aiostreams_Thumbnails_close(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Thumbnails) });
    }
}
