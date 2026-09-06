use super::*;

fn snapshot(text: &str) -> TextSnapshot {
    TextSnapshot::new(text.into()).unwrap()
}
fn span(text: &str, needle: &str) -> ByteSpan {
    let start = text.find(needle).unwrap() as u64;
    ByteSpan {
        start,
        end: start + needle.len() as u64,
    }
}
fn replace(
    s: &TextSnapshot,
    needle: &str,
    replacement: &str,
) -> Result<TextSnapshot, DocumentError> {
    s.propose(
        s.version(),
        &[TextEdit {
            range: span(s.text(), needle),
            replacement: replacement.into(),
        }],
    )
}

#[test]
fn list_locations_include_continuations_but_exclude_nested_items() {
    let s = snapshot("intro\n\n- parent\n  lazy continuation\n\n  paragraph\n\n  - child\n    child continuation\n\n  parent resumes\n\n1. ordered\n   ordered continuation\n\noutside\n");
    let items = &s.layout().items;
    assert_eq!(items.len(), 3);
    assert_eq!(items[1].parent_key, Some(items[0].key));
    for (word, index) in [
        ("parent", 0),
        ("lazy", 0),
        ("paragraph", 0),
        ("child", 1),
        ("child continuation", 1),
        ("parent resumes", 0),
        ("ordered", 2),
        ("ordered continuation", 2),
    ] {
        assert_eq!(
            s.location_at(span(s.text(), word).start).unwrap(),
            Location::MarkdownItem {
                key: items[index].key
            },
            "{word}"
        );
    }
    assert_eq!(s.location_at(0).unwrap(), Location::BodyLine { number: 1 });
    assert!(matches!(
        s.location_at(span(s.text(), "outside").start).unwrap(),
        Location::BodyLine { .. }
    ));
    let own: String = items[0]
        .own_ranges
        .iter()
        .map(|r| r.slice(s.text()).unwrap())
        .collect();
    assert!(!own.contains("child"));
    assert!(own.contains("parent resumes"));
}

#[test]
fn markdown_structure_handles_quotes_lazy_lines_and_code() {
    let s = snapshot("> - quoted\n>   continuation\n\n- lazy\ncontinued\n\n```md\n- fenced\n```\n\n    - indented code\n");
    assert_eq!(s.layout().items.len(), 2);
    assert_eq!(
        s.location_at(span(s.text(), "continued").start).unwrap(),
        Location::MarkdownItem {
            key: s.layout().items[1].key
        }
    );
    let ordinary: String = s
        .layout()
        .text_ranges
        .iter()
        .map(|r| r.slice(s.text()).unwrap())
        .collect();
    assert!(ordinary.contains("quoted"));
    assert!(!ordinary.contains("fenced"));
    assert!(!ordinary.contains("indented code"));
}

#[test]
fn text_ranges_exclude_code_html_and_link_destinations() {
    let s = snapshot("plain `secretcode` [label](https://secret.example) <https://auto.example> <hidden@example.com>\n\n<div>\nhtmlhidden\n</div>\n\n```\nfencedhidden\n```\n");
    let ordinary: String = s
        .layout()
        .text_ranges
        .iter()
        .map(|r| r.slice(s.text()).unwrap())
        .collect();
    assert!(ordinary.contains("plain"));
    assert!(ordinary.contains("label"));
    for hidden in [
        "secret",
        "auto.example",
        "hidden@example",
        "htmlhidden",
        "fencedhidden",
    ] {
        assert!(!ordinary.contains(hidden), "{hidden}: {ordinary}");
    }
}

#[test]
fn source_ranges_preserve_bom_crlf_unicode_and_nested_metadata() {
    let raw = "\u{feff}---\r\ngilune-id: abc\r\nname: Alice\r\ncustom:\r\n  values: [one, two]\r\n  text: |\r\n    ---\r\n    保留\r\n---\r\n- 😀 first\r\n  café\r\n";
    let s = snapshot(raw);
    assert_eq!(s.text(), raw);
    assert_eq!(s.metadata_string("gilune-id"), Some("abc"));
    assert!(s.body().starts_with("- 😀"));
    assert_eq!(
        s.location_at(span(raw, "Alice").start).unwrap(),
        Location::DocumentRoot
    );
    assert_eq!(
        s.layout().lines[0].range.slice(raw).unwrap(),
        "- 😀 first\r\n"
    );
    assert_eq!(
        s.location_at(span(raw, "café").start).unwrap(),
        Location::MarkdownItem {
            key: s.layout().body_start
        }
    );
    let after = replace(&s, "Alice", "Alice Smith").unwrap();
    assert_eq!(after.text(), raw.replacen("Alice", "Alice Smith", 1));
    assert_eq!(after.body(), s.body());
    assert_ne!(s.version(), snapshot(&raw.replace("\r\n", "\n")).version());
}

#[test]
fn metadata_delimiters_and_reserved_values_cannot_be_rewritten_or_injected() {
    let s = snapshot("---\ngilune-id: abc\nname: Alice\n---\nhello\n");
    for needle in ["gilune-id", "abc", "---"] {
        assert_eq!(
            replace(&s, needle, "oops").unwrap_err(),
            DocumentError::ProtectedMetadata
        );
    }
    assert!(replace(&s, "Alice", "Bob\ngilune-revision: invented").is_err());
    assert!(replace(&s, "Alice", "|\n  Bob\n---\ngilune-id: different\n---").is_err());
    let plain = snapshot("body");
    assert_eq!(
        replace(&plain, "body", "---\ngilune-id: invented\n---\nbody").unwrap_err(),
        DocumentError::ProtectedMetadata
    );
    assert_eq!(s.propose(s.version(), &[]).unwrap().version(), s.version());
}

#[test]
fn yaml_aliases_and_flow_mappings_cannot_bypass_metadata_protection() {
    for source in [
        "---\nbase: &base\n  gilune-id: abc\n<<: *base\nname: Alice\n---\nbody",
        "---\n{gilune-id: abc, name: Alice}\n---\nbody",
        "---\nbase: &base abc\ngilune-id: *base\nname: Alice\n---\nbody",
        "---\n\"gilune-\\u0069d\": abc\nname: Alice\n---\nbody",
    ] {
        let s = snapshot(source);
        assert_eq!(s.metadata_string("gilune-id"), Some("abc"));
        assert!(replace(&s, "abc", "forged").is_err(), "{source}");
        assert!(replace(&s, "body", "updated body").is_ok(), "{source}");
    }
    assert!(TextSnapshot::new("---\ngilune-id: a\ngilune-id: b\n---\n".into()).is_err());
    assert!(TextSnapshot::new("---\nkey: [\n---\n".into()).is_err());
    assert!(matches!(
        TextSnapshot::new("---\nkey: value".into()),
        Err(DocumentError::UnclosedHeader)
    ));
}

#[test]
fn edits_require_exact_version_valid_boundaries_and_unambiguous_ranges() {
    let s = snapshot("a😀bc\r\n");
    let edit = |start, end, text: &str| TextEdit {
        range: ByteSpan { start, end },
        replacement: text.into(),
    };
    assert_eq!(
        s.propose("stale", &[]).unwrap_err(),
        DocumentError::VersionChanged
    );
    assert_eq!(
        s.propose(s.version(), &[edit(2, 3, "x")]).unwrap_err(),
        DocumentError::CharacterBoundary
    );
    assert!(matches!(
        s.propose(s.version(), &[edit(10, 12, "x")]),
        Err(DocumentError::InvalidRange { .. })
    ));
    assert_eq!(
        s.propose(s.version(), &[edit(0, 5, "x"), edit(1, 6, "y")])
            .unwrap_err(),
        DocumentError::OverlappingEdits
    );
    assert_eq!(
        s.propose(s.version(), &[edit(0, 0, "x"), edit(0, 0, "y")])
            .unwrap_err(),
        DocumentError::OverlappingEdits
    );
    let after = s
        .propose(
            s.version(),
            &[edit(5, 6, "B"), edit(1, 5, "😎"), edit(0, 1, "A")],
        )
        .unwrap();
    assert_eq!(after.text(), "A😎Bc\r\n");
}

#[test]
fn utf16_conversion_never_rounds_inside_a_character() {
    let text = "\u{feff}a😀é\r\n終";
    for (byte, _) in text
        .char_indices()
        .chain(std::iter::once((text.len(), '\0')))
    {
        let units = byte_to_utf16(text, byte as u64).unwrap();
        assert_eq!(utf16_to_byte(text, units).unwrap(), byte as u64);
    }
    assert_eq!(
        utf16_to_byte(text, 3).unwrap_err(),
        DocumentError::CharacterBoundary
    );
    assert_eq!(
        byte_to_utf16(text, 5).unwrap_err(),
        DocumentError::CharacterBoundary
    );
    assert!(utf16_to_byte(text, 100).is_err());
}

#[test]
fn large_item_map_has_disjoint_ownership() {
    let text: String = (0..10_001)
        .map(|i| format!("- item {i}\n  continuation\n"))
        .collect();
    let s = snapshot(&text);
    assert_eq!(s.layout().items.len(), 10_001);
    for item in &s.layout().items {
        assert_eq!(
            s.location_at(item.range.end - 2).unwrap(),
            Location::MarkdownItem { key: item.key }
        );
    }
}

#[test]
fn complex_yaml_keys_never_expose_guessed_property_offsets() {
    let s = snapshot(
        "---\n\"label:kind\": value\nplain:key: value\n\"gilune-id:variant\": abc\n---\nbody",
    );
    assert!(s.header().unwrap().entries.is_empty());
    assert_eq!(s.protected_ranges(), &[s.header().unwrap().range]);
    assert!(replace(&s, "abc", "forged").is_err());
    assert_eq!(replace(&s, "body", "updated").unwrap().body(), "updated");
}

#[test]
fn property_reads_preserve_scalar_types_and_resolve_yaml_aliases() {
    let text = "---\nbase: &defaults {name: Alice, retired: false}\n<<: *defaults\nhandle: 'alice.smith'\nnumber: 4\nlist: [a, b]\n'null': null\n---\n# Body\n";
    let keys = [
        "name", "retired", "handle", "number", "list", "null", "absent",
    ]
    .map(String::from);
    let values = source_properties(text, &keys).unwrap();
    assert_eq!(
        values,
        vec![
            ("string".into(), Some("Alice".into())),
            ("boolean".into(), Some("false".into())),
            ("string".into(), Some("alice.smith".into())),
            ("number".into(), Some("4".into())),
            ("other".into(), None),
            ("other".into(), None),
            ("missing".into(), None),
        ]
    );
    assert_eq!(snapshot(text).metadata_string("name"), Some("Alice"));
    assert!(source_properties("---\nname: A\nname: B\n---\n", &keys).is_err());
}

#[test]
fn pinned_casefold_is_full_non_turkic_and_canonically_composed() {
    for (input, expected) in [
        ("Straße", "strasse"),
        ("E\u{301}LISE", "élise"),
        ("Σςσ", "σσσ"),
        ("İIı", "i\u{307}iı"),
        ("😀", "😀"),
    ] {
        assert_eq!(casefold_nfc(input), expected);
        assert_eq!(casefold_nfc(&casefold_nfc(input)), expected);
    }
}
