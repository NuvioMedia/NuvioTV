use aiostreams_container::{Options, RangeReader};
use jni::JNIEnv;
use jni::objects::{JByteArray, JObjectArray, JString};

/// `headers` alternates names and values; `ca_pem` is the device's trusted roots, user-installed ones included.
pub fn reader(
    env: &mut JNIEnv,
    url: &JString,
    headers: &JObjectArray,
    ca_pem: &JByteArray,
    options: Options,
) -> Result<RangeReader, String> {
    let url: String = env.get_string(url).map_err(|e| e.to_string())?.into();
    let options = self::options(env, headers, ca_pem, options)?;
    RangeReader::new(&url, options).map_err(|e| e.to_string())
}

/// [`reader`]'s options, for a reader opened later.
pub fn options(
    env: &mut JNIEnv,
    headers: &JObjectArray,
    ca_pem: &JByteArray,
    mut options: Options,
) -> Result<Options, String> {
    let e = |e: jni::errors::Error| e.to_string();
    if !headers.is_null() {
        let n = env.get_array_length(headers).map_err(e)?;
        for i in (0..n - n % 2).step_by(2) {
            let name = JString::from(env.get_object_array_element(headers, i).map_err(e)?);
            let value = JString::from(env.get_object_array_element(headers, i + 1).map_err(e)?);
            let name: String = env.get_string(&name).map_err(e)?.into();
            let value: String = env.get_string(&value).map_err(e)?.into();
            options.headers.push((name, value));
        }
    }
    if !ca_pem.is_null() {
        options.ca_pem = Some(env.convert_byte_array(ca_pem).map_err(e)?);
    }
    Ok(options)
}
