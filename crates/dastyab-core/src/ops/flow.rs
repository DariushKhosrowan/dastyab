//! Embedded Google Flow bridge.
//!
//! `dastyab flow` shells out to a Python helper that drives an xray
//! tunnel with a verified supported-country egress (see the script's
//! docstring for the full layer ladder). Shipping it as a separate file
//! broke on installed binaries: the file is simply not there, so the
//! command could only ever report "not found". The helper is
//! stdlib-only and does not depend on its own path, so it is compiled
//! into the binary and materialised on demand.
//!
//! If `$DASTYAB_FLOW` is set, that path wins — an operator can override
//! the embedded copy without rebuilding.

/// The Flow bridge script, verbatim from `dastyab-flow.py`.
pub const FLOW_PY: &str = include_str!("../../../../dastyab-flow.py");

/// Write the embedded script to a per-user cache path and return it.
///
/// The file is rewritten when its contents differ, so an upgraded binary
/// always runs the matching script. Returns `None` if it cannot be
/// written, in which case the caller falls back to any on-disk copy it
/// can find.
pub fn materialise() -> Option<std::path::PathBuf> {
    let dir = crate::store::mem_dir().join("bin");
    std::fs::create_dir_all(&dir).ok()?;
    let path = dir.join("dastyab-flow.py");

    let needs_write = match std::fs::read_to_string(&path) {
        Ok(existing) => existing != FLOW_PY,
        Err(_) => true,
    };
    if needs_write {
        std::fs::write(&path, FLOW_PY).ok()?;
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
        assert!(FLOW_PY.starts_with("#!/usr/bin/env python3"));
        assert!(FLOW_PY.contains("if __name__"));
    }

    /// The gate is only as honest as the country list it checks against:
    /// the embedded list must match Google's official rollout document.
    #[test]
    fn embedded_country_list_matches_google_rollout() {
        // support.google.com/flow/answer/16353544 (fetched 2026-11-16).
        let official = "AL DZ AS AO AG AR AM AW AU AT AZ BS BH BD BE BZ BJ BM BO BA BW BR BG BF \
CV KH CM CA KY CL CO CR CI HR CY CZ DK DO EC EG SV EE FJ FI FR GA DE GH \
GI GR GU GT GW HT HN HK HU IS IN ID IQ IE IL IT JM JP JO KZ KE KW KG LA \
LV LB LI LT LU MO MY MV ML MT MU MX MD MC MA MZ NA NP NL NZ NI NE NG MK \
MP NO OM PK PW PA PG PY PE PH PL PT PR QA RO RW SM SA SN RS SC SL SG SK \
SI ZA KR ES LK SE CH TW TJ TZ TH TG TO TT TN TR TM VI UG UA AE GB US UY \
UZ VE VN VG YE ZM ZW";

        // Extract the script's frozenset block and parse it as codes.
        let start = FLOW_PY
            .find("SUPPORTED_COUNTRIES = frozenset(\"\"\"")
            .expect("country set declaration present");
        let rest = &FLOW_PY[start + "SUPPORTED_COUNTRIES = frozenset(\"\"\"".len()..];
        let end = rest.find("\"\"").expect("country set closes");
        let embedded: std::collections::BTreeSet<&str> = rest[..end].split_whitespace().collect();
        let official_set: std::collections::BTreeSet<&str> = official.split_whitespace().collect();

        assert_eq!(
            embedded, official_set,
            "embedded Flow country list diverged from Google's rollout document"
        );
    }

    /// An upgraded binary must not keep executing a stale staged copy: the
    /// embedded script is authoritative and gets rewritten when it differs.
    #[test]
    fn materialise_refreshes_a_stale_copy() {
        let dir = tempfile::tempdir().unwrap();
        crate::store::set_mem_dir_override(dir.path().join(".dastyab"));
        let bin = crate::store::mem_dir().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let path = bin.join("dastyab-flow.py");
        std::fs::write(&path, b"#!/usr/bin/env python3\n# stale\n").unwrap();

        let written = materialise().expect("rewritten");
        let text = std::fs::read_to_string(written).unwrap();
        assert_eq!(text, FLOW_PY, "stale copy must be replaced");
        assert!(!text.contains("# stale"));
    }

    #[test]
    fn materialise_writes_readable_script() {
        let dir = tempfile::tempdir().unwrap();
        crate::store::set_mem_dir_override(dir.path().join(".dastyab"));
        let p = materialise().expect("materialised");
        assert!(p.is_file());
        let text = std::fs::read_to_string(&p).unwrap();
        assert_eq!(text, FLOW_PY);
    }
}
