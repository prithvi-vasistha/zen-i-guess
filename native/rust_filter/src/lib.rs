use jni::objects::{JClass, JString};
use jni::sys::{jboolean, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashSet;
use std::sync::{OnceLock, RwLock};

static MANAGED_APPS: OnceLock<RwLock<HashSet<String>>> = OnceLock::new();
static CONTACT_WHITELIST: OnceLock<RwLock<HashSet<String>>> = OnceLock::new();
static KEYWORD_WHITELIST: OnceLock<RwLock<Vec<Vec<String>>>> = OnceLock::new();

/// Returns the RwLock for the given set, initialising it on first access.
fn init_set(
    lock: &'static OnceLock<RwLock<HashSet<String>>>,
) -> &'static RwLock<HashSet<String>> {
    lock.get_or_init(|| RwLock::new(HashSet::new()))
}

/// Returns the RwLock for the keyword rule vec, initialising it on first access.
fn init_vec(
    lock: &'static OnceLock<RwLock<Vec<Vec<String>>>>,
) -> &'static RwLock<Vec<Vec<String>>> {
    lock.get_or_init(|| RwLock::new(Vec::new()))
}

// ── Managed apps (opt-in set) ─────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_dev_zig_notificationfilter_data_local_NativeBridge_isAppManaged<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    package_name: JString<'local>,
) -> jboolean {
    let name: String = match env.get_string(&package_name) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    match init_set(&MANAGED_APPS).read() {
        Ok(set) => {
            if set.contains(&name) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        Err(_) => JNI_FALSE, // lock poisoned — fail safe
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_zig_notificationfilter_data_local_NativeBridge_addAppToManaged<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    package_name: JString<'local>,
) {
    let name: String = match env.get_string(&package_name) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    if let Ok(mut set) = init_set(&MANAGED_APPS).write() {
        set.insert(name);
    }
    // write lock poisoned → skip silently; set remains intact from last good state
}

// ── Keyword whitelist (chained AND rules, OR across rules) ───────────────────

#[no_mangle]
pub extern "system" fn Java_dev_zig_notificationfilter_data_local_NativeBridge_addKeywordRuleToWhitelist<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    joined_keywords: JString<'local>,
) {
    let joined: String = match env.get_string(&joined_keywords) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    // Split on "||", discard empty tokens from leading/trailing delimiters, lowercase each term.
    let conditions: Vec<String> = joined
        .split("||")
        .filter(|s| !s.is_empty())
        .map(|s| s.to_lowercase())
        .collect();
    if conditions.is_empty() {
        return;
    }
    if let Ok(mut rules) = init_vec(&KEYWORD_WHITELIST).write() {
        rules.push(conditions);
    }
    // write lock poisoned → skip silently; existing rules remain intact
}

#[no_mangle]
pub extern "system" fn Java_dev_zig_notificationfilter_data_local_NativeBridge_containsWhitelistedKeyword<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    content: JString<'local>,
) -> jboolean {
    let body: String = match env.get_string(&content) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let body_lower = body.to_lowercase();
    match init_vec(&KEYWORD_WHITELIST).read() {
        Ok(rules) => {
            // OR across rules: any single rule whose ALL conditions appear in the body → TRUE.
            if rules.iter().any(|rule| {
                rule.iter().all(|kw| body_lower.contains(kw.as_str()))
            }) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        Err(_) => JNI_FALSE, // lock poisoned — fail safe
    }
}

// ── Contact whitelist ─────────────────────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_dev_zig_notificationfilter_data_local_NativeBridge_isContactWhitelisted<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    contact_name: JString<'local>,
) -> jboolean {
    let name: String = match env.get_string(&contact_name) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    match init_set(&CONTACT_WHITELIST).read() {
        Ok(set) => {
            if set.contains(&name) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_zig_notificationfilter_data_local_NativeBridge_addContactToWhitelist<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    contact_name: JString<'local>,
) {
    let name: String = match env.get_string(&contact_name) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    if let Ok(mut set) = init_set(&CONTACT_WHITELIST).write() {
        set.insert(name);
    }
}
