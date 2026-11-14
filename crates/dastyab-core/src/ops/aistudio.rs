//! Embedded AI Studio bridge.
//!
//! `dastyab aistudio` shells out to a Python helper that drives an xray
//! tunnel. Shipping it as a separate file broke on installed binaries: the
//! file is simply not there, so the command could only ever report "not
//! found". The helper is stdlib-only and does not depend on its own path, so
//! it is compiled into the binary and materialised on demand.
//!
//! If `$DASTYAB_AISTUDIO` is set, that path wins — an operator can override
//! the embedded copy without rebuilding.

/// The AI Studio bridge script, verbatim from `dastyab-aistudio.py`.
pub const AISTUDIO_PY: &str = include_str!("../../../../dastyab-aistudio.py");

/// Write the embedded script to a per-user cache path and return it.
///
/// The file is rewritten when its contents differ, so an upgraded binary
/// always runs the matching script. Returns `None` if it cannot be written,
/// in which case the caller falls back to any on-disk copy it can find.
pub fn materialise() -> Option<std::path::PathBuf> {
    let dir = crate::store::mem_dir().join("bin");
    std::fs::create_dir_all(&dir).ok()?;
    let path = dir.join("dastyab-aistudio.py");

    let needs_write = match std::fs::read_to_string(&path) {
        Ok(existing) => existing != AISTUDIO_PY,
        Err(_) => true,
    };
    if needs_write {
        std::fs::write(&path, AISTUDIO_PY).ok()?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700));
        }
    }
    Some(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn embedded_script_is_a_python_program() {
        assert!(AISTUDIO_PY.starts_with("#!/usr/bin/env python3"));
        assert!(AISTUDIO_PY.contains("def main(") || AISTUDIO_PY.contains("if __name__"));
    }

    /// An upgraded binary must not keep executing a stale staged copy: the
    /// embedded script is authoritative and gets rewritten when it differs.
    #[test]
    fn materialise_refreshes_a_stale_copy() {
        let dir = tempfile::tempdir().unwrap();
        crate::store::set_mem_dir_override(dir.path().join(".dastyab"));
        let bin = crate::store::mem_dir().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let path = bin.join("dastyab-aistudio.py");
        std::fs::write(&path, b"#!/usr/bin/env python3\n# stale\n").unwrap();

        let written = materialise().expect("rewritten");
        let text = std::fs::read_to_string(written).unwrap();
        assert_eq!(text, AISTUDIO_PY, "stale copy must be replaced");
        assert!(!text.contains("# stale"));
    }

    #[test]
    fn materialise_writes_readable_script() {
        let dir = tempfile::tempdir().unwrap();
        crate::store::set_mem_dir_override(dir.path().join(".dastyab"));
        let p = materialise().expect("materialised");
        assert!(p.is_file());
        let text = std::fs::read_to_string(&p).unwrap();
        assert_eq!(text, AISTUDIO_PY);
    }
}
