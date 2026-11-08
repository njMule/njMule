// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ApiSpecificationTest, under its names: the OpenAPI document of the API matches the engine and the
//! code that checks every call against it.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use njmule_engine::api::download_info::{DownloadPriority, DownloadStatus, SourceState};
use njmule_engine::api::kad_info::Buddy;
use njmule_engine::api::kad_search_info::Kind;
use njmule_engine::api::log_line::Level;
use njmule_engine::api::search_request::Scope;
use njmule_engine::api::upload_info::UploadState;
use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::config::preferences::MAX_RATE_KIBPS;
use njmule_engine::remote::api_specification::{self, ApiSpecification};
use njmule_engine::remote::remote_api;
use njmule_engine::server::server_list::Priority;
use serde_json::Value;
use strum::VariantArray;

#[test]
fn should_list_the_values_of_an_enum_of_the_engine() {
    for (pointer, names) in enums() {
        let mut test = Specification::new();

        test.when_the_values_are_read(pointer);

        test.then_they_are(&names);
    }
}

// Where the document lists the values of each enum of the engine, and their names.
fn enums() -> [(&'static str, Vec<&'static str>); 9] {
    [
        ("/components/schemas/DownloadPriority/enum", DownloadPriority::VARIANTS.iter().map(|v| v.name()).collect()),
        ("/components/schemas/UploadPriority/enum", UploadPriority::ALL.iter().map(|v| v.name()).collect()),
        (
            "/components/schemas/Download/properties/status/enum",
            DownloadStatus::VARIANTS.iter().map(|v| v.name()).collect(),
        ),
        ("/components/schemas/Server/properties/priority/enum", Priority::VARIANTS.iter().map(|v| v.name()).collect()),
        ("/components/schemas/Kad/properties/buddy/enum", Buddy::VARIANTS.iter().map(|v| v.name()).collect()),
        ("/components/schemas/Source/properties/state/enum", SourceState::VARIANTS.iter().map(|v| v.name()).collect()),
        ("/components/schemas/Upload/properties/state/enum", UploadState::VARIANTS.iter().map(|v| v.name()).collect()),
        ("/components/schemas/KadSearch/properties/kind/enum", Kind::VARIANTS.iter().map(|v| v.name()).collect()),
        ("/components/schemas/LogLine/properties/level/enum", Level::VARIANTS.iter().map(|v| v.name()).collect()),
    ]
}

#[test]
fn should_offer_the_search_scopes_of_the_engine_and_automatic() {
    let mut test = Specification::new();

    test.when_the_values_are_read("/components/schemas/SearchScope/enum");

    test.then_they_are_the_names_of_the_scopes_and("automatic");
}

#[test]
fn should_cap_the_speed_limits_as_the_preferences_do() {
    let mut test = Specification::new();

    test.when_the_values_are_read("/components/schemas/Limits/properties/uploadKiBps/maximum");

    test.then_they_are(&[&MAX_RATE_KIBPS.to_string()]);
}

#[test]
fn should_serve_under_the_prefix_of_the_api() {
    let mut test = Specification::new();

    test.when_the_values_are_read("/servers/0/url");

    test.then_the_only_value_ends_with(remote_api::PREFIX);
}

#[test]
fn should_have_the_version_of_the_api() {
    let mut test = Specification::new();

    test.when_the_values_are_read("/info/version");

    test.then_they_are(&[&remote_api::VERSION.to_string()]);
}

#[test]
fn should_match_a_concrete_path_before_a_templated_one() {
    let mut test = Specification::new();

    test.when_a_request_is_routed(&["servers", "connect"]);

    test.then_it_goes_to("/servers/connect");
}

#[test]
fn should_match_a_templated_path() {
    let mut test = Specification::new();

    test.when_a_request_is_routed(&["servers", "203.0.113.10:4661"]);

    test.then_it_goes_to("/servers/{address}");
}

#[test]
fn should_refuse_a_document_with_parameters_it_cannot_check() {
    let mut test = Specification::new();
    test.given_the_document_changed("in: query", "in: header");

    test.when_the_document_is_read();

    test.then_it_is_refused_as("not supported");
}

#[test]
fn should_refuse_an_operation_without_id() {
    let mut test = Specification::new();
    // Not a second summary, as the Java test writes: a YAML mapping holds each key once.
    test.given_the_document_changed("operationId: getStatus", "description: no id");

    test.when_the_document_is_read();

    test.then_it_is_refused_as("has no operationId");
}

#[test]
fn should_refuse_an_operation_id_used_twice() {
    let mut test = Specification::new();
    test.given_the_document_changed("operationId: getLimits", "operationId: getStatus");

    test.when_the_document_is_read();

    test.then_it_is_refused_as("used twice");
}

struct Specification {
    yaml: String,
    found: Vec<String>,
    route: Option<String>,
    read: Option<Result<(), String>>,
}

impl Specification {
    fn new() -> Self {
        Self { yaml: api_specification::DOCUMENT.to_owned(), found: Vec::new(), route: None, read: None }
    }

    fn given_the_document_changed(&mut self, from: &str, to: &str) {
        assert!(self.yaml.contains(from), "{from}");
        self.yaml = self.yaml.replace(from, to);
    }

    fn when_the_values_are_read(&mut self, pointer: &str) {
        let node = shipped().document().pointer(pointer).unwrap_or_else(|| panic!("{pointer}"));
        self.found = match node {
            Value::Array(values) => values.iter().map(text).collect(),
            value => vec![text(value)],
        };
    }

    fn when_a_request_is_routed(&mut self, segments: &[&str]) {
        let segments: Vec<String> = segments.iter().map(|segment| (*segment).to_owned()).collect();
        self.route = shipped().route(&segments).map(|route| route.path.clone());
    }

    fn when_the_document_is_read(&mut self) {
        self.read = Some(ApiSpecification::parse(&self.yaml).map(|_| ()));
    }

    fn then_they_are(&self, values: &[&str]) {
        assert_eq!(self.found, values);
    }

    fn then_they_are_the_names_of_the_scopes_and(&self, more: &str) {
        let mut names: Vec<&str> = Scope::VARIANTS.iter().map(|scope| scope.name()).collect();
        names.push(more);
        assert_eq!(self.found, names);
    }

    fn then_the_only_value_ends_with(&self, end: &str) {
        assert_eq!(self.found.len(), 1, "{:?}", self.found);
        assert!(self.found[0].ends_with(end), "{:?}", self.found);
    }

    fn then_it_goes_to(&self, path: &str) {
        assert_eq!(self.route.as_deref(), Some(path));
    }

    fn then_it_is_refused_as(&self, why: &str) {
        match &self.read {
            Some(Err(reason)) => assert!(reason.contains(why), "{reason}"),
            other => panic!("not refused: {other:?}"),
        }
    }
}

fn shipped() -> &'static ApiSpecification {
    ApiSpecification::shipped().unwrap()
}

// A value as text: a string as it is, anything else as JSON writes it.
fn text(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        other => other.to_string(),
    }
}
